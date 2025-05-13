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
import com.pqsolutions.hdd_monitor.util.StatusUpdateManager
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
    }

    // Mapa para rastrear listeners activos
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

    fun observeESP32s(): Flow<List<ESP32Device>> = callbackFlow {
        var listenerRegistration: ListenerRegistration? = null

        try {
            Log.d(TAG, "Iniciando observación de ESP32s")

            // Limpiar listeners anteriores con el mismo propósito
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

            // Registrar el nuevo listener
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

            // Limpiar listeners anteriores
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

            // Registrar el nuevo listener
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

            // Limpiar listeners anteriores
            val listenerId = "observe_unassigned_esp32" + (deviceId ?: "")
            activeListeners[listenerId]?.remove()

            // Usar whereIn para status y eliminar el filtro de client_id
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
                        // Filtrar aquí los dispositivos que no tienen client_id o panel_id asignados
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
                    } ?: emptyList()

                    trySend(devices)
                }

            // Registrar el nuevo listener
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

                    // Emitir actualización para panel asociado
                    if (snapshot != null && snapshot.exists()) {
                        val clientId = snapshot.getString("client_id") ?: ""
                        val panelId = snapshot.getString("panel_id") ?: ""

                        if (clientId.isNotEmpty() && panelId.isNotEmpty()) {
                            // Notificar cambio de estado ESP32
                            StatusUpdateManager.emitEsp32StatusUpdateSync(panelId, status)

                            if (status == ESP32Device.STATUS_RUNNING) {
                                // Si está en estado RUNNING, observar también los relays
                                observeRelayStates(clientId, panelId)
                            }
                        }
                    }
                }

            // Registrar el nuevo listener
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

    private fun observeRelayStates(clientId: String, panelId: String) {
        val listenerId = "relays_${clientId}_${panelId}"

        // Verificar si ya existe un listener
        if (activeListeners.containsKey(listenerId)) {
            return
        }

        Log.d(TAG, "Configurando listener de relays para panel $panelId")

        val relaysRef = firestore
            .collection("hdd-monitor/accounts/clients")
            .document(clientId)
            .collection("panels")
            .document(panelId)
            .collection("relays")

        val registration = relaysRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error observando relays", error)
                return@addSnapshotListener
            }

            snapshot?.documentChanges?.forEach { change ->
                val relay = change.document
                val relayName = relay.id
                val status = relay.getString("status") ?: "UNKNOWN"
                Log.d(TAG, "Cambio en relay $relayName: $status")

                // Emitir actualización
                StatusUpdateManager.emitRelayStatusUpdateSync(panelId, relayName, status)
            }
        }

        activeListeners[listenerId] = registration
    }

    private fun generateESP32Id(mac: String): String {
        val normalizedMAC = mac.uppercase().replace(":", "").replace("-", "")
        return normalizedMAC.takeLast(4) + "AC" + normalizedMAC.take(2)
    }

    suspend fun findByMAC(mac: String): ESP32Device? {
        return try {
            Log.d(TAG, "Searching for ESP32 with MAC: $mac")
            val normalizedMAC = mac.uppercase().replace(":", "")

            // Usar Source.SERVER para forzar consulta reciente
            firestore.collection(ESP32_COLLECTION)
                .whereEqualTo("MAC", normalizedMAC)
                .get(Source.SERVER)
                .await()
                .documents
                .firstOrNull()
                ?.toESP32Device()
        } catch (e: Exception) {
            Log.e(TAG, "Error finding ESP32 by MAC", e)
            try {
                // Intentar con caché si falla servidor
                val normalizedMAC = mac.uppercase().replace(":", "")
                firestore.collection(ESP32_COLLECTION)
                    .whereEqualTo("MAC", normalizedMAC)
                    .get(Source.CACHE)
                    .await()
                    .documents
                    .firstOrNull()
                    ?.toESP32Device()
            } catch (e2: Exception) {
                Log.e(TAG, "Error accessing cache", e2)
                null
            }
        }
    }

    suspend fun assignToPanelAndClient(
        esp32Id: String,
        clientId: String,
        panelId: String
    ): Result<Unit> = runCatching {
        Log.d(TAG, "Assigning ESP32 $esp32Id to panel $panelId")

        val esp32Ref = firestore.document("$ESP32_COLLECTION/$esp32Id")
        val esp32Doc = esp32Ref.get(Source.SERVER).await()

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

        val updateData = mapOf(
            "client_id" to clientId,
            "panel_id" to panelId,
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

        // Limpiar cualquier listener asociado
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
            "status" to ESP32Device.STATUS_AWAITING_CONFIG,
            "lastUpdate" to Timestamp.now()
        )

        firestore.document("$ESP32_COLLECTION/$esp32Id")
            .update(updateData)
            .await()

        Log.d(TAG, "ESP32 unassigned successfully")
    }

    /**
     * Limpia todos los listeners activos
     */
    fun clearListeners() {
        Log.d(TAG, "Limpiando todos los listeners de ESP32: ${activeListeners.size} listeners")

        // Copiar las claves para evitar ConcurrentModificationException
        val keys = ArrayList(activeListeners.keys)

        // Remover cada listener
        keys.forEach { key ->
            try {
                activeListeners[key]?.remove()
                activeListeners.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Error al remover listener: $key", e)
            }
        }

        Log.d(TAG, "Listeners de ESP32 limpiados correctamente")
    }
}