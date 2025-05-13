package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.data.AuthRepository
import com.pqsolutions.hdd_monitor.data.EventRepository
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserData
import com.pqsolutions.hdd_monitor.data.UserPreferences
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.presentation.navigation.Screen
import com.pqsolutions.hdd_monitor.presentation.state.MainUiEvent
import com.pqsolutions.hdd_monitor.presentation.state.MainUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * ViewModel principal que maneja la lógica de negocio de la aplicación
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val eventRepository: EventRepository,
    private val panelRepository: PanelRepository,
    private val notificationRepository: NotificationRepository,
    private val firestore: FirebaseFirestore, // Añadido FirebaseFirestore
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _hasPendingNotifications = MutableStateFlow(false)
    val hasPendingNotifications: StateFlow<Boolean> = _hasPendingNotifications.asStateFlow()

    private var sessionCheckJob: Job? = null
    private var pendingNotificationsJob: Job? = null

    companion object {
        private const val TAG = "MainViewModel"
        const val PANEL_UPDATE_ACTION = "com.pqsolutions.hdd_monitor.PANEL_UPDATE"
        const val CLIENT_MANAGEMENT_ROUTE = "client_management"
        private const val BASE_PATH = "hdd-monitor/accounts/clients" // Añadida constante BASE_PATH
    }

    private val panelUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PANEL_UPDATE_ACTION) {
                val clientDocName = intent.getStringExtra("clientDocName")
                val panelDocName = intent.getStringExtra("panelDocName")
                val relayName = intent.getStringExtra("relayName")
                val relayStatus = intent.getStringExtra("relayStatus")
                if (clientDocName != null && panelDocName != null && relayName != null && relayStatus != null) {
                    updateRelay(clientDocName, panelDocName, relayName, relayStatus)
                }
            }
        }
    }

    init {
        initializeViewModel()
    }

    private fun initializeViewModel() {
        viewModelScope.launch {
            val savedUser = userPreferences.getUserData()
            if (savedUser != null && authRepository.isUserLoggedIn()) {
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = true,
                    userData = savedUser,
                    currentRoute = "dashboard"
                )
                startSessionCheck()
                checkPendingNotifications()
            }

            combineUserPreferences().collect { state ->
                _uiState.value = state
                Log.d(TAG, "UI State updated: $state")
                if (state.isLoggedIn) {
                    checkPendingNotifications()
                }
            }
        }

        registerPanelUpdateReceiver()
        subscribeToTopic()
    }

    private fun startSessionCheck() {
        sessionCheckJob?.cancel()
        sessionCheckJob = viewModelScope.launch {
            while (true) {
                if (!authRepository.isUserLoggedIn()) {
                    handleLogout()
                    break
                }
                kotlinx.coroutines.delay(60000) // Verificar cada minuto
            }
        }
    }

    private fun combineUserPreferences() = combine(
        userPreferences.isFirstLaunchFlow,
        userPreferences.userDataFlow,
        userPreferences.themeFlow,
        userPreferences.languageFlow,
        userPreferences.notificationsEnabledFlow
    ) { isFirstLaunch, userData, theme, language, notificationsEnabled ->
        MainUiState(
            isFirstLaunch = isFirstLaunch,
            isLoggedIn = userData != null,
            userData = userData,
            theme = theme,
            language = language,
            notificationsEnabled = notificationsEnabled,
            currentRoute = determineRoute(isFirstLaunch, userData)
        )
    }

    private fun determineRoute(isFirstLaunch: Boolean, userData: UserData?): String =
        when {
            isFirstLaunch -> Screen.Onboarding.route
            userData != null -> Screen.Dashboard.route
            else -> Screen.Login.route
        }

    private fun registerPanelUpdateReceiver() {
        LocalBroadcastManager.getInstance(context).registerReceiver(
            panelUpdateReceiver,
            IntentFilter(PANEL_UPDATE_ACTION)
        )
    }

    private fun subscribeToTopic() {
        viewModelScope.launch {
            try {
                FirebaseMessaging.getInstance().subscribeToTopic("relay-status").await()
                Log.d(TAG, "Suscrito exitosamente al tema relay-status")
            } catch (e: Exception) {
                Log.e(TAG, "Error al suscribirse al tema relay-status", e)
            }
        }
    }

    private fun updateRelay(clientDocName: String, panelDocName: String, relayName: String, relayStatus: String) {
        viewModelScope.launch {
            try {
                panelRepository.updateRelayStatus(clientDocName, panelDocName, relayName, relayStatus)
                Log.d(TAG, "Relay updated successfully: Panel=$panelDocName, Relay=$relayName, Status=$relayStatus")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating relay: ${e.message}", e)
            }
        }
    }

    fun onEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.Login -> login(event.email, event.password)
            is MainUiEvent.Logout -> logout()
            is MainUiEvent.FinishOnboarding -> finishOnboarding()
            is MainUiEvent.SetTheme -> setTheme(event.theme)
            is MainUiEvent.SetLanguage -> setLanguage(event.language)
            is MainUiEvent.SetNotificationsEnabled -> setNotificationsEnabled(event.enabled)
        }
    }

    private fun login(email: String, password: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Attempting login for email: $email")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                authRepository.login(email, password).fold(
                    onSuccess = { user ->
                        handleLoginSuccess(user)
                    },
                    onFailure = { e ->
                        handleLoginFailure(e)
                    }
                )
            } catch (e: Exception) {
                handleNetworkError(e)
            }
        }
    }

    private suspend fun handleLoginSuccess(user: UserData) {
        userPreferences.setUserData(user)
        updateFCMToken()
        startSessionCheck()
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isLoggedIn = true,
            userData = user,
            error = null,
            currentRoute = "dashboard"
        )
        Log.d(TAG, "Login successful for user: ${user.name}")
        checkPendingNotifications()
    }

    private fun handleLoginFailure(e: Throwable) {
        Log.e(TAG, "Login failed: ${e.message}", e)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = e.message ?: "Unknown error occurred"
        )
    }

    private fun handleNetworkError(e: Exception) {
        Log.e(TAG, "Network error during login: ${e.message}", e)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = "Network error: ${e.message}"
        )
    }

    private fun logout() {
        viewModelScope.launch {
            Log.d(TAG, "Attempting logout")
            try {
                // 1. Primero: aseguramos que los listeners se limpien
                sessionCheckJob?.cancel()
                sessionCheckJob = null
                panelRepository.clearListeners()
                notificationRepository.clearListeners()
                eventRepository.clearListeners()

                // 2. Luego cambiamos la ruta (esto desencadenará la navegación)
                _uiState.value = _uiState.value.copy(currentRoute = "login")

                // 3. Esperamos para que empiece la navegación
                kotlinx.coroutines.delay(300)

                // 4. Actualizamos el resto del estado
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = false,
                    userData = null,
                    error = null
                )
                _hasPendingNotifications.value = false

                // 5. Procedemos con el logout en Firebase
                authRepository.logout().fold(
                    onSuccess = {
                        // 6. Limpiamos datos locales al final
                        userPreferences.clearUserData()
                        Log.d(TAG, "Logout successful")
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Logout failed: ${e.message}", e)
                        // Aseguramos que los datos locales se limpien incluso si falla Firebase
                        userPreferences.clearUserData()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Logout failed: ${e.message}", e)
                // Intentamos limpiar datos aunque falle
                try {
                    userPreferences.clearUserData()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear user data during logout error recovery", e)
                }
            }
        }
    }

    private fun handleLogout() {
        viewModelScope.launch {
            Log.d(TAG, "Handling automatic logout")

            // 1. Cancelar trabajos y limpiar listeners primero
            sessionCheckJob?.cancel()
            sessionCheckJob = null
            panelRepository.clearListeners()
            notificationRepository.clearListeners()
            eventRepository.clearListeners()

            // 2. Actualizar la ruta para iniciar navegación
            _uiState.value = _uiState.value.copy(currentRoute = "login")

            // 3. Pequeña pausa para que se inicie la navegación
            kotlinx.coroutines.delay(300)

            // 4. Limpiar el resto del estado
            userPreferences.clearUserData()
            _uiState.value = _uiState.value.copy(
                isLoggedIn = false,
                userData = null,
                error = null
            )
            _hasPendingNotifications.value = false
        }
    }

    private fun checkPendingNotifications() {
        // Cancelar trabajo existente
        pendingNotificationsJob?.cancel()

        pendingNotificationsJob = viewModelScope.launch {
            uiState.value.userData?.let { user ->
                try {
                    // Usar enfoque de consulta periódica en lugar de listener permanente
                    while (isActive) {
                        val hasUnread = checkUnreadNotificationsDirectly(user)
                        if (hasUnread != _hasPendingNotifications.value) {
                            _hasPendingNotifications.value = hasUnread
                            Log.d(TAG, "Estado de notificaciones pendientes actualizado: $hasUnread")
                        }
                        // Consultar cada 5 segundos
                        delay(5000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en checkPendingNotifications", e)
                }
            }
        }
    }

    // Método para consultar notificaciones sin establecer listener permanente
    private suspend fun checkUnreadNotificationsDirectly(user: UserData): Boolean {
        return try {
            val clientDocName = user.clientDocName

            // Primero verificar notificaciones no leídas
            val hasUnreadNotifications = if (user.role == UserRole.ADMIN) {
                // Consultar para admins usando collectionGroup (más eficiente)
                firestore.collectionGroup("notifications")
                    .whereEqualTo("isRead", false)
                    .limit(1)
                    .get()
                    .await()
                    .size() > 0
            } else {
                // Consultar para usuarios normales en su cliente específico
                if (clientDocName.isNotEmpty()) {
                    // CORRECCIÓN: Usar la ruta correcta sin duplicar "clients"
                    // Error era: "$BASE_PATH/clients/$clientDocName/notifications"
                    firestore.collection("$BASE_PATH/$clientDocName/notifications")
                        .whereEqualTo("isRead", false)
                        .limit(1)
                        .get()
                        .await()
                        .size() > 0
                } else {
                    // Protección contra clientDocName vacío
                    false
                }
            }

            // Luego verificar eventos pendientes de manera segura
            val hasPendingEvents = checkPendingEvents(clientDocName)

            // Retornar true si hay notificaciones no leídas o eventos pendientes
            hasUnreadNotifications || hasPendingEvents
        } catch (e: Exception) {
            Log.e(TAG, "Error checking unread notifications", e)
            false
        }
    }

    // Método para verificar eventos pendientes
    private suspend fun checkPendingEvents(clientDocName: String?): Boolean {
        return try {
            if (clientDocName.isNullOrEmpty()) {
                // Para administradores, buscar eventos pendientes en todos los clientes
                val clientsSnapshot = firestore.collection(BASE_PATH).get().await()
                var hasPendingEvents = false

                for (clientDoc in clientsSnapshot.documents) {
                    val clientId = clientDoc.id
                    try {
                        // CORRECCIÓN: Usar la ruta correcta sin duplicar "clients"
                        val events = firestore.collection("$BASE_PATH/$clientId/events")
                            .whereEqualTo("status", "PROGRAMADO")
                            .limit(1)
                            .get()
                            .await()

                        if (events.size() > 0) {
                            hasPendingEvents = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking events for client $clientId", e)
                        // Continuar con el siguiente cliente en caso de error
                    }
                }

                hasPendingEvents
            } else {
                // Para usuarios normales, buscar en su cliente específico
                // CORRECCIÓN: Usar la ruta correcta sin duplicar "clients"
                val events = firestore.collection("$BASE_PATH/$clientDocName/events")
                    .whereEqualTo("status", "PROGRAMADO")
                    .limit(1)
                    .get()
                    .await()

                events.size() > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pending events", e)
            false
        }
    }

    private fun finishOnboarding() {
        viewModelScope.launch {
            userPreferences.setFirstLaunch(false)
        }
    }

    private fun setTheme(theme: String) {
        viewModelScope.launch {
            userPreferences.setTheme(theme)
        }
    }

    private fun setLanguage(language: String) {
        viewModelScope.launch {
            userPreferences.setLanguage(language)
        }
    }

    private fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotificationsEnabled(enabled)
        }
    }

    suspend fun updateFCMToken() {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d(TAG, "FCM Token obtenido: $token")
            val currentUser = userRepository.getCurrentUser()
            if (currentUser != null) {
                Log.d(TAG, "Actualizando token para usuario: ${currentUser.documentName}")
                userRepository.updateFcmToken(currentUser.documentName, token)
                Log.d(TAG, "Token FCM actualizado exitosamente")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating FCM token: ${e.message}", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        sessionCheckJob?.cancel()
        pendingNotificationsJob?.cancel()
        eventRepository.clearListeners()
        LocalBroadcastManager.getInstance(context).unregisterReceiver(panelUpdateReceiver)
    }
}