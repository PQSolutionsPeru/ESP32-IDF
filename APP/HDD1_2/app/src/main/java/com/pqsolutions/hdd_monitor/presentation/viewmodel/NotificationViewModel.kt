package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.data.Notification
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "NotificationViewModel"

data class NotificationUiState(
    val notifications: List<NotificationItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasUnreadNotifications: Boolean = false,
    val pendingCount: Int = 0
)

enum class NotificationType {
    EVENT, RELAY
}

enum class NotificationStatus {
    PROGRAMADO, ACEPTADO, FINALIZADO;

    companion object {
        fun fromString(status: String): NotificationStatus {
            return when (status.uppercase()) {
                "PROGRAMADO" -> PROGRAMADO
                "ACEPTADO" -> ACEPTADO
                "FINALIZADO" -> FINALIZADO
                else -> PROGRAMADO
            }
        }
    }
}

data class NotificationItem(
    val documentName: String,
    val clientDocName: String,
    val title: String,
    val text: String,
    val date_time: String,
    val timestamp: Long,
    val status: NotificationStatus = NotificationStatus.PROGRAMADO,
    val notificationType: NotificationType,
    val panelDocName: String? = null,
    val panelName: String? = null,
    val relayName: String? = null,
    val eventId: String? = null,
    val eventType: String? = null,
    val isRead: Boolean = false
)

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    init {
        loadNotifications()
    }

    private fun mapToNotificationItem(notification: Notification): NotificationItem {
        val notificationType = if (notification.isEventNotification()) {
            NotificationType.EVENT
        } else {
            NotificationType.RELAY
        }

        val title = "HDD Monitor"
        val text = notification.getDisplayMessage()

        val status = notification.status?.let {
            try {
                NotificationStatus.fromString(it)
            } catch (e: Exception) {
                NotificationStatus.PROGRAMADO
            }
        } ?: NotificationStatus.PROGRAMADO

        return NotificationItem(
            documentName = notification.documentName,
            clientDocName = notification.clientDocName,
            title = title,
            text = text,
            date_time = notification.date_time,
            timestamp = notification.timestamp,
            status = status,
            notificationType = notificationType,
            panelDocName = notification.panelDocName,
            panelName = notification.panelName,
            relayName = notification.relayName,
            eventId = notification.eventId,
            eventType = notification.eventType,
            isRead = notification.isRead
        )
    }

    fun setLoading(isLoading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = isLoading)
    }

    private fun loadNotifications() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val clientDocName = currentUser.clientDocName
                    Log.d(TAG, "Cargando notificaciones para cliente: $clientDocName")

                    val notificationsFlow = when (currentUser.role) {
                        UserRole.ADMIN -> notificationRepository.getNotificationsFlow()
                        else -> notificationRepository.getNotificationsFlow(clientDocName)
                    }

                    notificationsFlow
                        .catch { e ->
                            Log.e(TAG, "Error loading notifications", e)
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                error = e.message ?: "Error al cargar notificaciones"
                            )
                        }
                        .collect { notifications ->
                            Log.d(TAG, "Recibidas ${notifications.size} notificaciones del flujo")

                            val notificationItems = notifications.map { mapToNotificationItem(it) }
                            val hasUnread = notificationItems.any { !it.isRead }
                            val unreadCount = notificationItems.count { !it.isRead }

                            _uiState.value = _uiState.value.copy(
                                notifications = notificationItems,
                                isLoading = false,
                                error = null,
                                hasUnreadNotifications = hasUnread,
                                pendingCount = unreadCount
                            )
                        }
                } else {
                    Log.w(TAG, "No se encontró usuario actual")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado",
                        notifications = emptyList(),
                        hasUnreadNotifications = false,
                        pendingCount = 0
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Carga de notificaciones cancelada intencionalmente")
                } else {
                    Log.e(TAG, "Error al cargar notificaciones", e)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Error al cargar notificaciones: ${e.message}"
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null && currentUser.role == UserRole.USER) {
                    notificationRepository.cleanupOldNotifications(currentUser.clientDocName)
                }
                loadNotifications()
            } catch (e: Exception) {
                Log.e(TAG, "Error al refrescar", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error al refrescar: ${e.message}"
                )
            }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val isAdmin = currentUser.role == UserRole.ADMIN
                    notificationRepository.markAllNotificationsAsRead(
                        clientDocName = currentUser.clientDocName,
                        isAdmin = isAdmin
                    )

                    val updatedNotifications = _uiState.value.notifications.map {
                        it.copy(isRead = true)
                    }
                    _uiState.value = _uiState.value.copy(
                        notifications = updatedNotifications,
                        hasUnreadNotifications = false,
                        pendingCount = 0
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al marcar notificaciones como leídas", e)
            }
        }
    }

    fun onNotificationClick(
        notification: NotificationItem,
        onNavigateToEvent: (String) -> Unit,
        onNavigateToPanel: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Clic en notificación: ${notification.documentName}")

                if (!notification.isRead) {
                    notificationRepository.markNotificationAsRead(
                        notification.clientDocName,
                        notification.documentName
                    ).onSuccess {
                        Log.d(TAG, "Notification marked as read: ${notification.documentName}")
                    }
                }

                when (notification.notificationType) {
                    NotificationType.EVENT -> {
                        Log.d(TAG, "Navegando a la pantalla general de eventos desde notificación")
                        onNavigateToEvent("")
                    }
                    NotificationType.RELAY -> {
                        notification.panelDocName?.let { panelId ->
                            Log.d(TAG, "Navegando al panel: $panelId")
                            onNavigateToPanel(panelId)
                        } ?: run {
                            Log.w(TAG, "No se pudo navegar, panelId es nulo")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en onNotificationClick", e)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun restartNotificationCollection() {
        Log.d(TAG, "Reiniciando recolección de notificaciones")
        loadNotifications()
    }
}