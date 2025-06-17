package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
    }

    private val activeListeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    fun clearListeners() {
        Log.d(TAG, "Clearing all panel and relay listeners: ${activeListeners.size} listeners")
        val listenersToRemove = activeListeners.toMap()
        activeListeners.clear()
        listenersToRemove.values.forEach { listener ->
            try {
                listener.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener", e)
            }
        }
        Log.d(TAG, "All listeners cleared successfully")
    }

    fun getPanels(clientDocName: String?): Flow<List<Panel>> = callbackFlow {
        Log.d(TAG, "getPanels called with clientDocName: $clientDocName")

        val listenerId = "panels_${clientDocName ?: "all"}_${System.currentTimeMillis()}"

        try {
            val registration = if (clientDocName != null) {
                setupClientPanelsListener(clientDocName) { panels ->
                    trySend(panels)
                }
            } else {
                setupAllClientsPanelsListener { panels ->
                    trySend(panels)
                }
            }

            activeListeners[listenerId] = registration

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up panels listener", e)
            close(e)
        }

        awaitClose {
            Log.d(TAG, "Closing panel flow")
            activeListeners[listenerId]?.remove()
            activeListeners.remove(listenerId)
        }
    }.flowOn(Dispatchers.IO)

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

    private fun setupClientPanelsListener(
        clientDocName: String,
        onUpdate: (List<Panel>) -> Unit
    ): ListenerRegistration {
        return firestore.collection("$BASE_PATH/$clientDocName/panels")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in panels listener for client $clientDocName", error)
                    return@addSnapshotListener
                }

                if (snapshot == null) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    try {
                        val clientDisplayName = getClientDisplayName(clientDocName)
                        val panels = mutableListOf<Panel>()

                        for (doc in snapshot.documents) {
                            if (doc.exists()) {
                                try {
                                    val panel = doc.toObject(Panel::class.java)?.copy(
                                        documentName = doc.id,
                                        clientName = clientDocName,
                                        clientDisplayName = clientDisplayName,
                                        lastUpdate = doc.getLong("lastUpdate") ?: System.currentTimeMillis()
                                    )

                                    if (panel != null) {
                                        setupRelayListener(clientDocName, panel)
                                        setupESP32Listener(panel)
                                        panels.add(panel)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error converting panel", e)
                                }
                            }
                        }

                        onUpdate(panels)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing panels", e)
                        onUpdate(emptyList())
                    }
                }
            }
    }

    private fun setupAllClientsPanelsListener(
        onUpdate: (List<Panel>) -> Unit
    ): ListenerRegistration {
        return firestore.collection(BASE_PATH)
            .addSnapshotListener { clientsSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in clients listener", error)
                    return@addSnapshotListener
                }

                if (clientsSnapshot == null) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    val allPanels = mutableListOf<Panel>()

                    for (clientDoc in clientsSnapshot.documents) {
                        if (clientDoc.exists()) {
                            val clientId = clientDoc.id
                            val clientDisplayName = clientDoc.getString("name") ?: clientId

                            val clientListenerId = "client_panels_$clientId"

                            if (!activeListeners.containsKey(clientListenerId)) {
                                val clientListener = firestore.collection("$BASE_PATH/$clientId/panels")
                                    .addSnapshotListener { panelsSnapshot, panelsError ->
                                        if (panelsError != null) {
                                            Log.e(TAG, "Error in client panels listener for $clientId", panelsError)
                                            return@addSnapshotListener
                                        }

                                        if (panelsSnapshot != null) {
                                            coroutineScope.launch {
                                                try {
                                                    val clientPanels = mutableListOf<Panel>()

                                                    for (doc in panelsSnapshot.documents) {
                                                        if (doc.exists()) {
                                                            try {
                                                                val panel = doc.toObject(Panel::class.java)?.copy(
                                                                    documentName = doc.id,
                                                                    clientName = clientId,
                                                                    clientDisplayName = clientDisplayName,
                                                                    lastUpdate = doc.getLong("lastUpdate") ?: System.currentTimeMillis()
                                                                )

                                                                if (panel != null) {
                                                                    setupRelayListener(clientId, panel)
                                                                    setupESP32Listener(panel)
                                                                    clientPanels.add(panel)
                                                                }
                                                            } catch (e: Exception) {
                                                                Log.e(TAG, "Error converting panel for client $clientId", e)
                                                            }
                                                        }
                                                    }

                                                    synchronized(allPanels) {
                                                        allPanels.removeAll { it.clientName == clientId }
                                                        allPanels.addAll(clientPanels)
                                                    }

                                                    onUpdate(allPanels.toList())
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error processing client panels for $clientId", e)
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
    }

    private fun setupRelayListener(clientDocName: String, panel: Panel) {
        val relayListenerId = "relays_${clientDocName}_${panel.documentName}"

        if (activeListeners.containsKey(relayListenerId)) {
            return
        }

        val registration = firestore
            .collection("$BASE_PATH/$clientDocName/panels/${panel.documentName}/relays")
            .addSnapshotListener { relaysSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in relays listener for panel ${panel.documentName}", error)
                    return@addSnapshotListener
                }

                if (relaysSnapshot != null) {
                    val updatedRelays = relaysSnapshot.documents.mapNotNull { doc ->
                        try {
                            val relayData = doc.data ?: emptyMap()
                            Relay.fromMap(relayData.plus(mapOf(
                                "name" to doc.id,
                                "customName" to (relayData["customName"] ?: doc.id),
                                "isControllable" to (relayData["isControllable"] ?: true),
                                "isActive" to (relayData["isActive"] ?: true),
                                "commandSource" to (relayData["commandSource"] ?: "esp32")
                            )))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting relay", e)
                            null
                        }
                    }

                    panel.relays = updatedRelays

                    updatedRelays.forEach { relay ->
                        StatusUpdateManager.emitRelayStatusUpdateSync(
                            panel.documentName,
                            relay.name,
                            relay.status
                        )
                    }
                }
            }

        activeListeners[relayListenerId] = registration
    }

    private fun setupESP32Listener(panel: Panel) {
        if (panel.esp32_id.isEmpty()) {
            panel.esp32Status = ESP32Device.STATUS_OFFLINE
            return
        }

        val esp32ListenerId = "esp32_${panel.esp32_id}"

        if (activeListeners.containsKey(esp32ListenerId)) {
            return
        }

        val registration = firestore
            .document("hdd-monitor/esp32/registered/${panel.esp32_id}")
            .addSnapshotListener { esp32Snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in ESP32 listener for ${panel.esp32_id}", error)
                    panel.esp32Status = ESP32Device.STATUS_OFFLINE
                    return@addSnapshotListener
                }

                val newStatus = if (esp32Snapshot != null && esp32Snapshot.exists()) {
                    esp32Snapshot.getString("status") ?: ESP32Device.STATUS_OFFLINE
                } else {
                    ESP32Device.STATUS_OFFLINE
                }

                if (panel.esp32Status != newStatus) {
                    panel.esp32Status = newStatus
                    StatusUpdateManager.emitEsp32StatusUpdateSync(panel.documentName, newStatus)
                }
            }

        activeListeners[esp32ListenerId] = registration
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
                "lastUpdate" to System.currentTimeMillis()
            )

            firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName/relays/$relayName")
                .set(updateData)
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

            val panelListenerId = "panel_observe_${clientDocName}_$panelDocName"
            val relayListenerId = "relays_${clientDocName}_$panelDocName"

            activeListeners[panelListenerId]?.remove()
            activeListeners.remove(panelListenerId)

            activeListeners[relayListenerId]?.remove()
            activeListeners.remove(relayListenerId)

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
                .get()
                .await()

            if (!panelDoc.exists()) {
                null
            } else {
                val panel = panelDoc.toObject(Panel::class.java)?.copy(
                    documentName = panelDoc.id,
                    clientName = clientDocName
                )

                panel?.let { p ->
                    val relaysSnapshot = firestore
                        .collection("$BASE_PATH/$clientDocName/panels/$panelDocName/relays")
                        .get()
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

    fun observePanelUpdates(clientDocName: String, panelDocName: String): Flow<Panel?> =
        callbackFlow {
            Log.d(TAG, "Starting panel updates observation for $clientDocName/$panelDocName")

            val panelListenerId = "panel_observe_${clientDocName}_$panelDocName"

            trySend(null)

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
                                    lastUpdate = snapshot.getLong("lastUpdate") ?: System.currentTimeMillis()
                                )

                                panel?.let {
                                    setupRelayListener(clientDocName, it)
                                    setupESP32Listener(it)
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