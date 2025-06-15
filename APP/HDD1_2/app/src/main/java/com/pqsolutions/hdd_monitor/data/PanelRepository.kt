package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.FieldValue
import com.pqsolutions.hdd_monitor.data.util.IdManager
import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import com.pqsolutions.hdd_monitor.util.StatusUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PanelRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val esp32Repository: ESP32Repository
) {
    companion object {
        private const val TAG = "PanelRepository"
        private const val BASE_PATH = "hdd-monitor/accounts/clients"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY = 500L
    }

    // Usar ConcurrentHashMap para manejar concurrencia de forma segura
    private val activeListeners = ConcurrentHashMap<String, ListenerRegistration>()

    // Caché de estados de relays para acceso rápido
    private val relayStatusCache = ConcurrentHashMap<String, Map<String, String>>()

    // Caché de estados ESP32
    private val esp32StatusCache = ConcurrentHashMap<String, String>()

    // Scope para operaciones en segundo plano con SupervisorJob para mejor manejo de errores
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val panelsFlows = ConcurrentHashMap<String, MutableSharedFlow<List<Panel>>>()

    private suspend fun <T> withRetry(
        maxRetries: Int = MAX_RETRIES,
        initialDelay: Long = INITIAL_RETRY_DELAY,
        operation: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                // No reintentar si la corrutina fue cancelada
                if (e is kotlinx.coroutines.CancellationException) throw e

                if (attempt == maxRetries - 1) throw e

                Log.e(TAG, "Operation failed, retrying (${attempt + 1}/$maxRetries)", e)
                delay(currentDelay)
                currentDelay *= 2 // Exponential backoff
            }
        }
        error("This line should never be reached")
    }

    fun clearListeners() {
        Log.d(TAG, "Clearing all panel and relay listeners: ${activeListeners.size} listeners")

        // Crear una copia para evitar modificaciones concurrentes
        val listenersToRemove = activeListeners.toMap()

        // Limpiar las colecciones primero
        activeListeners.clear()
        relayStatusCache.clear()
        esp32StatusCache.clear()

        // Remover cada listener de forma segura
        listenersToRemove.values.forEach { listener ->
            try {
                listener.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener", e)
            }
        }

        Log.d(TAG, "All listeners cleared successfully")
    }

    suspend fun forceRefreshFromServer() {
        try {
            Log.d(TAG, "Forzando refresco desde servidor")

            // NO limpiar listeners aquí - solo forzar consulta al servidor

            // Verificar conexión con consulta simple
            firestore.collection(BASE_PATH)
                .limit(1)
                .get(Source.SERVER)
                .await()

            Log.d(TAG, "Refresco forzado completado")
        } catch (e: Exception) {
            Log.e(TAG, "Error en refresco forzado", e)
            throw e
        }
    }

    fun getPanels(clientDocName: String?): Flow<List<Panel>> = callbackFlow {
        Log.d(TAG, "getPanels called with clientDocName: $clientDocName")

        val flowKey = "panels_${clientDocName ?: "all"}"
        val sharedFlow = panelsFlows.getOrPut(flowKey) {
            MutableSharedFlow<List<Panel>>(replay = 1)
        }

        val existingListenerKey = activeListeners.keys.find {
            it.startsWith(flowKey + "_")
        }

        if (existingListenerKey == null) {
            val currentPanels = Collections.synchronizedList(mutableListOf<Panel>())
            val listenerId = "${flowKey}_${System.currentTimeMillis()}"

            try {
                val registration = if (clientDocName != null) {
                    setupClientPanels(clientDocName, currentPanels) { panels ->
                        sharedFlow.tryEmit(panels.toList())
                    }
                } else {
                    setupAllClientsPanels(currentPanels) { panels ->
                        sharedFlow.tryEmit(panels.toList())
                    }
                }

                activeListeners[listenerId] = registration

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up panels listener", e)
                close(e)
                return@callbackFlow
            }
        }

        val job = launch {
            sharedFlow.collect { panels ->
                trySend(panels)
            }
        }

        awaitClose {
            Log.d(TAG, "Closing panel flow subscription")
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun getCurrentPanelsForClient(clientDocName: String): List<Panel> {
        return try {
            val panelsSnapshot = firestore
                .collection("$BASE_PATH/$clientDocName/panels")
                .get(Source.CACHE) // Usar caché para rapidez
                .await()

            val clientDisplayName = getClientDisplayName(clientDocName)

            panelsSnapshot.documents.mapNotNull { doc ->
                doc.toObject(Panel::class.java)?.copy(
                    documentName = doc.id,
                    clientName = clientDocName,
                    clientDisplayName = clientDisplayName,
                    lastUpdate = doc.getLong("lastUpdate") ?: System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current panels for client", e)
            emptyList()
        }
    }

    private suspend fun setupRelayListenerAsync(clientDocName: String, panel: Panel) {
        val relayListenerId = "relays_${clientDocName}_${panel.documentName}"

        if (activeListeners.containsKey(relayListenerId)) {
            Log.d(TAG, "Relay listener already exists for $relayListenerId")
            return
        }

        try {
            // Cargar relays iniciales
            val relaysSnapshot = firestore
                .collection("$BASE_PATH/$clientDocName/panels/${panel.documentName}/relays")
                .get(Source.SERVER)
                .await()

            val initialRelays = relaysSnapshot.documents.mapNotNull { doc ->
                try {
                    Relay.fromMap(
                        doc.data?.plus(mapOf("name" to doc.id)) ?: emptyMap()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting relay", e)
                    null
                }
            }

            panel.relays = initialRelays

            val registration = firestore
                .collection("$BASE_PATH/$clientDocName/panels/${panel.documentName}/relays")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error in relay listener for panel ${panel.documentName}", error)
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        var hasChanges = false

                        val updatedRelays = snapshot.documents.mapNotNull { doc ->
                            try {
                                val relayData = doc.data ?: emptyMap()
                                val relay = Relay.fromMap(relayData.plus(mapOf("name" to doc.id)))

                                val cacheKey = "${clientDocName}_${panel.documentName}_${doc.id}"
                                val oldStatus = relayStatusCache[cacheKey]?.get("status")

                                if (oldStatus != null && oldStatus != relay.status) {
                                    hasChanges = true
                                    StatusUpdateManager.emitRelayStatusUpdateSync(
                                        panel.documentName,
                                        doc.id,
                                        relay.status
                                    )
                                    Log.d(TAG, "Relay status changed: ${relay.name} from $oldStatus to ${relay.status}")
                                }

                                relayStatusCache[cacheKey] = mapOf(
                                    "status" to relay.status,
                                    "lastUpdate" to System.currentTimeMillis().toString()
                                )

                                relay
                            } catch (e: Exception) {
                                Log.e(TAG, "Error converting relay", e)
                                null
                            }
                        }

                        panel.relays = updatedRelays

                        // NUEVO: Si hubo cambios, notificar al Flow
                        if (hasChanges) {
                            coroutineScope.launch {
                                // Obtener la lista actual de paneles del cliente
                                val flowKey = "panels_$clientDocName"
                                val sharedFlow = panelsFlows[flowKey]

                                if (sharedFlow != null) {
                                    // Emitir la lista completa de paneles actualizada
                                    // Esto forzará la recomposición en el UI
                                    val currentPanels = getCurrentPanelsForClient(clientDocName)
                                    sharedFlow.tryEmit(currentPanels)
                                }
                            }
                        }
                    }
                }

            activeListeners[relayListenerId] = registration

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up relay listener", e)
            panel.relays = emptyList()
        }
    }

    private suspend fun updateESP32StatusAsync(panel: Panel) {
        if (panel.esp32_id.isEmpty()) {
            panel.esp32Status = ESP32Device.STATUS_OFFLINE
            return
        }

        val cacheKey = "${panel.clientName}_${panel.documentName}"

        try {
            val esp32Doc = firestore
                .collection("hdd-monitor/esp32/registered")
                .document(panel.esp32_id)
                .get(Source.SERVER)
                .await()

            val newStatus = if (esp32Doc.exists()) {
                esp32Doc.getString("status") ?: ESP32Device.STATUS_OFFLINE
            } else {
                ESP32Device.STATUS_OFFLINE
            }

            if (panel.esp32Status != newStatus) {
                panel.esp32Status = newStatus
                esp32StatusCache[cacheKey] = newStatus

                StatusUpdateManager.emitEsp32StatusUpdateSync(panel.documentName, newStatus)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating ESP32 status", e)
            panel.esp32Status = ESP32Device.STATUS_OFFLINE
            esp32StatusCache[cacheKey] = ESP32Device.STATUS_OFFLINE
        }
    }

    private fun setupClientPanels(
        clientDocName: String,
        currentPanels: MutableList<Panel>,
        onUpdate: (List<Panel>) -> Unit
    ): ListenerRegistration {

        return firestore.collection("$BASE_PATH/$clientDocName/panels")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error getting panels for client $clientDocName", error)
                    onUpdate(currentPanels.toList())
                    return@addSnapshotListener
                }

                if (snapshot == null || snapshot.isEmpty) {
                    synchronized(currentPanels) {
                        currentPanels.clear()
                    }
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    val clientDisplayName = getClientDisplayName(clientDocName)

                    val panels = snapshot.documents.mapNotNull { doc ->
                        try {
                            doc.toObject(Panel::class.java)?.copy(
                                documentName = doc.id,
                                clientName = clientDocName,
                                clientDisplayName = clientDisplayName,
                                lastUpdate = doc.getLong("lastUpdate") ?: System.currentTimeMillis()
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting panel", e)
                            null
                        }
                    }

                    synchronized(currentPanels) {
                        currentPanels.clear()
                        currentPanels.addAll(panels)
                    }

                    onUpdate(currentPanels.toList())

                    panels.forEach { panel ->
                        launch {
                            setupRelayListenerAsync(clientDocName, panel)
                            updateESP32StatusAsync(panel)
                        }
                    }
                }
            }
    }

    private fun setupAllClientsPanels(
        currentPanels: MutableList<Panel>,
        onUpdate: (List<Panel>) -> Unit
    ): ListenerRegistration {
        return firestore.collection(BASE_PATH)
            .addSnapshotListener { clientsSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error getting clients", error)
                    onUpdate(currentPanels.toList())
                    return@addSnapshotListener
                }

                if (clientsSnapshot == null || clientsSnapshot.isEmpty) {
                    synchronized(currentPanels) {
                        currentPanels.clear()
                    }
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    val allPanels = Collections.synchronizedList(mutableListOf<Panel>())

                    clientsSnapshot.documents.forEach { clientDoc ->
                        val clientId = clientDoc.id
                        val clientDisplayName = clientDoc.getString("name") ?: clientId

                        val clientListenerId = "client_panels_$clientId"

                        if (!activeListeners.containsKey(clientListenerId)) {
                            val clientListener = firestore.collection("$BASE_PATH/$clientId/panels")
                                .addSnapshotListener panelsListener@{ panelsSnapshot, panelsError ->
                                    if (panelsError != null) {
                                        Log.e(TAG, "Error listening to panels for client $clientId", panelsError)
                                        return@panelsListener
                                    }

                                    panelsSnapshot?.let { snapshot ->
                                        coroutineScope.launch {
                                            synchronized(allPanels) {
                                                allPanels.removeAll { it.clientName == clientId }
                                            }

                                            val clientPanels = snapshot.documents.mapNotNull { doc ->
                                                try {
                                                    doc.toObject(Panel::class.java)?.copy(
                                                        documentName = doc.id,
                                                        clientName = clientId,
                                                        clientDisplayName = clientDisplayName,
                                                        lastUpdate = doc.getLong("lastUpdate") ?: System.currentTimeMillis()
                                                    )
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error converting panel", e)
                                                    null
                                                }
                                            }

                                            synchronized(allPanels) {
                                                allPanels.addAll(clientPanels)
                                            }

                                            synchronized(currentPanels) {
                                                currentPanels.clear()
                                                currentPanels.addAll(allPanels)
                                            }

                                            onUpdate(currentPanels.toList())

                                            clientPanels.forEach { panel ->
                                                launch {
                                                    setupRelayListenerAsync(clientId, panel)
                                                    updateESP32StatusAsync(panel)
                                                }
                                            }
                                        }
                                    }
                                }

                            activeListeners[clientListenerId] = clientListener
                        }
                    }
                }
            }
    }

    private fun notifyPanelUpdate(clientDocName: String?, panels: List<Panel>) {
        val flowKey = "panels_${clientDocName ?: "all"}"
        panelsFlows[flowKey]?.tryEmit(panels.toList())
    }

    private suspend fun getClientDisplayName(clientDocName: String): String {
        return try {
            val clientDoc = firestore.document("$BASE_PATH/$clientDocName")
                .get(Source.SERVER)
                .await()

            clientDoc.getString("name") ?: clientDocName
        } catch (e: Exception) {
            Log.e(TAG, "Error getting client display name", e)
            clientDocName
        }
    }

    private fun processPanelChanges(
        changes: List<DocumentChange>,
        clientId: String,
        panelsList: MutableList<Panel>
    ) {
        changes.forEach { change ->
            when (change.type) {
                DocumentChange.Type.REMOVED -> {
                    val docId = change.document.id
                    Log.d(TAG, "Panel removed detected: $docId")

                    // Remover listeners de relays
                    val relayListenerId = "relays_${clientId}_$docId"
                    activeListeners[relayListenerId]?.remove()
                    activeListeners.remove(relayListenerId)

                    // Remover de la caché
                    synchronized(panelsList) {
                        val index = panelsList.indexOfFirst {
                            it.documentName == docId && it.clientName == clientId
                        }
                        if (index >= 0) {
                            panelsList.removeAt(index)
                        }
                    }
                }

                else -> { /* ADDED y MODIFIED se manejan después */
                }
            }
        }
    }

    private fun setupRelayListener(clientDocName: String, panel: Panel): ListenerRegistration {
        val relayListenerId = "relays_${clientDocName}_${panel.documentName}"

        if (activeListeners.containsKey(relayListenerId)) {
            Log.d(TAG, "Relay listener already exists for $relayListenerId")
            return activeListeners[relayListenerId]!!
        }

        Log.d(TAG, "Setting up relay listener for panel ${panel.documentName}")

        val registration = firestore
            .collection("$BASE_PATH/$clientDocName/panels/${panel.documentName}/relays")
            .addSnapshotListener { relaysSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error getting relays for panel ${panel.documentName}", error)
                    return@addSnapshotListener
                }

                if (relaysSnapshot == null || relaysSnapshot.isEmpty) {
                    panel.relays = emptyList()
                    return@addSnapshotListener
                }

                relaysSnapshot.let { snapshot ->
                    val updatedRelays = snapshot.documents.mapNotNull { doc ->
                        try {
                            val relayData = doc.data ?: emptyMap()
                            val relay = Relay.fromMap(relayData.plus(mapOf(
                                "name" to doc.id,
                                "customName" to (relayData["customName"] ?: doc.id),
                                "isControllable" to (relayData["isControllable"] ?: true),
                                "isActive" to (relayData["isActive"] ?: true),
                                "commandSource" to (relayData["commandSource"] ?: "esp32")
                            )))

                            val cacheKey = "${clientDocName}_${panel.documentName}_${doc.id}"
                            val oldStatus = relayStatusCache[cacheKey]?.get("status")
                            val oldCommandSource = relayStatusCache[cacheKey]?.get("commandSource")
                            val newCommandSource = relayData["commandSource"] as? String ?: "esp32"

                            if (oldStatus != null && oldStatus != relay.status) {
                                if (newCommandSource != "app" || oldCommandSource != "app") {
                                    StatusUpdateManager.emitRelayStatusUpdateSync(
                                        panel.documentName,
                                        doc.id,
                                        relay.status
                                    )
                                }

                                Log.d(TAG, "Relay status changed: ${relay.name} from $oldStatus to ${relay.status} (source: $newCommandSource)")
                            }

                            relayStatusCache[cacheKey] = mapOf(
                                "status" to relay.status,
                                "lastUpdate" to System.currentTimeMillis().toString(),
                                "commandSource" to newCommandSource
                            )

                            relay
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting relay", e)
                            null
                        }
                    }

                    panel.relays = updatedRelays
                }
            }

        activeListeners[relayListenerId] = registration
        return registration
    }

    private suspend fun updateESP32StatusInBackground(panel: Panel) {
        if (panel.esp32_id.isEmpty()) {
            panel.esp32Status = ESP32Device.STATUS_OFFLINE
            return
        }

        val cacheKey = "${panel.clientName}_${panel.documentName}"

        try {
            // Usar withRetry para obtener estado ESP32 con reintentos
            withRetry {
                // Obtener estado ESP32 desde el servidor
                val esp32Doc = firestore
                    .collection("hdd-monitor/esp32/registered")
                    .document(panel.esp32_id)
                    .get(Source.SERVER) // Forzar consulta al servidor
                    .await()

                if (esp32Doc.exists()) {
                    val newStatus = esp32Doc.getString("status") ?: ESP32Device.STATUS_OFFLINE

                    // Detectar cambio de estado
                    if (panel.esp32Status != newStatus) {
                        esp32StatusCache[cacheKey] = newStatus
                        panel.esp32Status = newStatus

                        // Notificar cambio
                        try {
                            StatusUpdateManager.emitEsp32StatusUpdateSync(panel.documentName, newStatus)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error emitting ESP32 status update", e)
                        }

                        Log.d(TAG, "ESP32 ${panel.esp32_id} status updated to $newStatus")
                    }
                } else {
                    if (panel.esp32Status != ESP32Device.STATUS_OFFLINE) {
                        panel.esp32Status = ESP32Device.STATUS_OFFLINE
                        esp32StatusCache[cacheKey] = ESP32Device.STATUS_OFFLINE

                        // Notificar cambio a OFFLINE
                        try {
                            StatusUpdateManager.emitEsp32StatusUpdateSync(
                                panel.documentName,
                                ESP32Device.STATUS_OFFLINE
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error emitting ESP32 status update", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating ESP32 status after retries", e)

            // En caso de error, consultar caché local
            try {
                val esp32Doc = firestore
                    .collection("hdd-monitor/esp32/registered")
                    .document(panel.esp32_id)
                    .get(Source.CACHE)
                    .await()

                if (esp32Doc.exists()) {
                    val cachedStatus = esp32Doc.getString("status") ?: ESP32Device.STATUS_OFFLINE
                    panel.esp32Status = cachedStatus
                    esp32StatusCache[cacheKey] = cachedStatus
                } else {
                    // Si no hay dato en caché, marcar como OFFLINE
                    panel.esp32Status = ESP32Device.STATUS_OFFLINE
                    esp32StatusCache[cacheKey] = ESP32Device.STATUS_OFFLINE
                }
            } catch (cacheEx: Exception) {
                Log.e(TAG, "Error accessing ESP32 cache", cacheEx)
                // Último recurso: marcar como OFFLINE
                panel.esp32Status = ESP32Device.STATUS_OFFLINE
                esp32StatusCache[cacheKey] = ESP32Device.STATUS_OFFLINE
            }
        }
    }

    fun observePanelUpdates(clientDocName: String, panelDocName: String): Flow<Panel?> =
        callbackFlow {
            Log.d(TAG, "Starting panel updates observation for $clientDocName/$panelDocName")

            val panelListenerId = "panel_observe_${clientDocName}_$panelDocName"

            trySend(null)

            activeListeners[panelListenerId]?.remove()

            val registration = firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error observing panel updates", error)
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        try {
                            coroutineScope.launch {
                                val clientDisplayName = getClientDisplayName(clientDocName)

                                val panel = snapshot.toObject(Panel::class.java)?.copy(
                                    documentName = snapshot.id,
                                    clientName = clientDocName,
                                    clientDisplayName = clientDisplayName,
                                    lastUpdate = snapshot.getLong("lastUpdate")
                                        ?: System.currentTimeMillis()
                                )

                                panel?.let {
                                    setupRelayListener(clientDocName, it)

                                    launch {
                                        try {
                                            updateESP32StatusInBackground(it)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error updating ESP32 status", e)
                                        }
                                    }
                                }

                                trySend(panel)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting panel", e)
                            trySend(null)
                        }
                    } else {
                        trySend(null)
                    }
                }

            activeListeners[panelListenerId] = registration

            awaitClose {
                Log.d(TAG, "Closing panel updates observation")
                registration.remove()
                activeListeners.remove(panelListenerId)
            }
        }.flowOn(Dispatchers.IO)

    private fun cleanupExistingListeners(prefix: String) {
        Log.d(TAG, "Cleaning up existing listeners with prefix: $prefix")

        val listenersToRemove = activeListeners.entries
            .filter { it.key.startsWith(prefix) }
            .map { it.key to it.value }

        listenersToRemove.forEach { (key, listener) ->
            try {
                listener.remove()
                activeListeners.remove(key)
                Log.d(TAG, "Removed listener: $key")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener: $key", e)
            }
        }

        if (listenersToRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${listenersToRemove.size} existing listeners")
        }
    }

    suspend fun createNewPanel(
        clientDocName: String,
        panel: Panel,
        esp32Id: String? = null
    ): Result<String> = runCatching {
        if (!panel.isValid()) {
            throw IllegalArgumentException("Panel data is invalid")
        }

        withRetry {
            if (esp32Id != null) {
                val esp32Doc = firestore
                    .collection("hdd-monitor/esp32/registered")
                    .document(esp32Id)
                    .get(Source.SERVER)
                    .await()

                if (esp32Doc.exists()) {
                    val existingPanelId = esp32Doc.getString("panel_id")
                    if (!existingPanelId.isNullOrEmpty()) {
                        throw IllegalStateException("ESP32 ya está asignado a otro panel")
                    }
                }
            }

            val panelDocName = IdManager.generatePanelDocumentName(panel.name, clientDocName)
            val clientDisplayName = getClientDisplayName(clientDocName)

            Log.d(TAG, "Creating new panel: $panelDocName")

            firestore.runTransaction { transaction ->
                val panelRef = firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                val esp32Ref = esp32Id?.let {
                    firestore.document("hdd-monitor/esp32/registered/$it")
                }

                val updatedPanel = panel.copy(
                    documentName = panelDocName,
                    clientName = clientDocName,
                    clientDisplayName = clientDisplayName,
                    esp32_id = esp32Id ?: "",
                    lastUpdate = System.currentTimeMillis(),
                    relays = emptyList()
                )

                transaction.set(panelRef, updatedPanel.toMap())

                esp32Ref?.let {
                    transaction.update(
                        it, mapOf(
                            "client_id" to clientDocName,
                            "panel_id" to panelDocName,
                            "lastUpdate" to com.google.firebase.Timestamp.now()
                        )
                    )
                }
            }.await()

            Log.d(TAG, "Panel created successfully with ID: $panelDocName")
            panelDocName
        }
    }.onFailure { e ->
        Log.e(TAG, "Error creating panel", e)
    }

    suspend fun updatePanel(
        clientDocName: String,
        panel: Panel,
        newEsp32Id: String? = null
    ): Result<Unit> = runCatching {
        if (!panel.isValid()) {
            throw IllegalArgumentException("Panel data is invalid")
        }

        withRetry {
            val panelRef = firestore
                .document("$BASE_PATH/$clientDocName/panels/${panel.documentName}")

            // Manejar cambio de ESP32
            if (newEsp32Id != panel.esp32_id) {
                // Desasignar ESP32 anterior
                if (panel.esp32_id.isNotEmpty()) {
                    esp32Repository.unassignFromPanel(panel.esp32_id)
                        .onFailure { e ->
                            Log.e(TAG, "Error unassigning previous ESP32", e)
                        }
                }

                // Asignar nuevo ESP32
                newEsp32Id?.let {
                    esp32Repository.assignToPanelAndClient(it, clientDocName, panel.documentName)
                        .onFailure { e ->
                            Log.e(TAG, "Error assigning new ESP32", e)
                        }
                }
            }

            // Actualizar panel
            val updatedPanel = panel.copy(
                esp32_id = newEsp32Id ?: panel.esp32_id,
                lastUpdate = System.currentTimeMillis()
            )
            panelRef.set(updatedPanel.toMap()).await()
        }
    }

    suspend fun updateRelayStatus(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        relayStatus: String
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Updating relay $relayName to $relayStatus")

        withRetry {
            val dateTime = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))

            val updateData = mapOf(
                "status" to relayStatus,
                "date_time" to dateTime,
                "lastUpdate" to System.currentTimeMillis()
            )

            firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName/relays/$relayName")
                .set(updateData)
                .await()

            // Actualizar caché
            val cacheKey = "${clientDocName}_${panelDocName}_$relayName"
            relayStatusCache[cacheKey] = mapOf(
                "status" to relayStatus,
                "lastUpdate" to System.currentTimeMillis().toString()
            )

            // Notificar cambio
            try {
                StatusUpdateManager.emitRelayStatusUpdateSync(panelDocName, relayName, relayStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Error emitting relay status update", e)
            }

            Log.d(TAG, "Relay status updated successfully")
        }
    }

    suspend fun deletePanel(
        clientDocName: String,
        panelDocName: String
    ): Result<Unit> = runCatching {
        withRetry {
            // Obtener panel
            val panelDoc = firestore
                .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                .get()
                .await()

            // Remover listeners
            val panelListenerId = "panel_observe_${clientDocName}_$panelDocName"
            val relayListenerId = "relays_${clientDocName}_$panelDocName"

            activeListeners[panelListenerId]?.remove()
            activeListeners.remove(panelListenerId)

            activeListeners[relayListenerId]?.remove()
            activeListeners.remove(relayListenerId)

            // Eliminar ESP32 si existe
            val esp32Id = panelDoc.getString("esp32_id")
            if (!esp32Id.isNullOrEmpty()) {
                esp32Repository.deleteESP32(esp32Id)
                    .onFailure { e ->
                        Log.e(TAG, "Error deleting ESP32", e)
                    }
            }

            // Eliminar relays
            val relaysSnapshot = firestore
                .collection("$BASE_PATH/$clientDocName/panels/$panelDocName/relays")
                .get()
                .await()

            val batch = firestore.batch()
            relaysSnapshot.documents.forEach { doc ->
                batch.delete(doc.reference)

                // Limpiar caché
                val cacheKey = "${clientDocName}_${panelDocName}_${doc.id}"
                relayStatusCache.remove(cacheKey)
            }

            // Eliminar panel
            batch.delete(panelDoc.reference)
            batch.commit().await()

            // Limpiar caché ESP32
            val cacheKey = "${clientDocName}_${panelDocName}"
            esp32StatusCache.remove(cacheKey)

            Log.d(TAG, "Panel, relays and ESP32 deleted successfully")
        }
    }

    suspend fun verifyPanelExists(clientDocName: String, panelDocName: String): Boolean {
        return try {
            withRetry {
                val panelDoc = firestore
                    .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                    .get(Source.SERVER) // Forzar consulta al servidor
                    .await()
                panelDoc.exists()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying panel existence", e)
            // Si falla la consulta al servidor, intentar con caché
            try {
                val panelDoc = firestore
                    .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                    .get(Source.CACHE)
                    .await()
                panelDoc.exists()
            } catch (e2: Exception) {
                Log.e(TAG, "Error accessing cache for panel verification", e2)
                false
            }
        }
    }

    suspend fun sendRelayCommand(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        newState: String,
        commandSource: String = "app"
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Sending relay command: $relayName -> $newState")

        withRetry {
            val timestamp = System.currentTimeMillis()
            val dateTime = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))

            // Actualizar estado en Firestore primero
            val updateData = mapOf(
                "status" to newState,
                "date_time" to dateTime,
                "lastUpdate" to FieldValue.serverTimestamp(),
                "lastCommandSent" to FieldValue.serverTimestamp(),
                "commandSource" to commandSource
            )

            firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName/relays/$relayName")
                .update(updateData)
                .await()

            // Actualizar caché local
            val cacheKey = "${clientDocName}_${panelDocName}_$relayName"
            relayStatusCache[cacheKey] = mapOf(
                "status" to newState,
                "lastUpdate" to timestamp.toString(),
                "commandSource" to commandSource
            )

            // Emitir actualización inmediatamente para UI responsiva
            StatusUpdateManager.emitRelayStatusUpdateSync(panelDocName, relayName, newState)

            Log.d(TAG, "Relay command sent successfully: $relayName -> $newState")
        }
    }

    suspend fun updateRelayName(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        customName: String
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Updating relay name: $relayName -> $customName")

        withRetry {
            val updateData = mapOf(
                "customName" to customName.trim(),
                "lastUpdate" to FieldValue.serverTimestamp()
            )

            firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName/relays/$relayName")
                .update(updateData)
                .await()

            Log.d(TAG, "Relay name updated successfully")
        }
    }

    suspend fun updateRelayControlConfig(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        isControllable: Boolean,
        isActive: Boolean
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Updating relay control config: $relayName")

        withRetry {
            val updateData = mapOf(
                "isControllable" to isControllable,
                "isActive" to isActive,
                "lastUpdate" to FieldValue.serverTimestamp()
            )

            firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName/relays/$relayName")
                .update(updateData)
                .await()

            Log.d(TAG, "Relay control config updated successfully")
        }
    }

    suspend fun getRelayCommandHistory(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        limit: Int = 20
    ): Result<List<RelayCommand>> = runCatching {
        Log.d(TAG, "Getting relay command history for $relayName")

        withRetry {
            val snapshot = firestore
                .collection("$BASE_PATH/$clientDocName/panels/$panelDocName/relays/$relayName/commands")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                try {
                    RelayCommand.fromMap(doc.data?.plus("id" to doc.id) ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting relay command", e)
                    null
                }
            }
        }
    }

    suspend fun getPanelForControl(
        clientDocName: String,
        panelDocName: String
    ): Result<Panel?> = runCatching {
        Log.d(TAG, "Getting panel for control: $panelDocName")

        withRetry {
            val panelDoc = firestore
                .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                .get(Source.SERVER)
                .await()

            if (!panelDoc.exists()) {
                null
            } else {
                val panel = panelDoc.toObject(Panel::class.java)?.copy(
                    documentName = panelDoc.id,
                    clientName = clientDocName
                )

                // Cargar relays con información extendida
                panel?.let { p ->
                    val relaysSnapshot = firestore
                        .collection("$BASE_PATH/$clientDocName/panels/$panelDocName/relays")
                        .get(Source.SERVER)
                        .await()

                    val relays = relaysSnapshot.documents.mapNotNull { relayDoc ->
                        try {
                            val relayData = relayDoc.data ?: emptyMap()
                            Relay.fromMap(relayData.plus(mapOf(
                                "name" to relayDoc.id,
                                "customName" to (relayData["customName"] ?: relayDoc.id),
                                "isControllable" to (relayData["isControllable"] ?: true),
                                "isActive" to (relayData["isActive"] ?: true),
                                "commandSource" to (relayData["commandSource"] ?: "esp32")
                            )))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting relay for control", e)
                            null
                        }
                    }

                    p.copy(relays = relays)
                }
            }
        }
    }

    data class RelayCommand(
        val id: String = "",
        val command: String = "",
        val previousState: String = "",
        val newState: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        val source: String = "app",
        val success: Boolean = true
    ) {
        companion object {
            fun fromMap(map: Map<String, Any?>): RelayCommand {
                return RelayCommand(
                    id = map["id"] as? String ?: "",
                    command = map["command"] as? String ?: "",
                    previousState = map["previousState"] as? String ?: "",
                    newState = map["newState"] as? String ?: "",
                    timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    source = map["source"] as? String ?: "app",
                    success = map["success"] as? Boolean ?: true
                )
            }
        }
    }
}