package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.data.Notification
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "NotificationHistoryVM"

// Clase de estado actualizada con pendingCount
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
            _uiState.update { it.copy(isLoading = true, error = null) }
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
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = e.message ?: "Error al cargar notificaciones"
                                )
                            }
                        }
                        .collect { notifications ->
                            Log.d(TAG, "Recibidas ${notifications.size} notificaciones para historial")

                            if (notifications.isEmpty()) {
                                Log.d(TAG, "La lista de notificaciones del historial está vacía")
                                _uiState.update {
                                    it.copy(
                                        notifications = emptyList(),
                                        isLoading = false,
                                        error = null,
                                        lastUpdate = System.currentTimeMillis(),
                                        pendingCount = 0
                                    )
                                }
                                return@collect
                            }

                            // Debug: mostrar primeras 3 notificaciones
                            if (notifications.isNotEmpty()) {
                                notifications.take(3).forEach { notification ->
                                    Log.d(TAG, "Notificación: ${notification.documentName}, mensaje: ${notification.message}")
                                }
                            }

                            // Convertir Notification a NotificationItem
                            val notificationItems = notifications.mapNotNull { notification ->
                                try {
                                    NotificationItem(
                                        documentName = notification.documentName,
                                        clientDocName = notification.clientDocName,
                                        title = if (notification.isEventNotification())
                                            "Evento ${notification.eventType}"
                                        else
                                            "Actualización de Panel",
                                        text = notification.message,
                                        date_time = notification.date_time,
                                        status = notification.status?.let {
                                            NotificationStatus.fromString(it)
                                        } ?: NotificationStatus.PROGRAMADO,
                                        panelDocName = notification.panelDocName,
                                        panelName = notification.panelName,
                                        relayName = notification.relayName,
                                        eventId = notification.eventId,
                                        eventType = notification.eventType,
                                        notificationType = if (notification.isEventNotification())
                                            NotificationType.EVENT
                                        else NotificationType.RELAY,
                                        isRead = notification.isRead,
                                        timestamp = notification.timestamp
                                    ).takeIf { item ->
                                        val isValid = item.documentName.startsWith(DocumentPrefixes.NOTIFICATION) &&
                                                item.clientDocName.startsWith(DocumentPrefixes.CLIENT) &&
                                                item.text.isNotBlank() &&
                                                (item.panelDocName?.startsWith(DocumentPrefixes.PANEL) ?: true)

                                        if (!isValid) {
                                            Log.w(TAG, "Item inválido: ${item.documentName}, clientDocName: ${item.clientDocName}")
                                        }

                                        isValid
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error mapeando notificación ${notification.documentName}", e)
                                    null
                                }
                            }

                            Log.d(TAG, "Después de mapeo y filtrado: ${notificationItems.size} notificaciones válidas en historial")

                            // IMPORTANTE: Ordenar explícitamente por timestamp
                            val sortedItems = notificationItems.sortedByDescending { it.timestamp }

                            // Contar notificaciones no leídas
                            val unreadCount = sortedItems.count { !it.isRead }

                            _uiState.update {
                                it.copy(
                                    notifications = sortedItems,
                                    isLoading = false,
                                    error = null,
                                    lastUpdate = System.currentTimeMillis(),
                                    pendingCount = unreadCount
                                )
                            }

                            Log.d(TAG, "Estado de historial actualizado: ${sortedItems.size} notificaciones, $unreadCount no leídas")
                        }
                } else {
                    Log.w(TAG, "No se encontró usuario actual para historial")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "No se encontró usuario actual"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadNotifications", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Error desconocido al cargar notificaciones"
                    )
                }
            }
        }
    }

    fun refreshNotifications() {
        Log.d(TAG, "Refreshing notifications")
        loadNotifications()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // Método para marcar todas las notificaciones como leídas (actualizado con pendingCount)
    fun markAllAsRead() {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val isAdmin = currentUser.role == UserRole.ADMIN
                    notificationRepository.markAllNotificationsAsRead(currentUser.clientDocName, isAdmin)
                        .onSuccess {
                            // Actualizar estado local
                            val updatedNotifications = _uiState.value.notifications.map {
                                it.copy(isRead = true)
                            }
                            _uiState.update {
                                it.copy(
                                    notifications = updatedNotifications,
                                    pendingCount = 0 // Reset contador a cero
                                )
                            }
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
        super.onCleared()
        Log.d(TAG, "ViewModel cleared")
    }
}