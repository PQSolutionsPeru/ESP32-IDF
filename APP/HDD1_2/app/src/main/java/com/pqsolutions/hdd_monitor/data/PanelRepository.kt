package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.pqsolutions.hdd_monitor.data.util.IdManager
import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
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
    }

    private val activeListeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val listenerCreationTime = ConcurrentHashMap<String, Long>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _panelUpdates = MutableSharedFlow<List<Panel>>(replay = 1)
    private var currentPanelsList = mutableListOf<Panel>()

    fun getPanels(clientDocName: String?): Flow<List<Panel>> {
        coroutineScope.launch {
            if (clientDocName != null) {
                startClientPanelsCollection(clientDocName)
            } else {
                startAdminPanelsCollection()
            }
        }

        return _panelUpdates.combine(esp32Repository.observeESP32s()) { panels: List<Panel>, esp32Devices: List<ESP32Device> ->
            val esp32StatusMap = esp32Devices.associateBy({ device -> device.documentName }, { device -> device.status })

            panels.map { panel: Panel ->
                val currentESP32Status = esp32StatusMap[panel.esp32_id] ?: ESP32Device.STATUS_OFFLINE
                panel.copy(esp32Status = currentESP32Status)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    private suspend fun startClientPanelsCollection(clientDocName: String) {
        Log.d(TAG, "Starting client panels collection for: $clientDocName")

        val panelsListenerId = "client_panels_$clientDocName"
        clearOldListeners(panelsListenerId)

        val panelsListener = firestore.collection("$BASE_PATH/$clientDocName/panels")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in client panels listener", error)
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    val panels = mutableListOf<Panel>()

                    snapshot?.documents?.forEach { doc ->
                        if (doc.exists()) {
                            val panel = createPanelFromDocument(clientDocName, doc)
                            if (panel != null) {
                                val completePanel = loadPanelWithRelays(clientDocName, panel)
                                panels.add(completePanel)
                            }
                        }
                    }

                    synchronized(currentPanelsList) {
                        currentPanelsList.clear()
                        currentPanelsList.addAll(panels)
                    }

                    _panelUpdates.tryEmit(panels)
                    setupRelayListenersForClient(clientDocName, panels)
                }
            }

        activeListeners[panelsListenerId] = panelsListener
        listenerCreationTime[panelsListenerId] = System.currentTimeMillis()
    }

    private suspend fun startAdminPanelsCollection() {
        Log.d(TAG, "Starting admin panels collection with dynamic client discovery")

        val clientsListenerId = "admin_clients_dynamic"
        clearOldListeners("admin_")

        val clientsListener = firestore.collection(BASE_PATH)
            .addSnapshotListener(MetadataChanges.INCLUDE) { clientsSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in clients listener", error)
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    val allPanels = mutableListOf<Panel>()
                    val clientIds = clientsSnapshot?.documents?.mapNotNull { it.id } ?: emptyList()

                    for (clientId in clientIds) {
                        try {
                            val panelsSnapshot = firestore.collection("$BASE_PATH/$clientId/panels")
                                .get(Source.DEFAULT)
                                .await()

                            panelsSnapshot.documents.forEach { doc ->
                                if (doc.exists()) {
                                    val panel = createPanelFromDocument(clientId, doc)
                                    if (panel != null) {
                                        val completePanel = loadPanelWithRelays(clientId, panel)
                                        allPanels.add(completePanel)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading panels for client $clientId", e)
                        }
                    }

                    synchronized(currentPanelsList) {
                        currentPanelsList.clear()
                        currentPanelsList.addAll(allPanels)
                    }

                    _panelUpdates.tryEmit(allPanels)
                    setupAdminRelayListeners(clientIds, allPanels)
                    setupDynamicPanelListeners(clientIds)
                }
            }

        activeListeners[clientsListenerId] = clientsListener
        listenerCreationTime[clientsListenerId] = System.currentTimeMillis()
    }

    private fun setupDynamicPanelListeners(clientIds: List<String>) {
        clientIds.forEach { clientId ->
            val panelListenerId = "admin_panel_$clientId"

            if (!activeListeners.containsKey(panelListenerId)) {
                val panelListener = firestore.collection("$BASE_PATH/$clientId/panels")
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        if (error != null) {
                            Log.e(TAG, "Error in dynamic panel listener for $clientId", error)
                            return@addSnapshotListener
                        }

                        Log.d(TAG, "Panel changes detected for client $clientId: ${snapshot?.size()} panels")
                    }

                activeListeners[panelListenerId] = panelListener
                listenerCreationTime[panelListenerId] = System.currentTimeMillis()
            }
        }
    }

    private fun createPanelFromDocument(
        clientDocName: String,
        doc: com.google.firebase.firestore.DocumentSnapshot
    ): Panel? {
        return try {
            val data = doc.data ?: return null

            Panel(
                documentName = doc.id,
                name = data["name"] as? String ?: "",
                location = data["location"] as? String ?: "",
                clientName = clientDocName,
                clientDisplayName = data["clientDisplayName"] as? String ?: clientDocName,
                esp32_id = data["esp32_id"] as? String ?: "",
                lastUpdate = data["lastUpdate"] as? Long ?: System.currentTimeMillis(),
                relays = emptyList(),
                esp32Status = ESP32Device.STATUS_OFFLINE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating panel from document ${doc.id}", e)
            null
        }
    }

    private suspend fun loadPanelWithRelays(clientDocName: String, panel: Panel): Panel {
        return try {
            val relaysSnapshot = firestore
                .collection("$BASE_PATH/$clientDocName/panels/${panel.documentName}/relays")
                .get(Source.DEFAULT)
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

            panel.copy(relays = relays)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading relays for panel ${panel.documentName}", e)
            panel
        }
    }

    private fun clearOldListeners(prefix: String) {
        val listenersToRemove = activeListeners.keys.filter { it.startsWith(prefix) }

        listenersToRemove.forEach { key ->
            try {
                activeListeners[key]?.remove()
                activeListeners.remove(key)
                listenerCreationTime.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener $key", e)
            }
        }
    }

    suspend fun reactivateListenersForClient(clientDocName: String?) {
        Log.d(TAG, "Reactivating listeners for client: $clientDocName")

        if (clientDocName != null) {
            clearOldListeners("client_panels_$clientDocName")
            startClientPanelsCollection(clientDocName)
        } else {
            clearOldListeners("admin_")
            startAdminPanelsCollection()
        }
    }

    suspend fun forceRefreshFromServer() {
        try {
            Log.d(TAG, "Forcing refresh from server")

            firestore.collection(BASE_PATH)
                .limit(1)
                .get(Source.SERVER)
                .await()

            Log.d(TAG, "Force refresh completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error in force refresh", e)
            throw e
        }
    }

    private fun setupRelayListenersForClient(
        clientDocName: String,
        panels: List<Panel>
    ) {
        panels.forEach { panel ->
            val relayListenerId = "relays_${clientDocName}_${panel.documentName}"

            if (activeListeners.containsKey(relayListenerId)) {
                return@forEach
            }

            val relayRegistration = firestore
                .collection("$BASE_PATH/$clientDocName/panels/${panel.documentName}/relays")
                .addSnapshotListener(MetadataChanges.INCLUDE) { relaysSnapshot, error ->
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

                            synchronized(currentPanelsList) {
                                val panelIndex = currentPanelsList.indexOfFirst { it.documentName == panel.documentName }
                                if (panelIndex != -1) {
                                    val updatedPanel = currentPanelsList[panelIndex].copy(relays = relays)
                                    currentPanelsList[panelIndex] = updatedPanel

                                    Log.d(TAG, "Relays updated for panel ${panel.documentName}: ${relays.size} relays")
                                    _panelUpdates.tryEmit(currentPanelsList.toList())
                                }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing relays for panel ${panel.documentName}", e)
                        }
                    }
                }

            activeListeners[relayListenerId] = relayRegistration
            listenerCreationTime[relayListenerId] = System.currentTimeMillis()
        }
    }

    private fun setupAdminRelayListeners(
        clientIds: List<String>,
        panels: List<Panel>
    ) {
        panels.forEach { panel ->
            val relayListenerId = "admin_relay_${panel.clientName}_${panel.documentName}"

            if (activeListeners.containsKey(relayListenerId)) {
                return@forEach
            }

            val relayRegistration = firestore
                .collection("$BASE_PATH/${panel.clientName}/panels/${panel.documentName}/relays")
                .addSnapshotListener(MetadataChanges.INCLUDE) { relaysSnapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error in admin relays listener for ${panel.documentName}", error)
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
                                    Log.e(TAG, "Error converting admin relay ${doc.id}", e)
                                    null
                                }
                            } ?: emptyList()

                            synchronized(currentPanelsList) {
                                val panelIndex = currentPanelsList.indexOfFirst { it.documentName == panel.documentName }
                                if (panelIndex != -1) {
                                    val updatedPanel = currentPanelsList[panelIndex].copy(relays = relays)
                                    currentPanelsList[panelIndex] = updatedPanel

                                    Log.d(TAG, "Admin relays updated for panel ${panel.documentName}: ${relays.size} relays")
                                    _panelUpdates.tryEmit(currentPanelsList.toList())
                                }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing admin relays for panel ${panel.documentName}", e)
                        }
                    }
                }

            activeListeners[relayListenerId] = relayRegistration
            listenerCreationTime[relayListenerId] = System.currentTimeMillis()
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
            activeListeners[key]?.remove()
            activeListeners.remove(key)
            listenerCreationTime.remove(key)
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

        val panelRegistration = firestore
            .document("$BASE_PATH/$clientDocName/panels/$panelDocName")
            .addSnapshotListener { panelSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing panel", error)
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    try {
                        val panel = if (panelSnapshot?.exists() == true) {
                            val basePanel = createPanelFromDocument(clientDocName, panelSnapshot)
                            basePanel?.let { loadPanelWithRelays(clientDocName, it) }
                        } else null

                        trySend(panel)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading panel in observer", e)
                        trySend(null)
                    }
                }
            }

        activeListeners[panelListenerId] = panelRegistration
        listenerCreationTime[panelListenerId] = System.currentTimeMillis()

        awaitClose {
            Log.d(TAG, "Closing panel updates observation")
            panelRegistration.remove()
            activeListeners.remove(panelListenerId)
            listenerCreationTime.remove(panelListenerId)
        }
    }.flowOn(Dispatchers.IO)

    fun getListenerStats(): Map<String, Any> {
        return mapOf(
            "activeListeners" to activeListeners.size,
            "memoryUsageMB" to getMemoryUsage()
        )
    }

    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }

    fun clearListeners() {
        Log.d(TAG, "Clearing ${activeListeners.size} listeners")

        val listenersSnapshot = activeListeners.toMap()
        activeListeners.clear()
        listenerCreationTime.clear()

        listenersSnapshot.forEach { (key, registration) ->
            try {
                registration.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener: $key", e)
            }
        }
    }

    fun clearStaleListeners() {
        Log.d(TAG, "Clearing stale listeners only")

        val currentTime = System.currentTimeMillis()
        val staleListenerAge = 5 * 60 * 1000L

        val staleKeys = activeListeners.keys.filter { key ->
            key.contains("_old_") ||
                    key.contains("_temp_") ||
                    isListenerStale(key, currentTime, staleListenerAge)
        }

        staleKeys.forEach { key ->
            try {
                activeListeners[key]?.remove()
                activeListeners.remove(key)
                listenerCreationTime.remove(key)
                Log.d(TAG, "Removed stale listener: $key")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing stale listener: $key", e)
            }
        }
    }

    private fun isListenerStale(key: String, currentTime: Long, staleAge: Long): Boolean {
        val creationTime = listenerCreationTime[key] ?: return false
        val age = currentTime - creationTime
        return age > staleAge && !key.contains("client_panels_") && !key.contains("admin_clients_")
    }

    fun performPeriodicCleanup() {
        Log.d(TAG, "Performing periodic cleanup - selective mode")
        clearStaleListeners()

        val now = System.currentTimeMillis()
        if (now % 300000 < 10000) {
            System.gc()
        }
    }
}