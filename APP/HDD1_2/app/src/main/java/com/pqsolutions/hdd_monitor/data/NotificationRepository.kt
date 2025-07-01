package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.pqsolutions.hdd_monitor.data.util.IdManager
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.util.Constants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val userRepository: UserRepository
) {
    companion object {
        private const val TAG = "NotificationRepository"
        private const val BASE_PATH = "hdd-monitor/accounts/clients"
        private const val DATE_FORMAT = "dd/MM/yyyy, HH:mm"
        private const val MAX_NOTIFICATIONS = 20
        private const val HOURS_TO_KEEP = 24L
        private const val MAX_LISTENERS = 20
        private var lastCleanup = 0L
    }

    private val activeListeners = mutableListOf<ListenerRegistration>()

    fun clearListeners() {
        Log.d(TAG, "Clearing notification listeners (active: ${activeListeners.size})")
        synchronized(activeListeners) {
            activeListeners.forEach { listener ->
                try {
                    listener.remove()
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing listener", e)
                }
            }
            activeListeners.clear()
        }
    }

    fun getNotificationsFlow(clientDocName: String): Flow<List<Notification>> = callbackFlow {
        if (clientDocName.isBlank()) {
            Log.w(TAG, "Intento de obtener notificaciones con clientDocName vacío")
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val collectionPath = "$BASE_PATH/$clientDocName/notifications"
        Log.d(TAG, "Consultando notificaciones en: $collectionPath")

        val notificationsRef = firestore.collection(collectionPath)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(MAX_NOTIFICATIONS.toLong())

        val listenerRegistration = notificationsRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                if (error.message?.contains("PERMISSION_DENIED") == true) {
                    Log.w(TAG, "Permission denied for notifications, cleaning up")
                    clearListeners()
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                Log.e(TAG, "Error getting notifications", error)
                return@addSnapshotListener
            }

            snapshot?.let { querySnapshot ->
                try {
                    val documents = querySnapshot.documents
                    Log.d(TAG, "Documentos encontrados: ${documents.size}")

                    if (documents.isEmpty()) {
                        Log.d(TAG, "No se encontraron documentos en la colección")
                        trySend(emptyList())
                        return@addSnapshotListener
                    }

                    val notifications = documents.mapNotNull { doc ->
                        try {
                            val data = doc.data ?: emptyMap()
                            val notificationMap = data.toMutableMap().apply {
                                this["documentName"] = doc.id
                                this["clientDocName"] = clientDocName
                                if (!containsKey("timestamp")) {
                                    val timestampValue = doc.getTimestamp("lastUpdate")?.toDate()?.time
                                        ?: System.currentTimeMillis()
                                    this["timestamp"] = timestampValue
                                }
                            }

                            val notification = Notification.fromMap(notificationMap)
                            if (notification.isValid()) notification else null
                        } catch (e: Exception) {
                            Log.e(TAG, "Error procesando documento ${doc.id}", e)
                            null
                        }
                    }

                    Log.d(TAG, "Enviando ${notifications.size} notificaciones al flow")
                    trySend(notifications)
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando snapshot", e)
                    trySend(emptyList())
                }
            } ?: run {
                Log.w(TAG, "Snapshot nulo recibido")
                trySend(emptyList())
            }
        }

        synchronized(activeListeners) {
            activeListeners.add(listenerRegistration)
        }

        awaitClose {
            Log.d(TAG, "Closing notification listener")
            try {
                listenerRegistration.remove()
                synchronized(activeListeners) {
                    activeListeners.remove(listenerRegistration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing listener", e)
            }
        }
    }

    fun getNotificationsFlow(): Flow<List<Notification>> = callbackFlow {
        Log.d(TAG, "Iniciando consulta de todas las notificaciones (admin)")

        val listenerRegistration = firestore.collectionGroup("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(MAX_NOTIFICATIONS.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.message?.contains("PERMISSION_DENIED") == true) {
                        Log.d(TAG, "Permisos denegados, limpiando listeners")
                        clearListeners()
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    Log.e(TAG, "Error getting all notifications", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                snapshot?.let { querySnapshot ->
                    try {
                        val documents = querySnapshot.documents
                        Log.d(TAG, "Documentos encontrados (admin): ${documents.size}")

                        val notifications = documents.mapNotNull { doc ->
                            try {
                                val data = doc.data ?: emptyMap()
                                val clientDocName = doc.reference.path
                                    .split("/")
                                    .let { parts ->
                                        parts.getOrNull(parts.indexOf("clients") + 1) ?: ""
                                    }

                                val notificationMap = data.toMutableMap().apply {
                                    this["documentName"] = doc.id
                                    this["clientDocName"] = clientDocName
                                }

                                val notification = Notification.fromMap(notificationMap)
                                if (notification.isValid()) notification else null
                            } catch (e: Exception) {
                                Log.e(TAG, "Error procesando documento admin ${doc.id}", e)
                                null
                            }
                        }

                        Log.d(TAG, "Enviando ${notifications.size} notificaciones admin al flow")
                        trySend(notifications)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error procesando snapshot (admin)", e)
                        trySend(emptyList())
                    }
                } ?: run {
                    Log.w(TAG, "Snapshot nulo recibido (admin)")
                    trySend(emptyList())
                }
            }

        synchronized(activeListeners) {
            activeListeners.add(listenerRegistration)
        }

        awaitClose {
            Log.d(TAG, "Cerrando listener de notificaciones globales")
            try {
                listenerRegistration.remove()
                synchronized(activeListeners) {
                    activeListeners.remove(listenerRegistration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cerrando listener", e)
            }
        }
    }

    suspend fun cleanupOldNotifications(clientDocName: String) {
        try {
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - (HOURS_TO_KEEP * 60 * 60 * 1000)

            deleteNotificationsOlderThan(clientDocName, cutoffTime)
            keepOnlyLastN(clientDocName, MAX_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Error en cleanup de notificaciones", e)
        }
    }

    suspend fun deleteNotificationsOlderThan(clientDocName: String, timestamp: Long): Result<Unit> = runCatching {
        val batch = firestore.batch()
        val notifications = firestore.collection("$BASE_PATH/$clientDocName/notifications")
            .whereLessThan("timestamp", timestamp)
            .get()
            .await()

        notifications.documents.forEach { doc ->
            batch.delete(doc.reference)
        }

        batch.commit().await()
        Log.d(TAG, "Deleted ${notifications.size()} old notifications for client: $clientDocName")
    }

    suspend fun keepOnlyLastN(clientDocName: String, n: Int): Result<Unit> = runCatching {
        Log.d(TAG, "Manteniendo solo últimas $n notificaciones para cliente: $clientDocName")

        val notifications = firestore.collection("$BASE_PATH/$clientDocName/notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()

        if (notifications.size() > n) {
            val batch = firestore.batch()
            val toDelete = notifications.documents.drop(n)

            toDelete.forEach { doc ->
                batch.delete(doc.reference)
            }

            batch.commit().await()
            Log.d(TAG, "Eliminadas ${toDelete.size} notificaciones antiguas para mantener solo $n")
        } else {
            Log.d(TAG, "No hay notificaciones para eliminar, solo hay ${notifications.size()}")
        }
    }

    suspend fun markNotificationAsRead(
        clientDocName: String,
        notificationDocName: String
    ): Result<Unit> = runCatching {
        firestore.document("$BASE_PATH/$clientDocName/notifications/$notificationDocName")
            .update("isRead", true)
            .await()
        Log.d(TAG, "Notification marked as read: $notificationDocName")
    }

    suspend fun markAllNotificationsAsRead(clientDocName: String, isAdmin: Boolean = false): Result<Unit> = runCatching {
        if (isAdmin) {
            val batch = firestore.batch()
            val notifications = firestore.collectionGroup("notifications")
                .whereEqualTo("isRead", false)
                .get()
                .await()

            notifications.documents.forEach { doc ->
                batch.update(doc.reference, mapOf(
                    "isRead" to true,
                    "readByAdmin" to true
                ))
            }
            batch.commit().await()
        } else {
            val batch = firestore.batch()
            val notifications = firestore.collection("$BASE_PATH/$clientDocName/notifications")
                .whereEqualTo("isRead", false)
                .get()
                .await()

            notifications.documents.forEach { doc ->
                batch.update(doc.reference, "isRead", true)
            }
            batch.commit().await()
        }
    }

    suspend fun createRelayNotification(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        oldStatus: String,
        newStatus: String,
        commandSource: String = "esp32"
    ): Result<Unit> = runCatching {
        val notificationDocName = IdManager.generateNotificationDocumentName("relay_$relayName", clientDocName)
        val now = LocalDateTime.now(Constants.TimeZone.PERU_ZONE)

        val panelName = try {
            val panelDoc = firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                .get()
                .await()
            panelDoc.getString("name") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener nombre del panel: $panelDocName", e)
            ""
        }

        val message = when (commandSource) {
            "app" -> "Relay $relayName del panel \"$panelName\" cambiado remotamente de $oldStatus a $newStatus"
            "web" -> "Relay $relayName del panel \"$panelName\" cambiado desde web de $oldStatus a $newStatus"
            else -> "El relay $relayName del panel \"$panelName\" ha cambiado de $oldStatus a $newStatus"
        }

        val notificationData = hashMapOf(
            "type" to "relay",
            "panelDocName" to panelDocName,
            "panel_id" to panelDocName,
            "panel_name" to panelName,
            "relayName" to relayName,
            "relay" to relayName,
            "old_status" to oldStatus,
            "state" to newStatus,
            "commandSource" to commandSource,
            "message" to message,
            "date_time" to now.format(DateTimeFormatter.ofPattern(DATE_FORMAT)),
            "timestamp" to now.atZone(Constants.TimeZone.PERU_ZONE)
                .toInstant()
                .toEpochMilli(),
            "isRead" to false,
            "readByAdmin" to false
        )

        firestore.collection("$BASE_PATH/$clientDocName/notifications")
            .document(notificationDocName)
            .set(notificationData)
            .await()

        Log.d(TAG, "Relay notification created: $notificationDocName with source: $commandSource")
    }

    suspend fun createNotification(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        message: String
    ): Result<Unit> = runCatching {
        val notificationDocName = IdManager.generateNotificationDocumentName(message, clientDocName)
        val now = LocalDateTime.now(Constants.TimeZone.PERU_ZONE)

        val panelName = try {
            val panelDoc = firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                .get()
                .await()
            panelDoc.getString("name") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener nombre del panel: $panelDocName", e)
            ""
        }

        val notificationData = hashMapOf(
            "panelDocName" to panelDocName,
            "panel_id" to panelDocName,
            "panel_name" to panelName,
            "relayName" to relayName,
            "message" to message,
            "date_time" to now.format(DateTimeFormatter.ofPattern(DATE_FORMAT)),
            "timestamp" to now.atZone(Constants.TimeZone.PERU_ZONE)
                .toInstant()
                .toEpochMilli(),
            "isRead" to false
        )

        firestore.collection("$BASE_PATH/$clientDocName/notifications")
            .document(notificationDocName)
            .set(notificationData)
            .await()

        Log.d(TAG, "Notification created: $notificationDocName with panel name: $panelName")
    }

    suspend fun deleteNotification(
        clientDocName: String,
        notificationDocName: String
    ): Result<Unit> = runCatching {
        firestore.document("$BASE_PATH/$clientDocName/notifications/$notificationDocName")
            .delete()
            .await()

        Log.d(TAG, "Notification deleted: $notificationDocName")
    }

    fun getListenerStats(): Map<String, Any> {
        return mapOf(
            "activeListeners" to activeListeners.size,
            "maxListeners" to MAX_LISTENERS,
            "listenersByClient" to mapOf<String, Int>(),
            "lastCleanup" to lastCleanup
        )
    }
}