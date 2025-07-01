package com.pqsolutions.hdd_monitor.esp32

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ESP32Repository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val TAG = "ESP32Repository"
        private const val ESP32_COLLECTION = "hdd-monitor/esp32/registered"
        private const val BASE_PATH = "hdd-monitor/accounts/clients"
        private const val MAX_LISTENERS = 50
        private var lastCleanup = 0L
    }

    private val activeListeners = ConcurrentHashMap<String, ListenerRegistration>()

    private fun DocumentSnapshot.toESP32Device(): ESP32Device? {
        return try {
            if (!exists()) return null

            toObject(ESP32Device::class.java)?.copy(
                documentName = id
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error convirtiendo documento ESP32", e)
            null
        }
    }

    suspend fun getUnassignedESP32s(): List<ESP32Device> {
        return try {
            Log.d(TAG, "Obteniendo ESP32s no asignados")

            val snapshot = firestore.collection(ESP32_COLLECTION)
                .whereIn("status", listOf(
                    ESP32Device.STATUS_AWAITING_CONFIG,
                    ESP32Device.STATUS_PENDING_ASSIGNMENT,
                    ESP32Device.STATUS_WIFI_CONFIG,
                    "CONFIG"
                ))
                .get()
                .await()

            val devices = snapshot.documents.mapNotNull { doc ->
                val device = doc.toESP32Device()
                if (device != null) {
                    val clientId = doc.getString("client_id") ?: ""
                    val panelId = doc.getString("panel_id") ?: ""
                    val isUnassigned = clientId.isEmpty() && panelId.isEmpty()

                    if (isUnassigned) {
                        Log.d(TAG, "ESP32 sin asignar encontrado: ${doc.id}, Status: ${device.status}")
                        device
                    } else {
                        Log.d(TAG, "ESP32 ya asignado, ignorando: ${doc.id}")
                        null
                    }
                } else null
            }

            Log.d(TAG, "ESP32s no asignados encontrados: ${devices.size}")
            devices
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo ESP32s no asignados", e)
            emptyList()
        }
    }

    suspend fun getAssignedESP32s(clientId: String): List<ESP32Device> {
        return try {
            Log.d(TAG, "Obteniendo ESP32s asignados para cliente: $clientId")

            val snapshot = firestore.collection(ESP32_COLLECTION)
                .whereEqualTo("client_id", clientId)
                .whereIn("status", listOf(
                    ESP32Device.STATUS_ONLINE,
                    ESP32Device.STATUS_RUNNING,
                    ESP32Device.STATUS_OFFLINE
                ))
                .get()
                .await()

            val devices = snapshot.documents.mapNotNull { doc ->
                doc.toESP32Device()?.let { device ->
                    val clientName = doc.getString("client_name") ?: ""
                    val panelName = doc.getString("panel_name") ?: ""
                    device.copy(
                        clientName = clientName,
                        panelName = panelName
                    )
                }
            }

            Log.d(TAG, "ESP32s asignados encontrados: ${devices.size}")
            devices
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo ESP32s asignados", e)
            emptyList()
        }
    }

    fun observeESP32s(): Flow<List<ESP32Device>> = callbackFlow {
        var listenerRegistration: ListenerRegistration? = null

        try {
            Log.d(TAG, "Iniciando observación de ESP32s")

            val listenerId = "observe_all_esp32"
            activeListeners[listenerId]?.remove()

            listenerRegistration = firestore.collection(ESP32_COLLECTION)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error al observar ESP32s", error)
                        return@addSnapshotListener
                    }

                    val devices = snapshot?.documents?.mapNotNull { doc ->
                        doc.toESP32Device()
                    } ?: emptyList()

                    trySend(devices)
                }

            activeListeners[listenerId] = listenerRegistration
        } catch (e: Exception) {
            Log.e(TAG, "Error al configurar listener de ESP32", e)
            close(e)
        }

        awaitClose {
            listenerRegistration?.remove()
            activeListeners.remove("observe_all_esp32")
        }
    }

    fun observeESP32ByMac(mac: String): Flow<ESP32Device?> = callbackFlow {
        try {
            Log.d(TAG, "Buscando ESP32 con MAC: $mac")
            val normalizedMac = mac.uppercase().replace(":", "").replace("-", "")

            val listenerId = "observe_esp32_mac_$normalizedMac"
            activeListeners[listenerId]?.remove()

            val listenerRegistration = firestore.collection(ESP32_COLLECTION)
                .whereEqualTo("MAC", normalizedMac)
                .limit(1)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error observando ESP32 por MAC", error)
                        return@addSnapshotListener
                    }

                    val device = snapshot?.documents?.firstOrNull()?.toESP32Device()
                    if (device != null) {
                        Log.d(TAG, "ESP32 encontrado por MAC: ${device.documentName}")
                        trySend(device)
                    } else {
                        trySend(null)
                    }
                }

            activeListeners[listenerId] = listenerRegistration

            awaitClose {
                listenerRegistration.remove()
                activeListeners.remove(listenerId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando observador por MAC", e)
            close(e)
        }
    }

    fun observeUnassignedESP32s(deviceId: String? = null): Flow<List<ESP32Device>> = callbackFlow {
        try {
            Log.d(TAG, "Iniciando observación de ESP32s no asignados. DeviceId: $deviceId")

            val listenerId = "unassigned_${System.currentTimeMillis()}_${deviceId ?: "all"}"

            val baseQuery = firestore.collection(ESP32_COLLECTION)
                .whereIn("status", listOf(
                    ESP32Device.STATUS_AWAITING_CONFIG,
                    ESP32Device.STATUS_PENDING_ASSIGNMENT,
                    ESP32Device.STATUS_WIFI_CONFIG,
                    "CONFIG"
                ))

            val finalQuery = deviceId?.let { id ->
                Log.d(TAG, "Buscando ESP32 con ID: $id")
                baseQuery.whereEqualTo(FieldPath.documentId(), id)
            } ?: baseQuery

            val listenerRegistration = finalQuery
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error observando ESP32s", error)
                        return@addSnapshotListener
                    }

                    val devices = snapshot?.documents?.mapNotNull { doc ->
                        val device = doc.toESP32Device()
                        if (device != null) {
                            val clientId = doc.getString("client_id") ?: ""
                            val panelId = doc.getString("panel_id") ?: ""
                            val isUnassigned = clientId.isEmpty() && panelId.isEmpty()

                            if (isUnassigned) {
                                Log.d(TAG, "ESP32 sin asignar encontrado: ${doc.id}, Status: ${device.status}")
                                device
                            } else {
                                null
                            }
                        } else null
                    } ?: emptyList()

                    trySend(devices)
                }

            activeListeners[listenerId] = listenerRegistration

            awaitClose {
                listenerRegistration.remove()
                activeListeners.remove(listenerId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando listener", e)
            close(e)
        }
    }

    fun observeESP32Status(esp32Id: String): Flow<String> = callbackFlow {
        try {
            val listenerId = "observe_esp32_status_$esp32Id"
            activeListeners[listenerId]?.remove()

            val listenerRegistration = firestore.collection(ESP32_COLLECTION)
                .document(esp32Id)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error al observar estado de ESP32 $esp32Id", error)
                        return@addSnapshotListener
                    }

                    val status = snapshot?.getString("status") ?: "UNKNOWN"
                    Log.d(TAG, "Estado de ESP32 $esp32Id actualizado: $status")
                    trySend(status)
                }

            activeListeners[listenerId] = listenerRegistration

            awaitClose {
                listenerRegistration.remove()
                activeListeners.remove(listenerId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al configurar listener de estado de ESP32", e)
            close(e)
        }
    }

    fun observeAssignedESP32s(clientId: String? = null): Flow<List<ESP32Device>> = callbackFlow {
        try {
            Log.d(TAG, "Observando ESP32s asignados para cliente: $clientId")

            val listenerId = "assigned_${System.currentTimeMillis()}_${clientId ?: "admin"}"

            val listenerRegistration = if (clientId != null) {
                firestore.collection(ESP32_COLLECTION)
                    .whereEqualTo("client_id", clientId)
                    .whereIn("status", listOf(
                        ESP32Device.STATUS_ONLINE,
                        ESP32Device.STATUS_RUNNING,
                        ESP32Device.STATUS_OFFLINE
                    ))
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e(TAG, "Error observando ESP32s asignados para cliente", error)
                            return@addSnapshotListener
                        }

                        val devices = snapshot?.documents?.mapNotNull { doc ->
                            doc.toESP32Device()?.let { device ->
                                val clientName = doc.getString("client_name") ?: ""
                                val panelName = doc.getString("panel_name") ?: ""
                                device.copy(
                                    clientName = clientName,
                                    panelName = panelName
                                )
                            }
                        } ?: emptyList()

                        Log.d(TAG, "ESP32s asignados encontrados para cliente: ${devices.size}")
                        trySend(devices)
                    }
            } else {
                firestore.collection(ESP32_COLLECTION)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e(TAG, "Error observando ESP32s para admin", error)
                            return@addSnapshotListener
                        }

                        val devices = snapshot?.documents?.mapNotNull { doc ->
                            val device = doc.toESP32Device()
                            if (device != null) {
                                val deviceClientId = doc.getString("client_id") ?: ""
                                val deviceStatus = device.status

                                if (deviceClientId.isNotEmpty() &&
                                    deviceStatus in listOf(
                                        ESP32Device.STATUS_ONLINE,
                                        ESP32Device.STATUS_RUNNING,
                                        ESP32Device.STATUS_OFFLINE
                                    )) {
                                    val clientName = doc.getString("client_name") ?: ""
                                    val panelName = doc.getString("panel_name") ?: ""
                                    device.copy(
                                        clientName = clientName,
                                        panelName = panelName
                                    )
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        } ?: emptyList()

                        Log.d(TAG, "ESP32s asignados encontrados para admin: ${devices.size}")
                        trySend(devices)
                    }
            }

            activeListeners[listenerId] = listenerRegistration

            awaitClose {
                listenerRegistration.remove()
                activeListeners.remove(listenerId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando listener de ESP32s asignados", e)
            close(e)
        }
    }

    suspend fun sendRelayCommand(
        esp32Id: String,
        relayName: String,
        targetStatus: String
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Enviando comando de relay: ESP32=$esp32Id, Relay=$relayName, Estado=$targetStatus")

        val esp32Doc = firestore.document("$ESP32_COLLECTION/$esp32Id").get().await()
        if (!esp32Doc.exists()) {
            throw IllegalStateException("ESP32 no encontrado: $esp32Id")
        }

        val clientId = esp32Doc.getString("client_id")
        val panelId = esp32Doc.getString("panel_id")

        if (clientId.isNullOrEmpty() || panelId.isNullOrEmpty()) {
            throw IllegalStateException("ESP32 no está asignado a ningún panel")
        }

        val relayRef = firestore.document("$BASE_PATH/$clientId/panels/$panelId/relays/$relayName")
        val relayDoc = relayRef.get().await()

        if (!relayDoc.exists()) {
            throw IllegalStateException("Relay no encontrado: $relayName")
        }

        val updateData = mapOf(
            "status" to targetStatus,
            "lastUpdate" to Timestamp.now(),
            "commandSource" to "app",
            "lastCommandSent" to Timestamp.now()
        )

        relayRef.update(updateData).await()
        Log.d(TAG, "Comando de relay enviado exitosamente")
    }

    suspend fun updateRelayName(
        esp32Id: String,
        relayName: String,
        customName: String
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Actualizando nombre de relay: ESP32=$esp32Id, Relay=$relayName, Nuevo nombre=$customName")

        val esp32Doc = firestore.document("$ESP32_COLLECTION/$esp32Id").get().await()
        if (!esp32Doc.exists()) {
            throw IllegalStateException("ESP32 no encontrado: $esp32Id")
        }

        val clientId = esp32Doc.getString("client_id")
        val panelId = esp32Doc.getString("panel_id")

        if (clientId.isNullOrEmpty() || panelId.isNullOrEmpty()) {
            throw IllegalStateException("ESP32 no está asignado a ningún panel")
        }

        val relayRef = firestore.document("$BASE_PATH/$clientId/panels/$panelId/relays/$relayName")
        val updateData = mapOf(
            "customName" to customName.trim(),
            "lastUpdate" to Timestamp.now()
        )

        relayRef.update(updateData).await()
        Log.d(TAG, "Nombre de relay actualizado exitosamente")
    }

    suspend fun updateRelayActiveState(
        esp32Id: String,
        relayName: String,
        isActive: Boolean
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Actualizando estado activo de relay: ESP32=$esp32Id, Relay=$relayName, Activo=$isActive")

        val esp32Doc = firestore.document("$ESP32_COLLECTION/$esp32Id").get().await()
        if (!esp32Doc.exists()) {
            throw IllegalStateException("ESP32 no encontrado: $esp32Id")
        }

        val clientId = esp32Doc.getString("client_id")
        val panelId = esp32Doc.getString("panel_id")

        if (clientId.isNullOrEmpty() || panelId.isNullOrEmpty()) {
            throw IllegalStateException("ESP32 no está asignado a ningún panel")
        }

        val relayRef = firestore.document("$BASE_PATH/$clientId/panels/$panelId/relays/$relayName")
        val updateData = mapOf(
            "isActive" to isActive,
            "lastUpdate" to Timestamp.now()
        )

        relayRef.update(updateData).await()
        Log.d(TAG, "Estado activo de relay actualizado exitosamente")
    }

    private fun generateESP32Id(mac: String): String {
        val normalizedMAC = mac.uppercase().replace(":", "").replace("-", "")
        return normalizedMAC.takeLast(4) + "AC" + normalizedMAC.take(2)
    }

    suspend fun findByMAC(mac: String): ESP32Device? {
        return try {
            Log.d(TAG, "Searching for ESP32 with MAC: $mac")
            val normalizedMAC = mac.uppercase().replace(":", "")

            firestore.collection(ESP32_COLLECTION)
                .whereEqualTo("MAC", normalizedMAC)
                .get()
                .await()
                .documents
                .firstOrNull()
                ?.toESP32Device()
        } catch (e: Exception) {
            Log.e(TAG, "Error finding ESP32 by MAC", e)
            null
        }
    }

    suspend fun assignToPanelAndClient(
        esp32Id: String,
        clientId: String,
        panelId: String
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Assigning ESP32 $esp32Id to panel $panelId")

        val esp32Ref = firestore.document("$ESP32_COLLECTION/$esp32Id")
        val esp32Doc = esp32Ref.get().await()

        if (!esp32Doc.exists()) {
            throw IllegalStateException("ESP32 $esp32Id no encontrado")
        }

        esp32Doc.data?.let { currentData ->
            val currentClientId = currentData["client_id"] as? String
            val currentPanelId = currentData["panel_id"] as? String

            if (!currentClientId.isNullOrEmpty() && !currentPanelId.isNullOrEmpty()) {
                throw IllegalStateException("ESP32 ya está asignado a otro panel")
            }
        }

        val clientDoc = firestore.document("$BASE_PATH/$clientId").get().await()
        val panelDoc = firestore.document("$BASE_PATH/$clientId/panels/$panelId").get().await()

        val clientName = clientDoc.getString("name") ?: ""
        val panelName = panelDoc.getString("name") ?: ""

        val updateData = mapOf(
            "client_id" to clientId,
            "panel_id" to panelId,
            "clientName" to clientName,
            "panelName" to panelName,
            "status" to ESP32Device.STATUS_AWAITING_CONFIG,
            "lastUpdate" to Timestamp.now()
        )

        esp32Ref.set(updateData, SetOptions.merge()).await()
        Log.d(TAG, "ESP32 assigned successfully")
    }

    suspend fun updateNetworkInfo(esp32Id: String, ip: String, mac: String): Result<Unit> = runCatching {
        Log.d(TAG, "Updating network info for ESP32: $esp32Id")

        val esp32Ref = firestore.document("$ESP32_COLLECTION/$esp32Id")
        val doc = esp32Ref.get().await()

        val updateData = if (!doc.exists()) {
            mapOf(
                "MAC" to mac.uppercase().replace(":", ""),
                "IP" to ip,
                "status" to ESP32Device.STATUS_AWAITING_CONFIG,
                "client_id" to "",
                "panel_id" to "",
                "lastUpdate" to Timestamp.now()
            )
        } else {
            mapOf(
                "IP" to ip,
                "MAC" to mac.uppercase().replace(":", ""),
                "status" to ESP32Device.STATUS_AWAITING_CONFIG,
                "lastUpdate" to Timestamp.now()
            )
        }

        esp32Ref.set(updateData, SetOptions.merge()).await()
    }

    suspend fun updateStatus(esp32Id: String, status: String): Result<Unit> = runCatching {
        Log.d(TAG, "Updating status for ESP32: $esp32Id to $status")

        val updateData = mapOf(
            "status" to status,
            "lastUpdate" to Timestamp.now()
        )

        firestore.document("$ESP32_COLLECTION/$esp32Id")
            .update(updateData)
            .await()
    }

    suspend fun registerNewESP32(mac: String): Result<String> = runCatching {
        Log.d(TAG, "Registering new ESP32 with MAC: $mac")
        val normalizedMAC = mac.uppercase().replace(":", "")
        val esp32Id = generateESP32Id(normalizedMAC)

        val device = mapOf(
            "MAC" to normalizedMAC,
            "IP" to "",
            "status" to ESP32Device.STATUS_WIFI_CONFIG,
            "client_id" to "",
            "panel_id" to "",
            "lastUpdate" to Timestamp.now()
        )

        firestore.collection(ESP32_COLLECTION).document(esp32Id)
            .set(device)
            .await()

        Log.d(TAG, "ESP32 registered with ID: $esp32Id")
        esp32Id
    }

    suspend fun deleteESP32(esp32Id: String): Result<Unit> = runCatching {
        Log.d(TAG, "Deleting ESP32: $esp32Id from registered collection")

        val listenersToRemove = activeListeners.keys
            .filter { it.contains(esp32Id) }

        listenersToRemove.forEach { key ->
            activeListeners[key]?.remove()
            activeListeners.remove(key)
        }

        firestore.document("$ESP32_COLLECTION/$esp32Id")
            .delete()
            .await()

        Log.d(TAG, "ESP32 deleted successfully")
    }

    suspend fun unassignFromPanel(esp32Id: String): Result<Unit> = runCatching {
        Log.d(TAG, "Unassigning ESP32: $esp32Id")

        val updateData = mapOf(
            "client_id" to "",
            "panel_id" to "",
            "clientName" to "",
            "panelName" to "",
            "status" to ESP32Device.STATUS_AWAITING_CONFIG,
            "lastUpdate" to Timestamp.now()
        )

        firestore.document("$ESP32_COLLECTION/$esp32Id")
            .update(updateData)
            .await()

        Log.d(TAG, "ESP32 unassigned successfully")
    }

    fun clearListeners() {
        Log.d(TAG, "Limpiando todos los listeners de ESP32: ${activeListeners.size} listeners")

        val listenersSnapshot = activeListeners.toMap()
        activeListeners.clear()

        listenersSnapshot.forEach { (key, listener) ->
            try {
                listener.remove()
                Log.d(TAG, "Listener removido: $key")
            } catch (e: Exception) {
                Log.e(TAG, "Error al remover listener: $key", e)
            }
        }

        Log.d(TAG, "Listeners de ESP32 limpiados correctamente")
    }

    fun getListenerStats(): Map<String, Any> {
        return mapOf(
            "activeListeners" to activeListeners.size,
            "maxListeners" to MAX_LISTENERS,
            "listenersByClient" to activeListeners.keys.groupBy { key ->
                key.split("_").getOrNull(1) ?: "unknown"
            }.mapValues { it.value.size },
            "lastCleanup" to lastCleanup,
            "oldestListener" to if (activeListeners.isNotEmpty())
                System.currentTimeMillis() - 300000L else 0L
        )
    }
}