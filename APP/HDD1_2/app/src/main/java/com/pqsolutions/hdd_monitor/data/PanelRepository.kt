package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
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

    /**
     * Ejecuta una operación con reintentos en caso de fallos
     */
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

    /**
     * Limpia todos los listeners activos
     */
    fun clearListeners() {
        Log.d(TAG, "Clearing all panel and relay listeners: ${activeListeners.size} listeners")

        // Copia segura para evitar ConcurrentModificationException
        val listeners = ArrayList(activeListeners.values)

        // Limpiar las colecciones de inmediato
        activeListeners.clear()
        relayStatusCache.clear()
        esp32StatusCache.clear()

        // Remueve cada listener en un contexto seguro
        listeners.forEach { listener ->
            try {
                listener.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener", e)
            }
        }

        Log.d(TAG, "All listeners cleared successfully")
    }

    /**
     * Fuerza una recarga desde el servidor limpiando cachés
     */
    suspend fun forceRefreshFromServer() {
        try {
            Log.d(TAG, "Forzando refresco desde servidor")

            // Limpiar cachés
            relayStatusCache.clear()
            esp32StatusCache.clear()

            // Limpiar listeners
            clearListeners()

            // Verificar conexión
            val testQuery = firestore.collection(BASE_PATH)
                .limit(1)
                .get(Source.SERVER)
                .await()

            Log.d(TAG, "Verificación de conexión exitosa: ${testQuery.size()} documentos")
        } catch (e: Exception) {
            Log.e(TAG, "Error al forzar refresco desde servidor", e)
            throw e
        }
    }

    /**
     * Obtiene paneles para un cliente específico o para todos los clientes (admin)
     */
    fun getPanels(clientDocName: String?): Flow<List<Panel>> = callbackFlow {
        Log.d(TAG, "getPanels called with clientDocName: $clientDocName")

        // Lista segura para threads
        val currentPanels = Collections.synchronizedList(mutableListOf<Panel>())
        val listenerId = "panels_${clientDocName ?: "all"}_${System.currentTimeMillis()}"

        // Emitir lista vacía inmediatamente para indicar carga
        trySend(emptyList())

        // Agregar un timeout de seguridad
        launch {
            // Si después de 10 segundos no hemos enviado ningún panel, enviar lista vacía con mensaje
            delay(10000)
            if (currentPanels.isEmpty()) {
                Log.w(TAG, "No se recibieron paneles después de 10 segundos, enviando lista vacía")
                trySend(emptyList())
            }
        }

        // Limpiar listeners existentes con el mismo prefijo
        cleanupExistingListeners("panels_")

        val registration = try {
            if (clientDocName != null) {
                // Cliente específico
                setupClientPanelsListener(clientDocName, currentPanels) { panels ->
                    trySend(panels.toList())
                }
            } else {
                // Admin (todos los clientes)
                setupAllClientsPanelsListener(currentPanels) { panels ->
                    trySend(panels.toList())
                }
            }
        } catch (e: Exception) {
            // En caso de error al configurar listeners, enviar lista vacía
            Log.e(TAG, "Error setting up panels listener", e)
            trySend(emptyList())
            // Retornar un listener vacío
            object : ListenerRegistration {
                override fun remove() {}
            }
        }

        // Registrar listener principal
        activeListeners[listenerId] = registration

        awaitClose {
            Log.d(TAG, "Closing panel flow, removing listeners")
            try {
                cleanupExistingListeners("panels_")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing listeners", e)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Configura listener para paneles de un cliente específico
     */
    private fun setupClientPanelsListener(
        clientDocName: String,
        currentPanels: MutableList<Panel>,
        onUpdateCallback: (List<Panel>) -> Unit
    ): ListenerRegistration {
        val listenerId = "client_panels_$clientDocName"

        Log.d(TAG, "Setting up client panels listener for $clientDocName")

        // Eliminar listener existente
        activeListeners[listenerId]?.remove()

        val registration = firestore.collection("$BASE_PATH/$clientDocName/panels")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error getting panels for client $clientDocName", error)
                    // IMPORTANTE: Enviar los paneles actuales para no bloquear la UI
                    onUpdateCallback(currentPanels.toList())
                    return@addSnapshotListener
                }

                if (snapshot == null || snapshot.isEmpty) {
                    Log.d(TAG, "No panels found for client $clientDocName")
                    synchronized(currentPanels) {
                        currentPanels.clear()
                    }
                    onUpdateCallback(emptyList())
                    return@addSnapshotListener
                }

                snapshot.let { panelsSnapshot ->
                    processPanelChanges(
                        panelsSnapshot.documentChanges,
                        clientDocName,
                        currentPanels
                    )

                    // Actualizar paneles desde snapshot
                    val updatedPanels = panelsSnapshot.documents.mapNotNull { doc ->
                        try {
                            val panel = doc.toObject(Panel::class.java)?.copy(
                                documentName = doc.id,
                                clientName = clientDocName,
                                lastUpdate = doc.getLong("lastUpdate") ?: System.currentTimeMillis()
                            )

                            // Cargar inmediatamente la colección de relays
                            panel?.let { p ->
                                try {
                                    firestore
                                        .collection("$BASE_PATH/$clientDocName/panels/${p.documentName}/relays")
                                        .get(Source.SERVER) // Usar SERVER para forzar datos frescos
                                        .addOnSuccessListener { relaysSnapshot ->
                                            val loadedRelays =
                                                relaysSnapshot.documents.mapNotNull { relayDoc ->
                                                    try {
                                                        Relay.fromMap(
                                                            relayDoc.data?.plus(mapOf("name" to relayDoc.id))
                                                                ?: emptyMap()
                                                        )
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Error converting relay", e)
                                                        null
                                                    }
                                                }

                                            if (loadedRelays.isNotEmpty()) {
                                                p.relays = loadedRelays
                                                Log.d(
                                                    TAG,
                                                    "Cargados ${loadedRelays.size} relays iniciales para panel ${p.documentName}"
                                                )

                                                // Notificar nuevamente para asegurar que los relays se muestren
                                                val index =
                                                    currentPanels.indexOfFirst { it.documentName == p.documentName }
                                                if (index >= 0) {
                                                    currentPanels[index].relays = loadedRelays
                                                    onUpdateCallback(currentPanels.toList())
                                                }
                                            } else {
                                                Log.d(
                                                    TAG,
                                                    "No se encontraron relays para el panel ${p.documentName}"
                                                )
                                            }
                                        }
                                        .addOnFailureListener { e ->
                                            Log.e(TAG, "Error cargando relays iniciales", e)
                                            // Si falla la carga desde SERVER, intentar con CACHE
                                            firestore
                                                .collection("$BASE_PATH/$clientDocName/panels/${p.documentName}/relays")
                                                .get(Source.CACHE)
                                                .addOnSuccessListener { relaysSnapshot ->
                                                    // Mismo procesamiento que arriba
                                                    val loadedRelays = relaysSnapshot.documents.mapNotNull { relayDoc ->
                                                        try {
                                                            Relay.fromMap(
                                                                relayDoc.data?.plus(mapOf("name" to relayDoc.id))
                                                                    ?: emptyMap()
                                                            )
                                                        } catch (e: Exception) {
                                                            null
                                                        }
                                                    }

                                                    if (loadedRelays.isNotEmpty()) {
                                                        p.relays = loadedRelays
                                                        val index = currentPanels.indexOfFirst {
                                                            it.documentName == p.documentName
                                                        }
                                                        if (index >= 0) {
                                                            currentPanels[index].relays = loadedRelays
                                                            onUpdateCallback(currentPanels.toList())
                                                        }
                                                    }
                                                }
                                        }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error cargando relays iniciales", e)
                                }
                            }

                            // Aplicar estado ESP32 desde caché si existe
                            val cacheKey = "${clientDocName}_${doc.id}"
                            if (esp32StatusCache.containsKey(cacheKey)) {
                                panel?.esp32Status =
                                    esp32StatusCache[cacheKey] ?: ESP32Device.STATUS_OFFLINE
                            }

                            panel
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting panel", e)
                            null
                        }
                    }

                    synchronized(currentPanels) {
                        currentPanels.clear()
                        currentPanels.addAll(updatedPanels)
                    }

                    // Notificar cambios
                    onUpdateCallback(currentPanels)

                    // Para cada panel, configurar listener de relays y actualizar ESP32
                    currentPanels.forEach { panel ->
                        setupRelayListener(clientDocName, panel)

                        coroutineScope.launch {
                            try {
                                updateESP32StatusInBackground(panel)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating ESP32 status", e)
                            }
                        }
                    }
                }
            }

        activeListeners[listenerId] = registration
        return registration
    }

    /**
     * Configura listeners para paneles de todos los clientes (admin)
     */
    private fun setupAllClientsPanelsListener(
        currentPanels: MutableList<Panel>,
        onUpdateCallback: (List<Panel>) -> Unit
    ): ListenerRegistration {
        val listenerId = "all_clients_panels"

        Log.d(TAG, "Setting up all clients panels listener")

        // Asegúrate de emitir una lista vacía inicialmente para que la UI sepa que estamos cargando
        onUpdateCallback(emptyList())

        // Eliminar listener existente
        activeListeners[listenerId]?.remove()

        val registration = firestore.collection(BASE_PATH)
            .addSnapshotListener { clientsSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error getting clients", error)
                    // IMPORTANTE: Emitir lista actual en caso de error para evitar bloqueos
                    onUpdateCallback(currentPanels.toList())
                    return@addSnapshotListener
                }

                if (clientsSnapshot == null || clientsSnapshot.isEmpty) {
                    Log.d(TAG, "No clients found")
                    synchronized(currentPanels) {
                        currentPanels.clear()
                    }
                    onUpdateCallback(emptyList())
                    return@addSnapshotListener
                }

                clientsSnapshot.let { clients ->
                    // Control de clientes procesados
                    val totalClients = clients.size()
                    val pendingClients = AtomicInteger(totalClients)
                    val allPanels = Collections.synchronizedList(mutableListOf<Panel>())

                    // SOLUCIÓN: Si no hay clientes que procesar, enviar lista vacía para evitar esperas infinitas
                    if (totalClients == 0) {
                        synchronized(currentPanels) {
                            currentPanels.clear()
                        }
                        onUpdateCallback(emptyList())
                        return@addSnapshotListener
                    }

                    clients.documents.forEach { clientDoc ->
                        val clientId = clientDoc.id
                        val clientListenerId = "client_panels_$clientId"

                        // Eliminar listener existente
                        activeListeners[clientListenerId]?.remove()

                        val clientListener = firestore.collection("$BASE_PATH/$clientId/panels")
                            .addSnapshotListener { panelsSnapshot, panelsError ->
                                if (panelsError != null) {
                                    Log.e(
                                        TAG,
                                        "Error getting panels for client $clientId",
                                        panelsError
                                    )
                                    if (pendingClients.decrementAndGet() == 0) {
                                        synchronized(currentPanels) {
                                            currentPanels.clear()
                                            currentPanels.addAll(allPanels)
                                        }
                                        onUpdateCallback(currentPanels)
                                    }
                                    return@addSnapshotListener
                                }

                                if (panelsSnapshot == null || panelsSnapshot.isEmpty) {
                                    if (pendingClients.decrementAndGet() == 0) {
                                        synchronized(currentPanels) {
                                            currentPanels.clear()
                                            currentPanels.addAll(allPanels)
                                        }
                                        onUpdateCallback(currentPanels)
                                    }
                                    return@addSnapshotListener
                                }

                                panelsSnapshot.let { panels ->
                                    // Procesar solo para este cliente
                                    val clientPanels = panels.documents.mapNotNull { doc ->
                                        try {
                                            val panel = doc.toObject(Panel::class.java)?.copy(
                                                documentName = doc.id,
                                                clientName = clientId,
                                                lastUpdate = doc.getLong("lastUpdate")
                                                    ?: System.currentTimeMillis()
                                            )

                                            // Aplicar estado ESP32 desde caché
                                            val cacheKey = "${clientId}_${doc.id}"
                                            if (esp32StatusCache.containsKey(cacheKey)) {
                                                panel?.esp32Status = esp32StatusCache[cacheKey]
                                                    ?: ESP32Device.STATUS_OFFLINE
                                            }

                                            panel
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error converting panel", e)
                                            null
                                        }
                                    }

                                    // Actualizar lista global
                                    synchronized(allPanels) {
                                        // Eliminar paneles antiguos de este cliente
                                        allPanels.removeAll { it.clientName == clientId }
                                        allPanels.addAll(clientPanels)
                                    }

                                    // Configurar listeners de relay y actualizar ESP32
                                    clientPanels.forEach { panel ->
                                        setupRelayListener(clientId, panel)

                                        coroutineScope.launch {
                                            try {
                                                updateESP32StatusInBackground(panel)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error updating ESP32 status", e)
                                            }
                                        }
                                    }

                                    // Si es el último cliente, actualizar la lista principal
                                    val remaining = pendingClients.decrementAndGet()
                                    Log.d(
                                        TAG,
                                        "Processed client $clientId, $remaining clients remaining"
                                    )

                                    if (remaining == 0) {
                                        Log.d(
                                            TAG,
                                            "All clients processed, updating main panel list with ${allPanels.size} panels"
                                        )
                                        synchronized(currentPanels) {
                                            currentPanels.clear()
                                            currentPanels.addAll(allPanels)
                                        }
                                        onUpdateCallback(currentPanels)
                                    }
                                }
                            }

                        activeListeners[clientListenerId] = clientListener
                    }
                }
            }

        activeListeners[listenerId] = registration
        return registration
    }

    /**
     * Procesa cambios en documentos de paneles
     */
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

    /**
     * Configura un listener para los relays de un panel
     */
    private fun setupRelayListener(clientDocName: String, panel: Panel): ListenerRegistration {
        val relayListenerId = "relays_${clientDocName}_${panel.documentName}"

        // Remover listener existente para evitar duplicados
        activeListeners[relayListenerId]?.remove()

        Log.d(TAG, "Setting up relay listener for panel ${panel.documentName}")

        val registration = firestore
            .collection("$BASE_PATH/$clientDocName/panels/${panel.documentName}/relays")
            .addSnapshotListener { relaysSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error getting relays for panel ${panel.documentName}", error)
                    return@addSnapshotListener
                }

                if (relaysSnapshot == null || relaysSnapshot.isEmpty) {
                    // Asegurar que se use la lista de relays por defecto
                    panel.relays = listOf(
                        Relay(Panel.RELAY_ALARM, Panel.STATUS_DISC),
                        Relay(Panel.RELAY_PROBLEM, Panel.STATUS_DISC),
                        Relay(Panel.RELAY_SUPERVISION, Panel.STATUS_DISC)
                    )
                    return@addSnapshotListener
                }

                relaysSnapshot.let { snapshot ->
                    val updatedRelays = snapshot.documents.mapNotNull { doc ->
                        try {
                            val relay =
                                Relay.fromMap(doc.data?.plus(mapOf("name" to doc.id)) ?: emptyMap())

                            // Detectar cambios de estado y notificar
                            val cacheKey = "${clientDocName}_${panel.documentName}_${doc.id}"
                            val oldStatus = relayStatusCache[cacheKey]?.get("status")
                            if (oldStatus != null && oldStatus != relay.status) {
                                // Emitir actualización inmediatamente
                                StatusUpdateManager.emitRelayStatusUpdateSync(
                                    panel.documentName,
                                    doc.id,
                                    relay.status
                                )

                                Log.d(TAG, "Relay status changed: ${relay.name} from $oldStatus to ${relay.status}")
                            }

                            // Actualizar caché
                            relayStatusCache[cacheKey] = mapOf(
                                "status" to relay.status,
                                "lastUpdate" to (System.currentTimeMillis().toString())
                            )

                            relay
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting relay", e)
                            null
                        }
                    }

                    // Si no hay relays, usar los relays por defecto
                    if (updatedRelays.isEmpty()) {
                        panel.relays = listOf(
                            Relay(Panel.RELAY_ALARM, Panel.STATUS_DISC),
                            Relay(Panel.RELAY_PROBLEM, Panel.STATUS_DISC),
                            Relay(Panel.RELAY_SUPERVISION, Panel.STATUS_DISC)
                        )
                    } else {
                        panel.relays = updatedRelays
                    }
                }
            }

        activeListeners[relayListenerId] = registration
        return registration
    }

    /**
     * Actualiza el estado ESP32 de un panel en segundo plano
     */
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

    /**
     * Observa actualizaciones de un panel específico
     */
    fun observePanelUpdates(clientDocName: String, panelDocName: String): Flow<Panel?> =
        callbackFlow {
            Log.d(TAG, "Starting panel updates observation for $clientDocName/$panelDocName")

            val panelListenerId = "panel_observe_${clientDocName}_$panelDocName"

            // Emitir null inicialmente para indicar carga
            trySend(null)

            // Eliminar listener existente
            activeListeners[panelListenerId]?.remove()

            val registration = firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error observing panel updates", error)
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        try {
                            val panel = snapshot.toObject(Panel::class.java)?.copy(
                                documentName = snapshot.id,
                                clientName = clientDocName,
                                lastUpdate = snapshot.getLong("lastUpdate")
                                    ?: System.currentTimeMillis()
                            )

                            panel?.let {
                                // Configurar listener de relays
                                setupRelayListener(clientDocName, it)

                                // Actualizar estado ESP32
                                coroutineScope.launch {
                                    try {
                                        updateESP32StatusInBackground(it)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error updating ESP32 status", e)
                                    }
                                }
                            }

                            trySend(panel)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting panel", e)
                            close(e)
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

    /**
     * Limpia listeners que coinciden con un prefijo
     */
    private fun cleanupExistingListeners(prefix: String) {
        val listenersToRemove = activeListeners.entries
            .filter { it.key.startsWith(prefix) }
            .map { it.key to it.value }

        listenersToRemove.forEach { (key, listener) ->
            try {
                listener.remove()
                activeListeners.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener: $key", e)
            }
        }
    }

    // Métodos CRUD existentes con mejoras

    suspend fun createNewPanel(
        clientDocName: String,
        panel: Panel,
        esp32Id: String? = null
    ): Result<String> = runCatching {
        if (!panel.isValid()) {
            throw IllegalArgumentException("Panel data is invalid")
        }

        // Usar withRetry para operación de crear panel
        withRetry {
            // Verificar si el ESP32 ya está asignado a otro panel
            if (esp32Id != null) {
                val esp32Doc = firestore
                    .collection("hdd-monitor/esp32/registered")
                    .document(esp32Id)
                    .get(Source.SERVER) // Forzar consulta al servidor
                    .await()

                if (esp32Doc.exists()) {
                    val existingPanelId = esp32Doc.getString("panel_id")
                    if (!existingPanelId.isNullOrEmpty()) {
                        throw IllegalStateException("ESP32 ya está asignado a otro panel")
                    }
                }
            }

            val panelDocName = IdManager.generatePanelDocumentName(panel.name, clientDocName)
            Log.d(TAG, "Creating new panel: $panelDocName")

            // Ejecutar todo en una transacción
            firestore.runTransaction { transaction ->
                // Referencias
                val panelRef = firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                val esp32Ref = esp32Id?.let {
                    firestore.document("hdd-monitor/esp32/registered/$it")
                }

                // Crear panel con datos actualizados
                val updatedPanel = panel.copy(
                    documentName = panelDocName,
                    clientName = clientDocName,
                    esp32_id = esp32Id ?: "",
                    lastUpdate = System.currentTimeMillis(),
                    relays = listOf(
                        Relay(Panel.RELAY_ALARM, Panel.STATUS_DISC),
                        Relay(Panel.RELAY_PROBLEM, Panel.STATUS_DISC),
                        Relay(Panel.RELAY_SUPERVISION, Panel.STATUS_DISC)
                    )
                )

                // Crear panel
                transaction.set(panelRef, updatedPanel.toMap())

                // Crear relays
                updatedPanel.relays.forEach { relay ->
                    val relayRef = panelRef.collection("relays").document(relay.name)
                    transaction.set(relayRef, relay.toMap())
                }

                // Si hay ESP32, asignarlo
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
}