package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.pqsolutions.hdd_monitor.data.util.IdManager
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.util.Constants
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val userRepository: UserRepository,
    private var lastProcessedTimestamp: Long = 0L,
    private val documentProcessCache: MutableSet<String> = mutableSetOf<String>(),
    private val PROCESS_THROTTLE_TIME: Long = 2000L
) {
    companion object {
        private const val TAG = "NotificationRepository"
        private const val BASE_PATH = "hdd-monitor/accounts/clients"
        private const val DATE_FORMAT = "dd/MM/yyyy, HH:mm"  // Formato unificado
        private const val MAX_NOTIFICATIONS = 20
        private const val HOURS_TO_KEEP = 24L

        // NUNCA mostrar notificaciones visuales desde aquí
        private const val SHOW_VISUAL_NOTIFICATIONS = false
    }

    // Flow principal de notificaciones para un cliente específico (usuarios)
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

    private fun shouldProcessSnapshot(documents: List<com.google.firebase.firestore.DocumentSnapshot>, clientId: String): Boolean {
        if (documents.isEmpty()) return false

        // Crear una huella digital del snapshot
        val snapshotSignature = documents.take(3).joinToString("|") { it.id } + "|" + clientId
        val currentTimestamp = System.currentTimeMillis()

        // Si una firma similar fue procesada recientemente, ignorar
        if (documentProcessCache.contains(snapshotSignature) &&
            currentTimestamp - lastProcessedTimestamp < PROCESS_THROTTLE_TIME) {
            Log.d(TAG, "Ignorando snapshot duplicado: $snapshotSignature")
            return false
        }

        // Actualizar caché
        documentProcessCache.add(snapshotSignature)
        lastProcessedTimestamp = currentTimestamp

        // Limpiar caché si crece demasiado
        if (documentProcessCache.size > 10) {
            val oldestEntries = documentProcessCache.take(documentProcessCache.size - 5)
            documentProcessCache.removeAll(oldestEntries.toSet())
        }

        return true
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

        // Usar un mapa para cachear notificaciones
        val notificationsCache = mutableMapOf<String, Notification>()

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

                    // Verificar si debemos procesar este snapshot o es un duplicado
                    if (!shouldProcessSnapshot(documents, clientDocName)) {
                        return@addSnapshotListener
                    }

                    Log.d(TAG, "Primer documento: ${documents[0].id}")

                    val notifications = documents.mapNotNull { doc ->
                        try {
                            val data = doc.data ?: emptyMap()
                            // Log para ver qué datos llegan
                            Log.d(TAG, "Datos de documento ${doc.id}: ${data.keys}")

                            val notificationMap = data.toMutableMap().apply {
                                this["documentName"] = doc.id
                                this["clientDocName"] = clientDocName
                                if (!containsKey("timestamp")) {
                                    val timestampValue = doc.getTimestamp("lastUpdate")?.toDate()?.time
                                        ?: System.currentTimeMillis()
                                    this["timestamp"] = timestampValue
                                    Log.d(TAG, "Generado timestamp para ${doc.id}: $timestampValue")
                                }
                            }

                            // Verificar validez de la notificación
                            val notification = Notification.fromMap(notificationMap)
                            if (!notification.isValid()) {
                                Log.w(TAG, "Notificación inválida: ${doc.id}")
                                null
                            } else {
                                // Cachear la notificación válida
                                notificationsCache[doc.id] = notification
                                notification
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error procesando documento ${doc.id}", e)
                            null
                        }
                    }

                    // IMPORTANTE: Verificar que realmente estamos enviando notificaciones
                    Log.d(TAG, "Enviando ${notifications.size} notificaciones al flow")
                    if (notifications.isNotEmpty()) {
                        trySend(notifications)
                    } else {
                        // Agregamos una rama else explícita para mayor claridad
                        Log.d(TAG, "No hay notificaciones válidas para enviar")
                    }
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

    // Flow para todas las notificaciones (para administradores)
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

                        // Verificar si debemos procesar este snapshot o es un duplicado
                        if (documents.isEmpty() || !shouldProcessSnapshot(documents, "admin")) {
                            return@addSnapshotListener
                        }

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
                                // Solo enviar notificaciones válidas
                                if (notification.isValid()) notification else null
                            } catch (e: Exception) {
                                Log.e(TAG, "Error procesando documento admin ${doc.id}", e)
                                null
                            }
                        }

                        if (notifications.isNotEmpty()) {
                            Log.d(TAG, "Enviando ${notifications.size} notificaciones admin al flow")
                            trySend(notifications)
                        } else {
                            Log.d(TAG, "No hay notificaciones válidas para enviar (admin)")
                        }
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

    private var lastCleanupTime: Long = 0
    private val CLEANUP_INTERVAL = 60 * 60 * 1000 // 1 hora

    private suspend fun cleanupOldNotifications(clientDocName: String) {
        val currentTime = System.currentTimeMillis()
        // CORREGIDO: Ejecutar limpieza más agresivamente
        if (currentTime - lastCleanupTime < CLEANUP_INTERVAL / 2) {
            return  // Evitar limpiezas demasiado frecuentes
        }

        try {
            Log.d(TAG, "Iniciando limpieza de notificaciones para cliente: $clientDocName")
            val cutoffTime = currentTime - (HOURS_TO_KEEP * 60 * 60 * 1000)

            coroutineScope {
                // Primero mantener solo las últimas MAX_NOTIFICATIONS
                launch {
                    keepOnlyLastN(clientDocName, MAX_NOTIFICATIONS)
                        .onSuccess { Log.d(TAG, "Mantenidas últimas $MAX_NOTIFICATIONS notificaciones") }
                        .onFailure { e -> Log.e(TAG, "Error manteniendo últimas notificaciones", e) }
                }

                // Luego eliminar notificaciones más antiguas que HOURS_TO_KEEP
                launch {
                    deleteNotificationsOlderThan(clientDocName, cutoffTime)
                        .onSuccess { Log.d(TAG, "Eliminadas notificaciones más antiguas que $HOURS_TO_KEEP horas") }
                        .onFailure { e -> Log.e(TAG, "Error limpiando notificaciones antiguas", e) }
                }
            }

            lastCleanupTime = currentTime
            Log.d(TAG, "Limpieza de notificaciones completada para: $clientDocName")
        } catch (e: Exception) {
            Log.e(TAG, "Error en cleanup de notificaciones", e)
        }
    }

    private suspend fun cleanupAllClientsOldNotifications() {
        try {
            val clients = firestore.collection(BASE_PATH)
                .get()
                .await()

            clients.documents.forEach { clientDoc ->
                cleanupOldNotifications(clientDoc.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en cleanup de todos los clientes", e)
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
            // Para admins, marcar todas las notificaciones como leídas pero mantener el estado separado
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
            // Para usuarios, solo marcar las de su cliente
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

    suspend fun updateFcmToken(userDocName: String, newToken: String): Result<Unit> = runCatching {
        val user = userRepository.getCurrentUser() ?:
        throw IllegalStateException("Usuario no encontrado")

        val collectionPath = when (user.role) {
            UserRole.ADMIN -> "hdd-monitor/accounts/admins"
            UserRole.USER -> "$BASE_PATH/${user.clientDocName}/users"
        }

        firestore.collection(collectionPath)
            .document(userDocName)
            .update("fcmToken", newToken)
            .await()

        Log.d(TAG, "Token FCM actualizado: $userDocName")
    }

    // NUEVO: Crear notificación para cambios de relay con información de comando
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

        // Obtener el nombre real del panel
        val panelName = try {
            val panelDoc = firestore.document("$BASE_PATH/$clientDocName/panels/$panelDocName")
                .get()
                .await()
            panelDoc.getString("name") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener nombre del panel: $panelDocName", e)
            ""
        }

        // Crear mensaje dependiendo del origen del cambio
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

        // Obtener el nombre real del panel
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
            "panel_name" to panelName,  // Guardar el nombre real del panel
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
}