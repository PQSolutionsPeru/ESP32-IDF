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
import kotlinx.coroutines.delay
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
import com.pqsolutions.hdd_monitor.data.RelayConfiguration

@HiltViewModel
class RelayControlViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val relayControlRepository: RelayControlRepository,
    private val userRepository: UserRepository,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    companion object {
        private const val TAG = "RelayControlViewModel"
        private const val COMMAND_TIMEOUT = 10000L
        private const val REFRESH_INTERVAL = 30000L
    }

    private val _uiState = MutableStateFlow(RelayControlState())
    val uiState: StateFlow<RelayControlState> = _uiState.asStateFlow()

    private var loadingJob: Job? = null
    private var statusUpdateJob: Job? = null
    private var refreshJob: Job? = null

    private val pendingCommands = mutableMapOf<String, Long>()

    init {
        Log.d(TAG, "RelayControlViewModel inicializado")
        startStatusUpdatesListener()
        startPeriodicRefresh()
    }

    fun loadPanels() {
        loadingJob?.let { job ->
            if (job.isActive) {
                job.cancel()
            }
        }

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

                Log.d(TAG, "Usuario: ${currentUser.name}, Rol: ${currentUser.role}")

                val clientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> null
                }

                val clientsMap = loadClientNames(clientDocName)
                _uiState.value = _uiState.value.copy(clientNames = clientsMap)

                panelRepository.getPanels(clientDocName)
                    .catch { error ->
                        if (error is kotlinx.coroutines.CancellationException) {
                            Log.d(TAG, "Carga de paneles cancelada (normal)")
                            return@catch
                        }

                        Log.e(TAG, "Error en flow de paneles", error)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Error cargando paneles: ${error.message}"
                        )
                    }
                    .collect { panels ->
                        if (!currentCoroutineContext().isActive) {
                            Log.d(TAG, "Corrutina cancelada, no actualizando UI")
                            return@collect
                        }

                        Log.d(TAG, "Paneles recibidos: ${panels.size}")

                        val validPanels = panels

                        val groupedPanels = validPanels.groupBy { panel ->
                            clientsMap[panel.clientName] ?: panel.clientName
                        }

                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            panels = validPanels,
                            groupedPanels = groupedPanels,
                            error = null
                        )

                        Log.d(TAG, "Estado actualizado - ${validPanels.size} paneles válidos")
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
                        date_time = currentRelay.date_time,
                        lastCommandSent = currentRelay.lastCommandSent,
                        commandSource = currentRelay.commandSource
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
                        Log.d(TAG, "Actualización recibida: ${update.panelDocName}")

                        if (!update.isEsp32) {
                            handleRelayUpdate(update.panelDocName, update.relayName, update.newStatus)
                        } else {
                            handleESP32Update(update.panelDocName, update.newStatus)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error en listener de actualizaciones", e)
            }
        }
    }

    private fun handleRelayUpdate(panelDocName: String, relayName: String, newStatus: String) {
        Log.d(TAG, "Relay $relayName del panel $panelDocName cambió a $newStatus")

        val commandKey = pendingCommands.keys.find {
            it.contains("${panelDocName}_$relayName")
        }
        if (commandKey != null) {
            Log.d(TAG, "Comando confirmado: $commandKey")
            pendingCommands.remove(commandKey)
        }

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

        _uiState.value = _uiState.value.copy(
            operationInProgress = pendingCommands.isNotEmpty()
        )
    }

    private fun handleESP32Update(panelDocName: String, newStatus: String) {
        Log.d(TAG, "ESP32 del panel $panelDocName cambió a $newStatus")

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

    private fun startPeriodicRefresh() {
        refreshJob = viewModelScope.launch {
            while (true) {
                try {
                    delay(REFRESH_INTERVAL)

                    val currentState = _uiState.value
                    if (!currentState.isLoading && currentState.panels.isNotEmpty()) {
                        Log.d(TAG, "Refresh periódico automático")
                        refreshPanelStates()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        Log.d(TAG, "Refresh periódico cancelado")
                        break
                    }
                    Log.e(TAG, "Error en refresh periódico", e)
                }
            }
        }
    }

    private suspend fun refreshPanelStates() {
        try {
            val currentPanels = _uiState.value.panels
            currentPanels.forEach { panel ->
                relayControlRepository.checkESP32Status(panel.esp32_id)
                    .onSuccess { status ->
                        if (panel.esp32Status != status) {
                            handleESP32Update(panel.documentName, status)
                        }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Error verificando ESP32 ${panel.esp32_id}", error)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en refresh de estados", e)
        }
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

    fun stopPeriodicRefresh() {
        Log.d(TAG, "Refresh periódico cancelado")
        refreshJob?.cancel()
        refreshJob = null

        _uiState.value = _uiState.value.copy(
            operationInProgress = false
        )
    }

    fun cleanup() {
        Log.d(TAG, "Limpiando estado del ViewModel")

        loadingJob?.cancel()
        statusUpdateJob?.cancel()
        refreshJob?.cancel()

        pendingCommands.clear()

        _uiState.value = RelayControlState()

        Log.d(TAG, "Estado del ViewModel limpiado")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel limpiado - cancelando jobs")

        loadingJob?.cancel()
        statusUpdateJob?.cancel()
        refreshJob?.cancel()

        pendingCommands.clear()

        _uiState.value = _uiState.value.copy(
            operationInProgress = false,
            isLoading = false,
            error = null
        )

        Log.d(TAG, "ViewModel limpiado exitosamente")
    }
}