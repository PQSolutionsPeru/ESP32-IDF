package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.MetadataChanges
import com.pqsolutions.hdd_monitor.data.util.IdManager
import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
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
import java.util.concurrent.ConcurrentHashMap
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
        private const val SERVER_VALIDATION_INTERVAL = 30000L
        private const val MAX_ACTIVE_LISTENERS = 50
        private const val CLEANUP_INTERVAL = 300000L
        private const val STALE_LISTENER_TIMEOUT = 600000L
    }

    private val activeListeners = ConcurrentHashMap<String, ListenerInfo>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastServerValidation = 0L
    private var lastCleanup = 0L
    private var reconnectionAttempts = 0

    data class ListenerInfo(
        val registration: ListenerRegistration,
        val createdAt: Long,
        val clientId: String,
        val panelId: String,
        val type: String
    )

    init {
        startPeriodicCleanup()
    }

    private fun startPeriodicCleanup() {
        coroutineScope.launch {
            while (true) {
                try {
                    delay(CLEANUP_INTERVAL)
                    performPeriodicCleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error en limpieza periódica", e)
                }
            }
        }
    }

    private fun cleanupExcessiveListeners() {
        val sortedListeners = activeListeners.toList().sortedBy { it.second.createdAt }
        val toRemove = sortedListeners.take(activeListeners.size - (MAX_ACTIVE_LISTENERS / 2))

        toRemove.forEach { (key, _) ->
            removeListener(key)
        }
    }

    private fun addListener(
        key: String,
        registration: ListenerRegistration,
        clientId: String,
        panelId: String,
        type: String
    ): String {
        if (activeListeners.size >= MAX_ACTIVE_LISTENERS) {
            cleanupExcessiveListeners()
        }

        val finalKey = if (activeListeners.containsKey(key)) {
            "${key}_${System.currentTimeMillis()}"
        } else {
            key
        }

        val info = ListenerInfo(
            registration = registration,
            createdAt = System.currentTimeMillis(),
            clientId = clientId,
            panelId = panelId,
            type = type
        )

        activeListeners[finalKey] = info
        return finalKey
    }

    private fun removeListener(key: String) {
        activeListeners[key]?.let { info ->
            try {
                info.registration.remove()
                activeListeners.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Error removiendo listener $key", e)
                activeListeners.remove(key)
            }
        }
    }

    fun clearListeners() {
        Log.d(TAG, "Limpiando ${activeListeners.size} listeners")

        val listenersSnapshot = activeListeners.toMap()
        activeListeners.clear()

        listenersSnapshot.forEach { (key, info) ->
            try {
                info.registration.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Error removiendo listener: $key", e)
            }
        }

        System.gc()
    }

    fun clearListenersForClient(clientId: String) {
        val clientListeners = activeListeners.filter { (_, info) ->
            info.clientId == clientId
        }

        clientListeners.keys.forEach { key ->
            removeListener(key)
        }
    }

    fun clearListenersForPanel(clientId: String, panelId: String) {
        val panelListeners = activeListeners.filter { (_, info) ->
            info.clientId == clientId && info.panelId == panelId
        }

        panelListeners.keys.forEach { key ->
            removeListener(key)
        }
    }

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
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (attempt == maxRetries - 1) throw e
                Log.e(TAG, "Operation failed, retrying (${attempt + 1}/$maxRetries)", e)
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        error("This line should never be reached")
    }

    fun getPanels(clientDocName: String?): Flow<List<Panel>> = callbackFlow {
        Log.d(TAG, "getPanels called with clientDocName: $clientDocName")

        try {
            if (clientDocName != null) {
                setupValidatedClientPanelsFlow(clientDocName) { panels ->
                    validateAndSend(panels, clientDocName) { validatedPanels ->
                        trySend(validatedPanels)
                    }
                }
            } else {
                setupValidatedAdminPanelsFlow { panels ->
                    validateAndSend(panels, null) { validatedPanels ->
                        trySend(validatedPanels)
                    }
                }
            }

            awaitClose {
                Log.d(TAG, "Closing panel flow for clientDocName: $clientDocName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up panels listener", e)
            close(e)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun verifyPanelsInServer(clientDocName: String?): List<Panel> {
        return try {
            Log.d(TAG, "Verifying panels in server")

            val serverSnapshot = if (clientDocName != null) {
                firestore.collection("$BASE_PATH/$clientDocName/panels")
                    .get(Source.SERVER)
                    .await()
            } else {
                Log.d(TAG, "Admin mode: usando collectionGroup('panels')")
                firestore.collectionGroup("panels")
                    .get(Source.SERVER)
                    .await()
            }

            val panelCount = serverSnapshot.size()
            lastServerValidation = System.currentTimeMillis()

            Log.d(TAG, "Server response: $panelCount panels")

            if (clientDocName == null) {
                Log.d(TAG, "=== DEBUG COLLECTIONGROUP ===")
                serverSnapshot.documents.forEachIndexed { index, doc ->
                    Log.d(TAG, "Document $index:")
                    Log.d(TAG, "  - ID: ${doc.id}")
                    Log.d(TAG, "  - Path: ${doc.reference.path}")
                    Log.d(TAG, "  - Exists: ${doc.exists()}")
                    if (doc.exists()) {
                        Log.d(TAG, "  - Data keys: ${doc.data?.keys}")
                    }
                }
                Log.d(TAG, "=== END DEBUG ===")
            }

            if (panelCount == 0) {
                return getAdminPanelsAlternative()
            }

            val panels = mutableListOf<Panel>()
            for (doc in serverSnapshot.documents) {
                if (doc.exists()) {
                    val clientId = if (clientDocName != null) {
                        clientDocName
                    } else {
                        val pathParts = doc.reference.path.split("/")
                        Log.d(TAG, "Path parts: $pathParts")

                        val clientsIndex = pathParts.indexOf("clients")
                        if (clientsIndex != -1 && clientsIndex + 1 < pathParts.size) {
                            val extractedClientId = pathParts[clientsIndex + 1]
                            Log.d(TAG, "Extracted clientId: $extractedClientId")
                            extractedClientId
                        } else {
                            Log.w(TAG, "Could not extract clientId from path: ${doc.reference.path}")
                            continue
                        }
                    }

                    Log.d(TAG, "Loading panel for clientId: $clientId")
                    val panel = loadCompletePanel(clientId, doc)
                    if (panel != null) {
                        panels.add(panel)
                        Log.d(TAG, "Panel loaded successfully: ${panel.name}")
                    } else {
                        Log.w(TAG, "Failed to load panel from doc: ${doc.id}")
                    }
                }
            }

            Log.d(TAG, "Total panels loaded: ${panels.size}")
            panels
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying server", e)
            getAdminPanelsAlternative()
        }
    }

    private suspend fun getAdminPanelsAlternative(): List<Panel> {
        return try {
            Log.d(TAG, "Using alternative method to get all panels for admin")

            val clientsSnapshot = firestore.collection("$BASE_PATH")
                .get(Source.SERVER)
                .await()

            val allPanels = mutableListOf<Panel>()

            for (clientDoc in clientsSnapshot.documents) {
                if (clientDoc.exists()) {
                    val clientId = clientDoc.id
                    Log.d(TAG, "Checking panels for client: $clientId")

                    try {
                        val panelsSnapshot = firestore.collection("$BASE_PATH/$clientId/panels")
                            .get(Source.SERVER)
                            .await()

                        Log.d(TAG, "Client $clientId has ${panelsSnapshot.size()} panels")

                        for (panelDoc in panelsSnapshot.documents) {
                            if (panelDoc.exists()) {
                                Log.d(TAG, "Found panel document: ${panelDoc.id} in client: $clientId")
                                val panel = loadCompletePanel(clientId, panelDoc)
                                if (panel != null) {
                                    allPanels.add(panel)
                                    Log.d(TAG, "Added panel: ${panel.name} from client: $clientId")
                                } else {
                                    Log.w(TAG, "Failed to load panel ${panelDoc.id} from client: $clientId")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting panels for client $clientId", e)
                    }
                }
            }

            Log.d(TAG, "Alternative method found ${allPanels.size} panels total")
            allPanels

        } catch (e: Exception) {
            Log.e(TAG, "Error in alternative admin panels method", e)
            emptyList()
        }
    }

    private fun validateAndSend(
        panels: List<Panel>,
        clientDocName: String?,
        onValidated: (List<Panel>) -> Unit
    ) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastServerValidation > SERVER_VALIDATION_INTERVAL) {
            coroutineScope.launch {
                val serverPanels = verifyPanelsInServer(clientDocName)

                if (panels.size != serverPanels.size) {
                    Log.w(TAG, "Inconsistency detected: Local=${panels.size}, Server=${serverPanels.size}")
                    onValidated(serverPanels)
                } else {
                    onValidated(panels)
                }
            }
        } else {
            onValidated(panels)
        }
    }

    private fun setupValidatedAdminPanelsFlow(onUpdate: (List<Panel>) -> Unit) {
        coroutineScope.launch {
            try {
                val clientsSnapshot = firestore.collection("$BASE_PATH")
                    .get()
                    .await()

                val allClientIds = clientsSnapshot.documents.mapNotNull { doc ->
                    if (doc.exists()) doc.id else null
                }

                Log.d(TAG, "Setting up individual listeners for ${allClientIds.size} clients")

                val allPanels = mutableListOf<Panel>()

                Log.d(TAG, "Loading existing panels initially for admin")
                for (clientId in allClientIds) {
                    try {
                        val panelsSnapshot = firestore.collection("$BASE_PATH/$clientId/panels")
                            .get()
                            .await()

                        val clientPanels = mutableListOf<Panel>()
                        for (panelDoc in panelsSnapshot.documents) {
                            if (panelDoc.exists()) {
                                val panel = loadCompletePanel(clientId, panelDoc)
                                if (panel != null) {
                                    clientPanels.add(panel)
                                    Log.d(TAG, "Initially loaded panel: ${panel.name} from client: $clientId")
                                }
                            }
                        }

                        allPanels.addAll(clientPanels)
                        Log.d(TAG, "Client $clientId: loaded ${clientPanels.size} panels initially")

                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading initial panels for client $clientId", e)
                    }
                }

                Log.d(TAG, "Sending initial panels: ${allPanels.size} total")
                setupRelayListeners(null, allPanels.toList(), onUpdate)
                onUpdate(allPanels.toList())

                allClientIds.forEach { clientId ->
                    val clientListenerId = "admin_client_${clientId}"

                    val clientPanelRegistration = firestore.collection("$BASE_PATH/$clientId/panels")
                        .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                            if (error != null) {
                                Log.e(TAG, "Error in client panels listener for $clientId", error)
                                return@addSnapshotListener
                            }

                            coroutineScope.launch {
                                try {
                                    val clientPanels = mutableListOf<Panel>()

                                    snapshot?.documents?.forEach { doc ->
                                        if (doc.exists()) {
                                            val panel = loadCompletePanel(clientId, doc)
                                            if (panel != null) {
                                                clientPanels.add(panel)
                                            }
                                        }
                                    }

                                    synchronized(allPanels) {
                                        allPanels.removeAll { it.clientName == clientId }
                                        allPanels.addAll(clientPanels)

                                        Log.d(TAG, "Updated panels for client $clientId: ${clientPanels.size}, Total: ${allPanels.size}")

                                        setupRelayListeners(null, allPanels.toList(), onUpdate)
                                        onUpdate(allPanels.toList())
                                    }

                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing panels for client $clientId", e)
                                }
                            }
                        }

                    addListener(clientListenerId, clientPanelRegistration, clientId, "", "admin_panels")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up admin panels flow", e)
                onUpdate(emptyList())
            }
        }
    }

    private fun setupValidatedClientPanelsFlow(clientDocName: String, onUpdate: (List<Panel>) -> Unit) {
        val panelListenerId = "panels_validated_${clientDocName}"

        val panelRegistration = firestore.collection("$BASE_PATH/$clientDocName/panels")
            .addSnapshotListener(MetadataChanges.INCLUDE) { panelsSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in panels listener for client $clientDocName", error)
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                if (panelsSnapshot == null) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                if (panelsSnapshot.metadata.isFromCache) {
                    Log.w(TAG, "Cache data detected for client $clientDocName")

                    coroutineScope.launch {
                        val serverPanels = verifyPanelsInServer(clientDocName)

                        if (serverPanels.isEmpty() && !panelsSnapshot.isEmpty) {
                            Log.w(TAG, "Obsolete cache for client $clientDocName")
                            onUpdate(emptyList())
                        } else {
                            processClientPanels(clientDocName, panelsSnapshot.documents, onUpdate)
                        }
                    }
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    processClientPanels(clientDocName, panelsSnapshot.documents, onUpdate)
                }
            }

        addListener(panelListenerId, panelRegistration, clientDocName, "", "client_panels")
    }

    private suspend fun processAdminPanels(
        documents: List<com.google.firebase.firestore.DocumentSnapshot>,
        onUpdate: (List<Panel>) -> Unit
    ) {
        if (documents.isEmpty()) {
            Log.d(TAG, "No documents from admin query, trying alternative method")
            val alternativePanels = getAdminPanelsAlternative()
            setupRelayListeners(null, alternativePanels, onUpdate)
            onUpdate(alternativePanels)
            return
        }

        val panels = mutableListOf<Panel>()

        for (doc in documents) {
            if (doc.exists()) {
                val documentPath = doc.reference.path
                val pathParts = documentPath.split("/")

                val clientsIndex = pathParts.indexOf("clients")
                if (clientsIndex != -1 && clientsIndex + 1 < pathParts.size) {
                    val clientId = pathParts[clientsIndex + 1]
                    val panel = loadCompletePanel(clientId, doc)

                    if (panel != null) {
                        panels.add(panel)
                        Log.d(TAG, "Admin panel loaded: ${panel.name}, relays: ${panel.relays.size}")
                    }
                }
            }
        }

        setupRelayListeners(null, panels, onUpdate)
        onUpdate(panels)
        Log.d(TAG, "Admin panels loaded: ${panels.size}")
    }

    private suspend fun processClientPanels(
        clientDocName: String,
        documents: List<com.google.firebase.firestore.DocumentSnapshot>,
        onUpdate: (List<Panel>) -> Unit
    ) {
        val panels = mutableListOf<Panel>()

        for (doc in documents) {
            if (doc.exists()) {
                val panel = loadCompletePanel(clientDocName, doc)
                if (panel != null) {
                    panels.add(panel)
                    Log.d(TAG, "Client panel loaded: ${panel.name}, relays: ${panel.relays.size}")
                }
            }
        }

        setupRelayListeners(clientDocName, panels, onUpdate)
        onUpdate(panels)
        Log.d(TAG, "Client panels loaded for $clientDocName: ${panels.size}")
    }

    private suspend fun loadCompletePanel(
        clientDocName: String,
        panelDoc: com.google.firebase.firestore.DocumentSnapshot
    ): Panel? {
        return try {
            val panelData = panelDoc.data ?: return null
            val panelId = panelDoc.id
            val esp32Id = panelData["esp32_id"] as? String ?: ""

            val basePanel = Panel(
                documentName = panelId,
                name = panelData["name"] as? String ?: "",
                location = panelData["location"] as? String ?: "",
                clientName = clientDocName,
                clientDisplayName = panelData["clientDisplayName"] as? String ?: clientDocName,
                esp32_id = esp32Id,
                lastUpdate = panelData["lastUpdate"] as? Long ?: System.currentTimeMillis(),
                relays = emptyList(),
                esp32Status = if (esp32Id.isNotEmpty()) loadESP32Status(esp32Id) else ESP32Device.STATUS_OFFLINE
            )

            val relays = loadPanelRelays(clientDocName, panelId)
            basePanel.copy(relays = relays)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading complete panel", e)
            null
        }
    }

    private suspend fun loadPanelRelays(clientDocName: String, panelDocName: String): List<Relay> {
        return try {
            val relaysSnapshot = firestore
                .collection("$BASE_PATH/$clientDocName/panels/$panelDocName/relays")
                .get()
                .await()

            val relays = relaysSnapshot.documents.mapNotNull { doc ->
                try {
                    val data = doc.data ?: return@mapNotNull null
                    Relay(
                        name = doc.id,
                        status = data["status"] as? String ?: "UNKNOWN",
                        date_time = data["date_time"] as? String,
                        customName = data["customName"] as? String,
                        isActive = data["isActive"] as? Boolean ?: false,
                        contactType = data["contactType"] as? String ?: "NO",
                        lastCommandSent = (data["lastCommandSent"] as? Number)?.toLong(),
                        commandSource = data["commandSource"] as? String
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting relay ${doc.id}", e)
                    null
                }
            }

            Log.d(TAG, "Loaded ${relays.size} relays for panel $panelDocName")
            relays
        } catch (e: Exception) {
            Log.e(TAG, "Error loading relays for panel $panelDocName", e)
            emptyList()
        }
    }

    private suspend fun loadESP32Status(esp32Id: String): String {
        return try {
            if (esp32Id.isEmpty()) {
                ESP32Device.STATUS_OFFLINE
            } else {
                val esp32Doc = firestore
                    .document("hdd-monitor/esp32/registered/$esp32Id")
                    .get()
                    .await()

                if (esp32Doc.exists()) {
                    esp32Doc.getString("status") ?: ESP32Device.STATUS_OFFLINE
                } else {
                    ESP32Device.STATUS_OFFLINE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading ESP32 status $esp32Id", e)
            ESP32Device.STATUS_OFFLINE
        }
    }

    private fun setupRelayListeners(clientDocName: String?, panels: List<Panel>, onUpdate: (List<Panel>) -> Unit) {
        panels.forEach { panel ->
            val relayListenerId = "relays_${panel.clientName}_${panel.documentName}"

            val existingListener = activeListeners[relayListenerId]
            if (existingListener != null) {
                return@forEach
            }

            val relayRegistration = firestore
                .collection("$BASE_PATH/${panel.clientName}/panels/${panel.documentName}/relays")
                .addSnapshotListener { relaysSnapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error in relays listener for ${panel.documentName}", error)
                        return@addSnapshotListener
                    }

                    coroutineScope.launch {
                        try {
                            val relays = relaysSnapshot?.documents?.mapNotNull { doc ->
                                try {
                                    val data = doc.data ?: return@mapNotNull null
                                    Relay(
                                        name = doc.id,
                                        status = data["status"] as? String ?: "UNKNOWN",
                                        date_time = data["date_time"] as? String,
                                        customName = data["customName"] as? String,
                                        isActive = data["isActive"] as? Boolean ?: false,
                                        contactType = data["contactType"] as? String ?: "NO",
                                        lastCommandSent = (data["lastCommandSent"] as? Number)?.toLong(),
                                        commandSource = data["commandSource"] as? String
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error converting relay ${doc.id}", e)
                                    null
                                }
                            } ?: emptyList()

                            val updatedPanels = panels.map { p ->
                                if (p.documentName == panel.documentName) {
                                    p.copy(relays = relays)
                                } else p
                            }

                            onUpdate(updatedPanels)
                            Log.d(TAG, "Relays updated for panel ${panel.documentName}: ${relays.size} relays")

                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing relays for panel ${panel.documentName}", e)
                        }
                    }
                }

            addListener(relayListenerId, relayRegistration, panel.clientName, panel.documentName, "relay")
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
                    .get()
                    .await()

                if (esp32Doc.exists()) {
                    val existingPanelId = esp32Doc.getString("panel_id")
                    if (!existingPanelId.isNullOrEmpty()) {
                        throw IllegalStateException("ESP32 already assigned to another panel")
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

    private suspend fun getClientDisplayName(clientDocName: String): String {
        return try {
            val clientDoc = firestore.document("$BASE_PATH/$clientDocName")
                .get()
                .await()
            clientDoc.getString("name") ?: clientDocName
        } catch (e: Exception) {
            Log.e(TAG, "Error getting client display name", e)
            clientDocName
        }
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

            if (newEsp32Id != panel.esp32_id) {
                if (panel.esp32_id.isNotEmpty()) {
                    esp32Repository.unassignFromPanel(panel.esp32_id)
                        .onFailure { e ->
                            Log.e(TAG, "Error unassigning previous ESP32", e)
                        }
                }

                newEsp32Id?.let {
                    esp32Repository.assignToPanelAndClient(it, clientDocName, panel.documentName)
                        .onFailure { e ->
                            Log.e(TAG, "Error assigning new ESP32", e)
                        }
                }
            }

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
                "lastUpdate" to FieldValue.serverTimestamp()
            )

            firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName/relays/$relayName")
                .update(updateData)
                .await()

            Log.d(TAG, "Relay status updated successfully")
        }
    }

    suspend fun deletePanel(
        clientDocName: String,
        panelDocName: String
    ): Result<Unit> = runCatching {
        withRetry {
            val panelDoc = firestore
                .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                .get()
                .await()

            clearRelatedListeners(clientDocName, panelDocName)

            val esp32Id = panelDoc.getString("esp32_id")
            if (!esp32Id.isNullOrEmpty()) {
                esp32Repository.deleteESP32(esp32Id)
                    .onFailure { e ->
                        Log.e(TAG, "Error deleting ESP32", e)
                    }
            }

            val relaysSnapshot = firestore
                .collection("$BASE_PATH/$clientDocName/panels/$panelDocName/relays")
                .get()
                .await()

            val batch = firestore.batch()
            relaysSnapshot.documents.forEach { doc ->
                batch.delete(doc.reference)
            }

            batch.delete(panelDoc.reference)
            batch.commit().await()

            Log.d(TAG, "Panel, relays and ESP32 deleted successfully")
        }
    }

    private fun clearRelatedListeners(clientDocName: String, panelDocName: String) {
        val keysToRemove = activeListeners.keys.filter {
            it.contains("${clientDocName}_$panelDocName") || it.contains("relays_${clientDocName}_$panelDocName")
        }

        keysToRemove.forEach { key ->
            removeListener(key)
        }
    }

    suspend fun verifyPanelExists(clientDocName: String, panelDocName: String): Boolean {
        return try {
            withRetry {
                val panelDoc = firestore
                    .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                    .get()
                    .await()
                panelDoc.exists()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying panel existence", e)
            false
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
            val dateTime = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))

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

            Log.d(TAG, "Relay command sent successfully: $relayName -> $newState")
        }
    }

    fun observePanelUpdates(clientDocName: String, panelDocName: String): Flow<Panel?> = callbackFlow {
        Log.d(TAG, "Starting panel updates observation for $clientDocName/$panelDocName")

        val panelListenerId = "panel_${clientDocName}_$panelDocName"
        val relayListenerId = "relays_${clientDocName}_$panelDocName"

        fun loadAndSendPanel() {
            coroutineScope.launch {
                try {
                    val panelDoc = firestore
                        .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                        .get()
                        .await()

                    val panel = if (panelDoc.exists()) {
                        loadCompletePanel(clientDocName, panelDoc)
                    } else null

                    trySend(panel)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading panel in observer", e)
                    trySend(null)
                }
            }
        }

        val panelRegistration = firestore
            .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
            .addSnapshotListener { _, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing panel", error)
                    return@addSnapshotListener
                }
                loadAndSendPanel()
            }

        val relayRegistration = firestore
            .collection("$BASE_PATH/$clientDocName/panels/$panelDocName/relays")
            .addSnapshotListener { _, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing relays", error)
                    return@addSnapshotListener
                }
                loadAndSendPanel()
            }

        addListener(panelListenerId, panelRegistration, clientDocName, panelDocName, "panel_observer")
        addListener(relayListenerId, relayRegistration, clientDocName, panelDocName, "relay_observer")

        awaitClose {
            Log.d(TAG, "Closing panel updates observation")
            removeListener(panelListenerId)
            removeListener(relayListenerId)
        }
    }.flowOn(Dispatchers.IO)

    fun getListenerStats(): Map<String, Any> {
        return mapOf(
            "activeListeners" to activeListeners.size,
            "maxListeners" to MAX_ACTIVE_LISTENERS,
            "memoryUsageMB" to getMemoryUsage(),
            "reconnectionAttempts" to reconnectionAttempts,
            "listenersByType" to activeListeners.values.groupBy { it.type }.mapValues { it.value.size }
        )
    }

    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }

    fun performPeriodicCleanup() {
        if (System.currentTimeMillis() - lastCleanup < CLEANUP_INTERVAL) return

        val currentTime = System.currentTimeMillis()
        val staleListeners = activeListeners.filter { (_, info) ->
            currentTime - info.createdAt > STALE_LISTENER_TIMEOUT
        }

        staleListeners.keys.forEach { key ->
            removeListener(key)
        }

        if (activeListeners.size > MAX_ACTIVE_LISTENERS) {
            cleanupExcessiveListeners()
        }

        lastCleanup = currentTime
    }

    fun forceCleanupAllListeners() {
        Log.w(TAG, "LIMPIEZA FORZADA DE TODOS LOS LISTENERS")
        val allKeys = activeListeners.keys.toList()
        allKeys.forEach { key ->
            removeListener(key)
        }
    }
}