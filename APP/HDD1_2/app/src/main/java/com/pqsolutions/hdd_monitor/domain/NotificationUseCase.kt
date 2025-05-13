package com.pqsolutions.hdd_monitor.domain

import android.util.Log
import com.pqsolutions.hdd_monitor.data.Notification
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class NotificationUseCase @Inject constructor(
    private val notificationRepository: NotificationRepository
) {
    private val TAG = "NotificationUseCase"
    private val MAX_NOTIFICATIONS = 20

    // Obtener flujo de notificaciones para un cliente específico
    fun getNotificationsFlow(clientDocName: String): Flow<List<Notification>> =
        notificationRepository.getNotificationsFlow(clientDocName)

    // Obtener flujo de todas las notificaciones (para administradores)
    fun getAllNotificationsFlow(): Flow<List<Notification>> =
        notificationRepository.getNotificationsFlow()

    // Crear una nueva notificación
    suspend fun createNotification(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        message: String
    ): Result<Unit> =
        notificationRepository.createNotification(
            clientDocName = clientDocName,
            panelDocName = panelDocName,
            relayName = relayName,
            message = message
        )

    // Eliminar una notificación
    suspend fun deleteNotification(
        clientDocName: String,
        notificationDocName: String
    ): Result<Unit> =
        notificationRepository.deleteNotification(clientDocName, notificationDocName)

    // Eliminar notificaciones antiguas
    suspend fun deleteNotificationsOlderThan(
        clientDocName: String,
        timestamp: Long
    ): Result<Unit> =
        notificationRepository.deleteNotificationsOlderThan(clientDocName, timestamp)

    // Marcar notificación como leída
    suspend fun markNotificationAsRead(
        clientDocName: String,
        notificationDocName: String
    ): Result<Unit> =
        notificationRepository.markNotificationAsRead(clientDocName, notificationDocName)

    // Marcar todas las notificaciones como leídas
    suspend fun markAllNotificationsAsRead(
        clientDocName: String,
        isAdmin: Boolean = false
    ): Result<Unit> =
        notificationRepository.markAllNotificationsAsRead(clientDocName, isAdmin)

    // Mantener solo las últimas N notificaciones
    suspend fun keepOnlyLastN(
        clientDocName: String,
        n: Int = MAX_NOTIFICATIONS
    ): Result<Unit> {
        Log.d(TAG, "Manteniendo solo últimas $n notificaciones para cliente: $clientDocName")
        return notificationRepository.keepOnlyLastN(clientDocName, n)
    }

    // Método para realizar limpieza inicial de notificaciones
    suspend fun performInitialCleanup(clientDocName: String): Result<Unit> {
        Log.d(TAG, "Realizando limpieza inicial para cliente: $clientDocName")
        return keepOnlyLastN(clientDocName, MAX_NOTIFICATIONS)
    }
}