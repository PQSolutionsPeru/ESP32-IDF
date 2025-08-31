package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.data.manager.ListenerManager
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val userRepository: UserRepository,
    private val firestore: FirebaseFirestore,
    private val listenerManager: ListenerManager,
    private val esp32Repository: ESP32Repository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var panelsJob: Job? = null
    private var currentClientDocName: String? = null

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    init {
        Log.d(TAG, "DashboardViewModel initialized")
        listenerManager.setCurrentScreen("dashboard")
        initializeDashboard()
    }

    fun onScreenVisible() {
        Log.d(TAG, "Dashboard screen became visible - reactivating listeners")
        listenerManager.setCurrentScreen("dashboard")

        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - (_uiState.value.lastUpdate)

        if (timeSinceLastUpdate > 30000) {
            Log.d(TAG, "Refreshing data due to time elapsed: ${timeSinceLastUpdate}ms")
            refreshPanels()
        } else {
            reactivateListeners()
        }
    }

    fun onScreenHidden() {
        Log.d(TAG, "Dashboard screen hidden")
        listenerManager.setCurrentScreen("other")
    }

    private fun reactivateListeners() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Reactivating listeners without full refresh")
                panelRepository.clearStaleListeners()
                startPanelCollection()
            } catch (e: Exception) {
                Log.e(TAG, "Error reactivating listeners", e)
            }
        }
    }

    private fun initializeDashboard() {
        _uiState.update { it.copy(isLoading = true, error = null) }

        panelsJob?.cancel()
        panelsJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Inicializando dashboard - reiniciando todos los listeners")

                panelRepository.clearStaleListeners()
                esp32Repository.forceRestart()

                val currentUser = userRepository.getCurrentUser()
                Log.d(TAG, "Current user: ${currentUser?.documentName}, Role: ${currentUser?.role}")

                if (currentUser != null) {
                    currentClientDocName = when (currentUser.role) {
                        UserRole.USER -> {
                            Log.d(TAG, "User role detected, using client: ${currentUser.clientDocName}")
                            currentUser.clientDocName
                        }
                        UserRole.ADMIN -> {
                            Log.d(TAG, "Admin role detected, fetching all panels")
                            null
                        }
                    }

                    startPanelCollection()

                } else {
                    Log.e(TAG, "No authenticated user found")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Error cargando paneles: no hay usuario autenticado"
                        )
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error initializing dashboard", e)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Error desconocido"
                        )
                    }
                }
            }
        }
    }

    private suspend fun startPanelCollection() {
        Log.d(TAG, "Iniciando colección reactiva de paneles para cliente: $currentClientDocName")

        val clientsMap = loadClientNames()

        panelRepository.getPanels(currentClientDocName)
            .catch { error ->
                Log.e(TAG, "Error en flujo de paneles", error)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Error en flujo de paneles: ${error.message}"
                    )
                }
            }
            .collect { panels ->
                Log.d(TAG, "Recibidos ${panels.size} paneles del repository")

                val validPanels = panels.filter { panel ->
                    panel.documentName.startsWith(DocumentPrefixes.PANEL) &&
                            panel.clientName.startsWith(DocumentPrefixes.CLIENT)
                }

                val groupedPanels = if (currentClientDocName != null) {
                    emptyMap()
                } else {
                    validPanels.groupBy { panel ->
                        clientsMap[panel.clientName] ?: panel.clientName
                    }
                }

                _uiState.update { currentState ->
                    currentState.copy(
                        isLoading = false,
                        panels = validPanels,
                        groupedPanels = groupedPanels,
                        clientNames = clientsMap,
                        error = null,
                        lastUpdate = System.currentTimeMillis()
                    )
                }

                Log.d(TAG, "Estado del dashboard actualizado: ${validPanels.size} paneles válidos")

                validPanels.forEach { panel ->
                    Log.d(TAG, "Panel: ${panel.name}, Relays: ${panel.relays.size}, Active: ${panel.activeRelays.size}, ESP32: ${panel.esp32Status}")
                }
            }
    }

    private suspend fun loadClientNames(): Map<String, String> {
        return try {
            if (currentClientDocName != null) {
                val clientSnapshot = firestore
                    .collection("hdd-monitor/accounts/clients")
                    .document(currentClientDocName!!)
                    .get()
                    .await()

                if (clientSnapshot.exists()) {
                    val clientName = clientSnapshot.getString("name")
                    if (clientName != null) {
                        mapOf(currentClientDocName!! to clientName)
                    } else {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
            } else {
                val clients = mutableMapOf<String, String>()
                val clientsSnapshot = firestore
                    .collection("hdd-monitor/accounts/clients")
                    .get()
                    .await()

                for (doc in clientsSnapshot.documents) {
                    val clientName = doc.getString("name")
                    if (clientName != null) {
                        clients[doc.id] = clientName
                    }
                }
                clients
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading client names", e)
            emptyMap()
        }
    }

    fun refreshPanels() {
        Log.d(TAG, "Refresh manual solicitado - reiniciando dashboard completo")
        initializeDashboard()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun retryLoad() {
        Log.d(TAG, "Reintentar carga solicitado")
        clearError()
        initializeDashboard()
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "DashboardViewModel being cleared")
        panelsJob?.cancel()
        listenerManager.setCurrentScreen("other")
    }

    data class DashboardUiState(
        val isLoading: Boolean = true,
        val panels: List<Panel> = emptyList(),
        val groupedPanels: Map<String, List<Panel>> = emptyMap(),
        val clientNames: Map<String, String> = emptyMap(),
        val error: String? = null,
        val lastUpdate: Long = System.currentTimeMillis()
    )
}