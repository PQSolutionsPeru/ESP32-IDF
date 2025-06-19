package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.data.ClientRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import com.pqsolutions.hdd_monitor.util.StatusUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var panelsJob: Job? = null
    private var statusUpdateJob: Job? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    init {
        Log.d(TAG, "DashboardViewModel initialized")
        listenForStatusUpdates()
    }

    private fun listenForStatusUpdates() {
        statusUpdateJob?.cancel()
        statusUpdateJob = viewModelScope.launch {
            Log.d(TAG, "Comenzando a escuchar actualizaciones de estado")

            StatusUpdateManager.statusUpdates
                .distinctUntilChanged()
                .collect { update ->
                    Log.d(TAG, "Recibida actualización de estado: ${update.panelDocName} -> ${update.newStatus}")
                    processStatusUpdate(update)
                }
        }
    }

    private fun processStatusUpdate(update: StatusUpdateManager.StatusUpdate) {
        _uiState.update { currentState ->
            val updatedPanels = currentState.panels.map { panel ->
                if (panel.documentName == update.panelDocName) {
                    if (update.isEsp32) {
                        Log.d(TAG, "Actualizando ESP32 de ${panel.name} a ${update.newStatus}")
                        panel.copy(esp32Status = update.newStatus)
                    } else {
                        Log.d(TAG, "Actualizando relay ${update.relayName} de ${panel.name} a ${update.newStatus}")
                        val updatedRelays = panel.relays.map { relay ->
                            if (relay.name == update.relayName) {
                                relay.copy(status = update.newStatus)
                            } else {
                                relay
                            }
                        }
                        panel.copy(relays = updatedRelays)
                    }
                } else {
                    panel
                }
            }

            val updatedGroupedPanels = updatedPanels.groupBy {
                currentState.clientNames[it.clientName] ?: it.clientName
            }

            currentState.copy(
                panels = updatedPanels,
                groupedPanels = updatedGroupedPanels,
                lastUpdate = System.currentTimeMillis()
            )
        }
    }

    fun loadPanels() {
        Log.d(TAG, "loadPanels() called")

        if (isInitialized && panelsJob?.isActive == true) {
            Log.d(TAG, "Panels already initialized and loading, skipping")
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        panelsJob?.cancel()
        panelsJob = viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                Log.d(TAG, "Current user: ${currentUser?.documentName}, Role: ${currentUser?.role}")

                if (currentUser != null) {
                    val clientDocName = when (currentUser.role) {
                        UserRole.USER -> {
                            Log.d(TAG, "User role detected, using client: ${currentUser.clientDocName}")
                            currentUser.clientDocName
                        }
                        UserRole.ADMIN -> {
                            Log.d(TAG, "Admin role detected, fetching all panels")
                            null
                        }
                    }

                    val clientsMap = loadClientNames(clientDocName)
                    _uiState.update { it.copy(clientNames = clientsMap) }

                    panelRepository.getPanels(clientDocName).collect { panels ->
                        Log.d(TAG, "Received ${panels.size} panels from repository")

                        val validPanels = panels.filter { panel ->
                            panel.documentName.startsWith(DocumentPrefixes.PANEL) &&
                                    panel.clientName.startsWith(DocumentPrefixes.CLIENT)
                        }

                        val groupedPanels = validPanels.groupBy {
                            clientsMap[it.clientName] ?: it.clientName
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

                        if (!isInitialized) {
                            isInitialized = true
                            Log.d(TAG, "Dashboard initialized successfully")
                        }
                    }
                } else {
                    Log.e(TAG, "No authenticated user found")
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "Error cargando paneles: no hay usuario autenticado"
                    ) }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error loading panels", e)
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = e.message ?: "Error desconocido"
                    ) }
                } else {
                    Log.d(TAG, "Panel loading cancelled intentionally")
                }
            }
        }
    }

    private suspend fun loadClientNames(clientDocName: String?): Map<String, String> {
        return try {
            if (clientDocName != null) {
                val clientSnapshot = firestore
                    .collection("hdd-monitor/accounts/clients")
                    .document(clientDocName)
                    .get()
                    .await()

                if (clientSnapshot.exists()) {
                    val clientName = clientSnapshot.getString("name")
                    if (clientName != null) {
                        mapOf(clientDocName to clientName)
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

    override fun onCleared() {
        try {
            super.onCleared()
            Log.d(TAG, "DashboardViewModel clearing")

            panelsJob?.cancel()
            statusUpdateJob?.cancel()

            panelsJob = null
            statusUpdateJob = null

            try {
                panelRepository.clearListeners()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing panel listeners", e)
            }

            Log.d(TAG, "DashboardViewModel cleared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in DashboardViewModel.onCleared", e)
        }
    }

    data class DashboardUiState(
        val isLoading: Boolean = true,
        val panels: List<Panel> = emptyList(),
        val groupedPanels: Map<String, List<Panel>> = emptyMap(),
        val clientNames: Map<String, String> = emptyMap(),
        val error: String? = null,
        val lastUpdate: Long = System.currentTimeMillis()
    ) {
        override fun toString(): String {
            return "DashboardUiState(" +
                    "isLoading=$isLoading, " +
                    "panels=${panels.size}, " +
                    "groupedPanels=${groupedPanels.keys}, " +
                    "clientNames=${clientNames.size}, " +
                    "error=$error, " +
                    "lastUpdate=$lastUpdate" +
                    ")"
        }
    }
}