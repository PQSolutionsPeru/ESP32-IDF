package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.Relay
import com.pqsolutions.hdd_monitor.data.RelayControlRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.presentation.state.RelayControlState
import com.pqsolutions.hdd_monitor.util.StatusUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

@HiltViewModel
class RelayControlViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val relayControlRepository: RelayControlRepository,
    private val userRepository: UserRepository,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    companion object {
        private const val TAG = "RelayControlViewModel"
    }

    private val _uiState = MutableStateFlow(RelayControlState())
    val uiState: StateFlow<RelayControlState> = _uiState.asStateFlow()

    private var loadingJob: Job? = null
    private var statusUpdateJob: Job? = null

    init {
        Log.d(TAG, "RelayControlViewModel inicializado")
        startStatusUpdatesListener()
    }

    fun loadSpecificPanel(panelId: String) {
        cancelCurrentJob()

        loadingJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Cargando panel específico: $panelId")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val currentUser = userRepository.getCurrentUser()
                if (currentUser == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                    return@launch
                }

                val clientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> null
                }

                if (clientDocName != null) {
                    loadClientSpecificPanel(clientDocName, panelId)
                } else {
                    loadAdminSpecificPanel(panelId)
                }

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Carga de panel específico cancelada")
                    return@launch
                }

                Log.e(TAG, "Error cargando panel específico", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error cargando panel: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadClientSpecificPanel(clientDocName: String, panelId: String) {
        val clientsMap = loadClientNames(clientDocName)
        _uiState.value = _uiState.value.copy(clientNames = clientsMap)

        panelRepository.observePanelUpdates(clientDocName, panelId)
            .catch { error ->
                if (error is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Observación de panel cancelada")
                    return@catch
                }

                Log.e(TAG, "Error en observación de panel", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error cargando panel: ${error.message}"
                )
            }
            .collect { panel ->
                if (!currentCoroutineContext().isActive) return@collect

                if (panel != null) {
                    val clientDisplayName = clientsMap[panel.clientName] ?: panel.clientName
                    val groupedPanels: Map<String, List<Panel>> = mapOf(clientDisplayName to listOf(panel))

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        panels = listOf(panel),
                        groupedPanels = groupedPanels,
                        error = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Panel no encontrado"
                    )
                }
            }
    }

    private suspend fun loadAdminSpecificPanel(panelId: String) {
        val allClientsSnapshot = firestore.collection("hdd-monitor/accounts/clients").get().await()

        for (clientDoc in allClientsSnapshot.documents) {
            if (clientDoc.exists()) {
                val clientId = clientDoc.id
                val panelExists = panelRepository.verifyPanelExists(clientId, panelId)

                if (panelExists) {
                    loadClientSpecificPanel(clientId, panelId)
                    return
                }
            }
        }

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = "Panel no encontrado en ningún cliente"
        )
    }

    fun loadPanels() {
        cancelCurrentJob()

        loadingJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Iniciando carga de paneles")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val currentUser = userRepository.getCurrentUser()
                if (currentUser == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                    return@launch
                }

                val clientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> null
                }

                val clientsMap = loadClientNames(clientDocName)
                _uiState.value = _uiState.value.copy(clientNames = clientsMap)

                panelRepository.getPanels(clientDocName)
                    .catch { error ->
                        if (error is kotlinx.coroutines.CancellationException) {
                            Log.d(TAG, "Carga de paneles cancelada")
                            return@catch
                        }

                        Log.e(TAG, "Error en flow de paneles", error)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Error cargando paneles: ${error.message}"
                        )
                    }
                    .collect { panels ->
                        if (!currentCoroutineContext().isActive) return@collect

                        Log.d(TAG, "Paneles recibidos: ${panels.size}")

                        val groupedPanels = panels.groupBy { panel ->
                            clientsMap[panel.clientName] ?: panel.clientName
                        }

                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            panels = panels,
                            groupedPanels = groupedPanels,
                            error = null
                        )
                    }

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Carga de paneles cancelada")
                    return@launch
                }

                Log.e(TAG, "Error cargando paneles", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error cargando paneles: ${e.message}"
                )
            }
        }
    }

    fun refreshPanels() {
        Log.d(TAG, "Refresh manual solicitado")
        loadPanels()
    }

    fun refreshSpecificPanel(panelId: String) {
        Log.d(TAG, "Refresh de panel específico solicitado: $panelId")
        loadSpecificPanel(panelId)
    }

    fun updateRelayConfig(panel: Panel, updatedRelay: Relay) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Actualizando configuración del relay ${updatedRelay.name}")

                _uiState.value = _uiState.value.copy(operationInProgress = true)

                val updateData = mutableMapOf<String, Any>(
                    "isActive" to updatedRelay.isActive,
                    "contactType" to updatedRelay.contactType,
                    "lastUpdate" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                val customName = updatedRelay.customName?.trim()
                if (!customName.isNullOrBlank() && customName != updatedRelay.name) {
                    updateData["customName"] = customName
                } else {
                    updateData["customName"] = com.google.firebase.firestore.FieldValue.delete()
                }

                firestore.document("hdd-monitor/accounts/clients/${panel.clientName}/panels/${panel.documentName}/relays/${updatedRelay.name}")
                    .update(updateData)
                    .await()

                _uiState.value = _uiState.value.copy(operationInProgress = false)

                updateRelayInState(panel.documentName, updatedRelay.name) { currentRelay ->
                    updatedRelay.copy(
                        status = currentRelay.status,
                        date_time = currentRelay.date_time
                    )
                }

                Log.d(TAG, "Relay ${updatedRelay.displayName} configurado exitosamente")

            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando configuración", e)
                _uiState.value = _uiState.value.copy(
                    operationInProgress = false,
                    error = "Error actualizando configuración: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun startStatusUpdatesListener() {
        statusUpdateJob = viewModelScope.launch {
            try {
                StatusUpdateManager.statusUpdates
                    .distinctUntilChanged()
                    .catch { error ->
                        Log.e(TAG, "Error en status updates", error)
                    }
                    .collect { update ->
                        if (!update.isEsp32) {
                            handleRelayUpdate(update.panelDocName, update.relayName, update.newStatus)
                        } else {
                            handleESP32Update(update.panelDocName, update.newStatus)
                        }
                    }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error en listener de actualizaciones", e)
                }
            }
        }
    }

    private fun handleRelayUpdate(panelDocName: String, relayName: String, newStatus: String) {
        updatePanelInState(panelDocName) { panel ->
            val updatedRelays = panel.relays.map { relay ->
                if (relay.name == relayName) {
                    relay.copy(status = newStatus)
                } else {
                    relay
                }
            }
            panel.copy(relays = updatedRelays)
        }
    }

    private fun handleESP32Update(panelDocName: String, newStatus: String) {
        updatePanelInState(panelDocName) { panel ->
            panel.copy(esp32Status = newStatus)
        }
    }

    private fun updatePanelInState(panelDocName: String, transform: (Panel) -> Panel) {
        val currentState = _uiState.value
        val updatedPanels = currentState.panels.map { panel ->
            if (panel.documentName == panelDocName) {
                transform(panel)
            } else {
                panel
            }
        }

        val updatedGroupedPanels = updatedPanels.groupBy { panel ->
            currentState.clientNames[panel.clientName] ?: panel.clientName
        }

        _uiState.value = currentState.copy(
            panels = updatedPanels,
            groupedPanels = updatedGroupedPanels
        )
    }

    private fun updateRelayInState(
        panelDocName: String,
        relayName: String,
        transform: (Relay) -> Relay
    ) {
        val currentState = _uiState.value
        val updatedPanels = currentState.panels.map { panel ->
            if (panel.documentName == panelDocName) {
                val updatedRelays = panel.relays.map { relay ->
                    if (relay.name == relayName) {
                        transform(relay)
                    } else {
                        relay
                    }
                }
                panel.copy(relays = updatedRelays)
            } else {
                panel
            }
        }

        val updatedGroupedPanels = updatedPanels.groupBy { panel ->
            currentState.clientNames[panel.clientName] ?: panel.clientName
        }

        _uiState.value = currentState.copy(
            panels = updatedPanels,
            groupedPanels = updatedGroupedPanels
        )
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
            Log.e(TAG, "Error cargando nombres de clientes", e)
            emptyMap()
        }
    }

    private fun cancelCurrentJob() {
        loadingJob?.cancel()
        loadingJob = null
    }

    fun cleanup() {
        Log.d(TAG, "Limpiando ViewModel")
        cancelCurrentJob()
        _uiState.value = _uiState.value.copy(operationInProgress = false)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "RelayControlViewModel limpiado")

        try {
            loadingJob?.cancel()
            statusUpdateJob?.cancel()

            loadingJob = null
            statusUpdateJob = null

            _uiState.value = RelayControlState()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCleared", e)
        }
    }
}