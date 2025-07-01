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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val eventRepository: EventRepository,
    private val panelRepository: PanelRepository,
    private val notificationRepository: NotificationRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _hasPendingNotifications = MutableStateFlow(false)
    val hasPendingNotifications: StateFlow<Boolean> = _hasPendingNotifications.asStateFlow()

    private val _navigateToRelayControl = MutableStateFlow(false)
    val navigateToRelayControl: StateFlow<Boolean> = _navigateToRelayControl.asStateFlow()

    private var sessionCheckJob: Job? = null
    private var pendingNotificationsJob: Job? = null

    companion object {
        private const val TAG = "MainViewModel"
        const val PANEL_UPDATE_ACTION = "com.pqsolutions.hdd_monitor.PANEL_UPDATE"
        const val RELAY_CONTROL_ACTION = "com.pqsolutions.hdd_monitor.RELAY_CONTROL"
        const val CLIENT_MANAGEMENT_ROUTE = "client_management"
        const val RELAY_CONTROL_ROUTE = "relay_control"
        private const val BASE_PATH = "hdd-monitor/accounts/clients"
    }

    private val panelUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PANEL_UPDATE_ACTION -> {
                    val clientDocName = intent.getStringExtra("clientDocName")
                    val panelDocName = intent.getStringExtra("panelDocName")
                    val relayName = intent.getStringExtra("relayName")
                    val relayStatus = intent.getStringExtra("relayStatus")
                    if (clientDocName != null && panelDocName != null && relayName != null && relayStatus != null) {
                        updateRelay(clientDocName, panelDocName, relayName, relayStatus)
                    }
                }
                RELAY_CONTROL_ACTION -> {
                    Log.d(TAG, "Relay control action received")
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
                    userData = savedUser
                )
            }

            combineUserPreferences().collect { state ->
                _uiState.value = state
                Log.d(TAG, "UI State updated")
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
                kotlinx.coroutines.delay(60000)
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
            notificationsEnabled = notificationsEnabled
        )
    }

    private fun registerPanelUpdateReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(PANEL_UPDATE_ACTION)
            addAction(RELAY_CONTROL_ACTION)
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(
            panelUpdateReceiver,
            intentFilter
        )
    }

    private fun subscribeToTopic() {
        viewModelScope.launch {
            try {
                FirebaseMessaging.getInstance().subscribeToTopic("relay-status").await()
                FirebaseMessaging.getInstance().subscribeToTopic("relay-control").await()
                Log.d(TAG, "Suscrito exitosamente a temas relay-status y relay-control")
            } catch (e: Exception) {
                Log.e(TAG, "Error al suscribirse a temas MQTT", e)
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
            is MainUiEvent.NavigateToRelayControl -> navigateToRelayControl()
        }
    }

    fun navigateToRelayControl() {
        viewModelScope.launch {
            try {
                val currentUser = _uiState.value.userData
                if (currentUser != null) {
                    Log.d(TAG, "Navegando a control de relays para usuario: ${currentUser.name}")
                    _navigateToRelayControl.value = true
                } else {
                    Log.e(TAG, "No hay usuario logueado para navegar al control de relays")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error navegando al control de relays", e)
            }
        }
    }

    fun onRelayControlNavigationCompleted() {
        _navigateToRelayControl.value = false
    }

    fun canAccessRelayControl(): Boolean {
        val userData = _uiState.value.userData
        return userData != null && (userData.role == UserRole.ADMIN || userData.role == UserRole.USER)
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
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isLoggedIn = true,
            userData = user,
            error = null
        )
        Log.d(TAG, "Login successful for user: ${user.name}")
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
                _navigateToRelayControl.value = false

                _uiState.value = _uiState.value.copy(
                    isLoggedIn = false,
                    userData = null,
                    error = null
                )
                _hasPendingNotifications.value = false

                authRepository.logout().fold(
                    onSuccess = {
                        userPreferences.clearUserData()
                        Log.d(TAG, "Logout successful")
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Logout failed: ${e.message}", e)
                        userPreferences.clearUserData()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Logout failed: ${e.message}", e)
                userPreferences.clearUserData()
            }
        }
    }

    private fun handleLogout() {
        viewModelScope.launch {
            Log.d(TAG, "Handling automatic logout")

            _navigateToRelayControl.value = false

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
        pendingNotificationsJob?.cancel()

        pendingNotificationsJob = viewModelScope.launch {
            uiState.value.userData?.let { user ->
                try {
                    while (isActive) {
                        val hasUnread = checkUnreadNotificationsDirectly(user)
                        if (hasUnread != _hasPendingNotifications.value) {
                            _hasPendingNotifications.value = hasUnread
                            Log.d(TAG, "Estado de notificaciones pendientes actualizado: $hasUnread")
                        }
                        delay(5000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en checkPendingNotifications", e)
                }
            }
        }
    }

    private suspend fun checkUnreadNotificationsDirectly(user: UserData): Boolean {
        return try {
            val clientDocName = user.clientDocName

            val hasUnreadNotifications = if (user.role == UserRole.ADMIN) {
                firestore.collectionGroup("notifications")
                    .whereEqualTo("isRead", false)
                    .limit(1)
                    .get()
                    .await()
                    .size() > 0
            } else {
                if (clientDocName.isNotEmpty()) {
                    firestore.collection("$BASE_PATH/$clientDocName/notifications")
                        .whereEqualTo("isRead", false)
                        .limit(1)
                        .get()
                        .await()
                        .size() > 0
                } else {
                    false
                }
            }

            val hasPendingEvents = checkPendingEvents(clientDocName)

            hasUnreadNotifications || hasPendingEvents
        } catch (e: Exception) {
            Log.e(TAG, "Error checking unread notifications", e)
            false
        }
    }

    private suspend fun checkPendingEvents(clientDocName: String?): Boolean {
        return try {
            if (clientDocName.isNullOrEmpty()) {
                val clientsSnapshot = firestore.collection(BASE_PATH).get().await()
                var hasPendingEvents = false

                for (clientDoc in clientsSnapshot.documents) {
                    val clientId = clientDoc.id
                    try {
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
                    }
                }

                hasPendingEvents
            } else {
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
        LocalBroadcastManager.getInstance(context).unregisterReceiver(panelUpdateReceiver)
    }
}