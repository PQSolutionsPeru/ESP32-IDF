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

private const val TAG = "NotificationHistoryVM"

data class NotificationHistoryUiState(
    val notifications: List<NotificationItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastUpdate: Long = System.currentTimeMillis(),
    val pendingCount: Int = 0
)

@HiltViewModel
class NotificationHistoryViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationHistoryUiState())
    val uiState: StateFlow<NotificationHistoryUiState> = _uiState.asStateFlow()

    init {
        loadNotifications()
    }

    private fun loadNotifications() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val clientDocName = currentUser.clientDocName
                    Log.d(TAG, "Cargando notificaciones para historia, cliente: $clientDocName")

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
                            Log.d(TAG, "Recibidas ${notifications.size} notificaciones para historial")

                            val notificationItems = notifications.mapNotNull { notification ->
                                try {
                                    Log.d(TAG, "Procesando notificación: ${notification.documentName}")
                                    Log.d(TAG, "  - message: '${notification.message}'")
                                    Log.d(TAG, "  - getDisplayMessage: '${notification.getDisplayMessage()}'")
                                    Log.d(TAG, "  - clientDocName: '${notification.clientDocName}'")
                                    Log.d(TAG, "  - panelDocName: '${notification.panelDocName}'")
                                    Log.d(TAG, "  - relayName: '${notification.relayName}'")
                                    Log.d(TAG, "  - isValid: ${notification.isValid()}")

                                    mapToNotificationItem(notification)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error mapeando notificación ${notification.documentName}", e)
                                    null
                                }
                            }

                            Log.d(TAG, "Después de mapeo: ${notificationItems.size} notificaciones válidas")

                            val sortedItems = notificationItems.sortedByDescending { it.timestamp }
                            val unreadCount = sortedItems.count { !it.isRead }

                            _uiState.value = _uiState.value.copy(
                                notifications = sortedItems,
                                isLoading = false,
                                error = null,
                                lastUpdate = System.currentTimeMillis(),
                                pendingCount = unreadCount
                            )

                            Log.d(TAG, "Estado de historial actualizado: ${sortedItems.size} notificaciones, $unreadCount no leídas")
                        }
                } else {
                    Log.w(TAG, "No se encontró usuario actual para historial")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "No se encontró usuario actual"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadNotifications", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Error desconocido al cargar notificaciones"
                )
            }
        }
    }

    private fun mapToNotificationItem(notification: Notification): NotificationItem? {
        return try {
            val displayMessage = notification.getDisplayMessage()

            if (notification.documentName.isBlank()) {
                Log.w(TAG, "Notificación con documentName vacío")
                return null
            }

            if (displayMessage.isBlank()) {
                Log.w(TAG, "Notificación ${notification.documentName} con mensaje vacío")
                return null
            }

            if (!notification.isValid()) {
                Log.w(TAG, "Notificación ${notification.documentName} no es válida")
                return null
            }

            val notificationItem = NotificationItem(
                documentName = notification.documentName,
                clientDocName = notification.clientDocName,
                title = if (notification.isEventNotification()) {
                    "Evento ${notification.eventType ?: "desconocido"}"
                } else {
                    "Actualización de Panel"
                },
                text = displayMessage,
                date_time = notification.date_time,
                status = notification.status?.let {
                    NotificationStatus.fromString(it)
                } ?: NotificationStatus.PROGRAMADO,
                panelDocName = notification.panelDocName,
                panelName = notification.panelName,
                relayName = notification.relayName,
                eventId = notification.eventId,
                eventType = notification.eventType,
                notificationType = if (notification.isEventNotification()) {
                    NotificationType.EVENT
                } else {
                    NotificationType.RELAY
                },
                isRead = notification.isRead,
                timestamp = notification.timestamp
            )

            Log.d(TAG, "Mapeado exitoso: ${notificationItem.documentName} -> ${notificationItem.text.take(50)}")
            notificationItem
        } catch (e: Exception) {
            Log.e(TAG, "Error mapping notification ${notification.documentName}: ${e.message}", e)
            null
        }
    }

    fun refreshNotifications() {
        Log.d(TAG, "Refreshing notifications")
        loadNotifications()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val isAdmin = currentUser.role == UserRole.ADMIN
                    notificationRepository.markAllNotificationsAsRead(currentUser.clientDocName, isAdmin)
                        .onSuccess {
                            val updatedNotifications = _uiState.value.notifications.map {
                                it.copy(isRead = true)
                            }
                            _uiState.value = _uiState.value.copy(
                                notifications = updatedNotifications,
                                pendingCount = 0
                            )
                            Log.d(TAG, "Todas las notificaciones marcadas como leídas")
                        }
                        .onFailure { e ->
                            Log.e(TAG, "Error marcando todas como leídas", e)
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en markAllAsRead", e)
            }
        }
    }

    override fun onCleared() {
        try {
            Log.d(TAG, "onCleared: Limpiando recursos")
            notificationRepository.clearListeners()
        } catch (e: Exception) {
            Log.e(TAG, "Error durante onCleared", e)
        } finally {
            super.onCleared()
            Log.d(TAG, "ViewModel cleared")
        }
    }
}