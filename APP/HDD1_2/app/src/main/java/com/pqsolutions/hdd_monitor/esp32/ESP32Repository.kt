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
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
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
    }

    private val activeListeners = ConcurrentHashMap<String, ListenerRegistration>()

    private fun DocumentSnapshot.toESP32Device(): ESP32Device? {
        return try {
            if (!exists()) return null
            toObject(ESP32Device::class.java)?.copy(documentName = id)
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
                    if (isUnassigned) device else null
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
                    device.copy(clientName = clientName, panelName = panelName)
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
        Log.d(TAG, "Iniciando observación de todos los ESP32s")
        val listenerId = "observe_all_esp32_global"
        activeListeners[listenerId]?.remove()

        val listenerRegistration = firestore.collection(ESP32_COLLECTION)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error al observar ESP32s", error)
                    return@addSnapshotListener
                }

                val devices = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toESP32Device()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error converting ESP32 document ${doc.id}", e)
                        null
                    }
                } ?: emptyList()

                Log.d(TAG, "ESP32s actualizados: ${devices.size} dispositivos")
                devices.forEach { device ->
                    Log.d(TAG, "ESP32: ${device.documentName}, Status: ${device.status}")
                }
                trySend(devices)
            }

        activeListeners[listenerId] = listenerRegistration

        awaitClose {
            Log.d(TAG, "Cerrando observación global de ESP32s")
            listenerRegistration.remove()
            activeListeners.remove(listenerId)
        }
    }.flowOn(Dispatchers.IO)

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
                baseQuery.whereEqualTo(FieldPath.documentId(), id)
            } ?: baseQuery

            val listenerRegistration = finalQuery
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
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
                            if (isUnassigned) device else null
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
    }.flowOn(Dispatchers.IO)

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
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        if (error != null) {
                            Log.e(TAG, "Error observando ESP32s asignados para cliente", error)
                            return@addSnapshotListener
                        }

                        val devices = snapshot?.documents?.mapNotNull { doc ->
                            doc.toESP32Device()?.let { device ->
                                val clientName = doc.getString("client_name") ?: ""
                                val panelName = doc.getString("panel_name") ?: ""
                                device.copy(clientName = clientName, panelName = panelName)
                            }
                        } ?: emptyList()

                        trySend(devices)
                    }
            } else {
                firestore.collection(ESP32_COLLECTION)
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
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
                                    device.copy(clientName = clientName, panelName = panelName)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        } ?: emptyList()

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
    }.flowOn(Dispatchers.IO)

    suspend fun assignToPanelAndClient(esp32Id: String, clientId: String, panelId: String): Result<Unit> = runCatching {
        Log.d(TAG, "Assigning ESP32 $esp32Id to panel $panelId")
        val esp32Ref = firestore.document("$ESP32_COLLECTION/$esp32Id")
        val esp32Doc = esp32Ref.get().await()

        if (!esp32Doc.exists()) {
            throw IllegalStateException("ESP32 $esp32Id no encontrado")
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

    suspend fun updateStatus(esp32Id: String, status: String): Result<Unit> = runCatching {
        Log.d(TAG, "Updating status for ESP32: $esp32Id to $status")
        val updateData = mapOf(
            "status" to status,
            "lastUpdate" to Timestamp.now()
        )
        firestore.document("$ESP32_COLLECTION/$esp32Id").update(updateData).await()
    }

    suspend fun deleteESP32(esp32Id: String): Result<Unit> = runCatching {
        Log.d(TAG, "Deleting ESP32: $esp32Id from registered collection")
        val listenersToRemove = activeListeners.keys.filter { it.contains(esp32Id) }
        listenersToRemove.forEach { key ->
            activeListeners[key]?.remove()
            activeListeners.remove(key)
        }
        firestore.document("$ESP32_COLLECTION/$esp32Id").delete().await()
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
        firestore.document("$ESP32_COLLECTION/$esp32Id").update(updateData).await()
        Log.d(TAG, "ESP32 unassigned successfully")
    }

    fun clearListeners() {
        Log.d(TAG, "Limpiando listeners de ESP32: ${activeListeners.size} listeners")
        val listenersSnapshot = activeListeners.toMap()
        activeListeners.clear()
        listenersSnapshot.forEach { (key, listener) ->
            try {
                listener.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Error al remover listener: $key", e)
            }
        }
        Log.d(TAG, "Listeners de ESP32 limpiados correctamente")
    }

    fun clearStaleListeners() {
        Log.d(TAG, "Limpiando solo listeners obsoletos de ESP32")
        val staleKeys = activeListeners.keys.filter { key ->
            key.contains("_old_") || key.contains("_temp_") || key.contains("_duplicate_")
        }
        staleKeys.forEach { key ->
            try {
                activeListeners[key]?.remove()
                activeListeners.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing stale ESP32 listener: $key", e)
            }
        }
        Log.d(TAG, "Limpieza selectiva completada: ${staleKeys.size} listeners obsoletos removidos")
    }

    fun getListenerStats(): Map<String, Any> {
        return mapOf(
            "activeListeners" to activeListeners.size,
            "maxListeners" to MAX_LISTENERS,
            "listenersByType" to activeListeners.keys.groupBy { key ->
                when {
                    key.contains("observe_all") -> "global_observers"
                    key.contains("mac_") -> "mac_observers"
                    key.contains("status_") -> "status_observers"
                    key.contains("unassigned") -> "unassigned_observers"
                    key.contains("assigned") -> "assigned_observers"
                    else -> "other"
                }
            }.mapValues { it.value.size }
        )
    }

    fun forceRestart() {
        Log.d(TAG, "Forzando reinicio completo de ESP32Repository")
        val oldListeners = activeListeners.toMap()
        activeListeners.clear()
        oldListeners.values.forEach { listener ->
            try {
                listener.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener during restart", e)
            }
        }
        Log.d(TAG, "ESP32Repository reiniciado - ${oldListeners.size} listeners removidos")
    }
}