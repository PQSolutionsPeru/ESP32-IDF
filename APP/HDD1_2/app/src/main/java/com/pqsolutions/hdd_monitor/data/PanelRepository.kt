package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
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
        Log.d(TAG, "Clearing ${activeListeners.size} active listeners")
        val listenersToRemove = activeListeners.toMap()
        activeListeners.clear()

        listenersToRemove.forEach { (key, listener) ->
            try {
                listener.remove()
                Log.d(TAG, "Removed listener: $key")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener $key", e)
            }
        }
    }

    fun getPanels(clientDocName: String?): Flow<List<Panel>> = callbackFlow {
        Log.d(TAG, "getPanels called with clientDocName: $clientDocName")

        try {
            if (clientDocName != null) {
                setupClientPanelsFlow(clientDocName) { panels ->
                    trySend(panels)
                }
            } else {
                setupAdminPanelsFlow { panels ->
                    trySend(panels)
                }
            }

            awaitClose {
                Log.d(TAG, "Closing panel flow for clientDocName: $clientDocName")
                clearListeners()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up panels listener", e)
            close(e)
        }
    }.flowOn(Dispatchers.IO)

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

    private fun setupClientPanelsFlow(clientDocName: String, onUpdate: (List<Panel>) -> Unit) {
        val panelListenerId = "panels_${clientDocName}_${System.currentTimeMillis()}"

        val panelRegistration = firestore.collection("$BASE_PATH/$clientDocName/panels")
            .addSnapshotListener { panelsSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in panels listener for client $clientDocName", error)
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                if (panelsSnapshot == null) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    try {
                        val panels = mutableListOf<Panel>()

                        for (doc in panelsSnapshot.documents) {
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing panels for client $clientDocName", e)
                        onUpdate(emptyList())
                    }
                }
            }

        activeListeners[panelListenerId] = panelRegistration
    }

    private fun setupAdminPanelsFlow(onUpdate: (List<Panel>) -> Unit) {
        val panelListenerId = "admin_panels_${System.currentTimeMillis()}"

        val panelRegistration = firestore.collectionGroup("panels")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in admin panels listener", error)
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                if (snapshot == null) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                coroutineScope.launch {
                    try {
                        val panels = mutableListOf<Panel>()

                        for (doc in snapshot.documents) {
                            if (doc.exists()) {
                                val documentPath = doc.reference.path
                                val pathParts = documentPath.split("/")

                                if (pathParts.size >= 4) {
                                    val clientId = pathParts[3]
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing admin panels", e)
                        onUpdate(emptyList())
                    }
                }
            }

        activeListeners[panelListenerId] = panelRegistration
    }

    private fun setupRelayListeners(clientDocName: String?, panels: List<Panel>, onUpdate: (List<Panel>) -> Unit) {
        panels.forEach { panel ->
            val relayListenerId = "relays_${panel.clientName}_${panel.documentName}_${System.currentTimeMillis()}"

            if (activeListeners.containsKey(relayListenerId)) {
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

            activeListeners[relayListenerId] = relayRegistration
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
            activeListeners.remove(key)?.remove()
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

        activeListeners[panelListenerId] = panelRegistration
        activeListeners[relayListenerId] = relayRegistration

        awaitClose {
            Log.d(TAG, "Closing panel updates observation")
            activeListeners.remove(panelListenerId)?.remove()
            activeListeners.remove(relayListenerId)?.remove()
        }
    }.flowOn(Dispatchers.IO)
}