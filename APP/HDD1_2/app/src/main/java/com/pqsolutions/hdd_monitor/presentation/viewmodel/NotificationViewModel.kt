package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.data.Notification
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.NotificationUseCase
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "NotificationViewModel"

// Definir el estado UI para notificaciones
data class NotificationUiState(
    val notifications: List<NotificationItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasUnreadNotifications: Boolean = false,
    val pendingCount: Int = 0  // Propiedad añadida
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
    private val notificationUseCase: NotificationUseCase,
    private val userRepository: UserRepository
) : ViewModel() {

    // NUEVO: Desactivar mostrar notificaciones visuales desde aquí
    private val SHOW_VISUAL_NOTIFICATIONS = false

    // NUEVO: Lista para rastrear notificaciones ya procesadas y evitar duplicados
    private val processedNotifications = mutableSetOf<String>()
    private val PROCESSED_CACHE_MAX_SIZE = 100

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                // Realizar limpieza inicial
                val currentUser = userRepository.getCurrentUser()
                if (currentUser?.role == UserRole.USER) {
                    notificationUseCase.performInitialCleanup(currentUser.clientDocName)
                        .onSuccess { Log.d(TAG, "Limpieza inicial completada") }
                        .onFailure { e -> Log.e(TAG, "Error en limpieza inicial", e) }
                }
                // Cargar notificaciones
                loadNotifications()
            } catch (e: Exception) {
                Log.e(TAG, "Error en inicialización", e)
            }
        }
    }

    private var notificationCollectJob: Job? = null

    // Función para convertir Notification a NotificationItem
    private fun mapToNotificationItem(notification: Notification): NotificationItem {
        // Determinar tipo de notificación
        val notificationType = if (notification.isEventNotification()) {
            NotificationType.EVENT
        } else {
            NotificationType.RELAY
        }

        // Extraer texto para la UI - Usar getDisplayMessage para asegurar el nombre del panel
        val title = "HDD Monitor"
        val text = notification.getDisplayMessage()

        // Mapear estado si existe
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

    fun cancelLoading() {
        viewModelScope.launch {
            try {
                // Cancelar cualquier trabajo en progreso
                notificationCollectJob?.cancel()
                notificationCollectJob = null

                // Resetear estado a "no cargando"
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelando carga", e)
            }
        }
    }

    private fun loadNotifications() {
        // Cancelar trabajo anterior si existe
        try {
            notificationCollectJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelando trabajo anterior", e)
        }

        notificationCollectJob = viewModelScope.launch {
            // Primero actualizamos el estado a cargando
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val clientDocName = currentUser.clientDocName

                    // Log para debug
                    Log.d(TAG, "Cargando notificaciones para cliente: $clientDocName")

                    val notificationsFlow = when (currentUser.role) {
                        UserRole.ADMIN -> notificationUseCase.getAllNotificationsFlow()
                        else -> notificationUseCase.getNotificationsFlow(clientDocName)
                    }

                    // Usar distinctUntilChanged con un timeout para evitar procesamiento duplicado
                    var isFirstCollection = true

                    notificationsFlow
                        .distinctUntilChanged { old, new ->
                            // Comparar si las listas son iguales por sus IDs
                            if (old.size != new.size) return@distinctUntilChanged false
                            val oldIds = old.map { it.documentName }.toSet()
                            val newIds = new.map { it.documentName }.toSet()
                            oldIds == newIds
                        }
                        .collect { notifications ->
                            // Si es la primera recolección, siempre actualizamos
                            // Si no, verificamos si hay algo nuevo que mostrar
                            if (isFirstCollection || notifications.isNotEmpty()) {
                                // Log para debug
                                Log.d(TAG, "Recibidas ${notifications.size} notificaciones del flujo")
                                isFirstCollection = false

                                // Convertir de Notification a NotificationItem
                                val notificationItems = notifications.map { mapToNotificationItem(it) }

                                // Filtramos cualquier notificación que ya hayamos procesado
                                val newNotifications = notificationItems.filterNot {
                                    processedNotifications.contains(it.documentName)
                                }

                                // Actualizamos nuestra caché de procesados
                                newNotifications.forEach { notification ->
                                    processedNotifications.add(notification.documentName)
                                    // Limpiar caché si es demasiado grande
                                    if (processedNotifications.size > PROCESSED_CACHE_MAX_SIZE) {
                                        val removeCount = processedNotifications.size - PROCESSED_CACHE_MAX_SIZE
                                        processedNotifications.toList().take(removeCount).forEach {
                                            processedNotifications.remove(it)
                                        }
                                    }
                                }

                                // Actualizar UI state con todas las notificaciones (nuevas y existentes)
                                val hasUnread = notificationItems.any { !it.isRead }
                                val unreadCount = notificationItems.count { !it.isRead }

                                _uiState.value = _uiState.value.copy(
                                    notifications = notificationItems,
                                    isLoading = false,
                                    error = null,
                                    hasUnreadNotifications = hasUnread,
                                    pendingCount = unreadCount
                                )
                            } else {
                                // Si no hay notificaciones pero llegamos a este punto,
                                // solo actualizamos el estado de carga
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    error = null,
                                    hasUnreadNotifications = false,
                                    pendingCount = 0
                                )
                            }
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
                // Ignorar excepciones de cancelación
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Carga de notificaciones cancelada intencionalmente")
                } else {
                    Log.e(TAG, "Error al cargar notificaciones", e)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Error al cargar notificaciones: ${e.message}",
                        notifications = _uiState.value.notifications // Mantener notificaciones previas
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
                if (currentUser != null) {
                    // Ejecutar limpieza al refrescar
                    if (currentUser.role == UserRole.USER) {
                        notificationUseCase.keepOnlyLastN(currentUser.clientDocName)
                            .onFailure { e -> Log.e(TAG, "Error en limpieza", e) }
                    }
                    loadNotifications()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                }
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
                    notificationUseCase.markAllNotificationsAsRead(
                        clientDocName = currentUser.clientDocName,
                        isAdmin = isAdmin
                    )
                    // Actualizar estado local
                    val updatedNotifications = _uiState.value.notifications.map {
                        it.copy(isRead = true)
                    }
                    _uiState.value = _uiState.value.copy(
                        notifications = updatedNotifications,
                        hasUnreadNotifications = false,
                        pendingCount = 0 // Reset contador a cero
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

                // Marcar la notificación como leída
                if (!notification.isRead) {
                    val currentUser = userRepository.getCurrentUser()
                    if (currentUser != null) {
                        // Usar notificationUseCase en lugar de notificationRepository
                        notificationUseCase.markNotificationAsRead(
                            notification.clientDocName,
                            notification.documentName
                        ).onSuccess {
                            Log.d(TAG, "Notification marked as read: ${notification.documentName}")
                        }
                    }
                }

                when (notification.notificationType) {
                    NotificationType.EVENT -> {
                        // CORRECCIÓN: Navegar a la pantalla general de eventos en lugar de a un evento específico
                        Log.d(TAG, "Navegando a la pantalla general de eventos desde notificación")
                        onNavigateToEvent("") // Pasar una cadena vacía para navegar a la pantalla general de eventos
                    }
                    NotificationType.RELAY -> {
                        notification.panelDocName?.let { panelId ->
                            Log.d(TAG, "Navegando al panel: $panelId")
                            onNavigateToPanel(panelId)
                        } ?: run {
                            Log.w(TAG, "No se pudo navegar, panelId es nulo")
                        }
                    }
                    else -> {
                        Log.d(TAG, "Tipo de notificación desconocido, no se navega")
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
        try {
            Log.d(TAG, "onCleared: Limpiando recursos")

            // Cancelar el job de recolección de notificaciones primero
            notificationCollectJob?.cancel()
            notificationCollectJob = null

            // Limpiar otros recursos
            processedNotifications.clear()

            // Resetear el estado UI
            _uiState.value = NotificationUiState()
        } catch (e: Exception) {
            Log.e(TAG, "Error durante onCleared", e)
        } finally {
            super.onCleared()
            Log.d(TAG, "ViewModel cleared")
        }
    }

    fun restartNotificationCollection() {
        // Evitar iniciar múltiples trabajos
        if (_uiState.value.isLoading) {
            Log.d(TAG, "Carga ya en progreso, no se reinicia")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "Reiniciando recolección de notificaciones")

                // Detener job actual con un semáforo para evitar condiciones de carrera
                val currentJob = notificationCollectJob
                notificationCollectJob = null

                // Cancelamos con cuidado
                currentJob?.cancel()

                // Pequeña pausa para asegurar limpieza
                kotlinx.coroutines.delay(300)

                // Actualizar estado UI para mostrar loading
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                // Iniciar nueva carga
                loadNotifications()
            } catch (e: Exception) {
                Log.e(TAG, "Error al reiniciar notificaciones", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Error al cargar: ${e.message}")
            }
        }
    }
}