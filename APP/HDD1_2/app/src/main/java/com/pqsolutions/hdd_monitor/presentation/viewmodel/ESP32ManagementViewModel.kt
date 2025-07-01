package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import com.pqsolutions.hdd_monitor.presentation.state.ESP32ManagementState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ESP32ManagementViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val esp32Repository: ESP32Repository,
    private val userRepository: UserRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ESP32ManagementViewModel"
    }

    private val _uiState = MutableStateFlow(ESP32ManagementState())
    val uiState: StateFlow<ESP32ManagementState> = _uiState.asStateFlow()

    private var panelsJob: Job? = null
    private var assignedESP32Job: Job? = null
    private var unassignedESP32Job: Job? = null

    private var panelsLoaded = false
    private var assignedESP32sLoaded = false
    private var unassignedESP32sLoaded = false

    init {
        Log.d(TAG, "ESP32ManagementViewModel initialized")
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadData()
    }

    fun onResume() {
        Log.d(TAG, "ESP32ManagementViewModel resumed - verificando estado de listeners")

        if (panelsJob?.isActive != true && assignedESP32Job?.isActive != true && unassignedESP32Job?.isActive != true) {
            Log.d(TAG, "Listeners no activos, recargando datos")
            loadData()
        }
    }

    private fun loadData() {
        Log.d(TAG, "Iniciando carga de datos")

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        panelsLoaded = false
        assignedESP32sLoaded = false
        unassignedESP32sLoaded = false

        viewModelScope.launch {
            try {
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

                cancelJobs()
                startDataListeners(clientDocName)

            } catch (e: Exception) {
                Log.e(TAG, "Error cargando datos", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error cargando datos: ${e.message}"
                )
            }
        }
    }

    private fun startDataListeners(clientDocName: String?) {
        panelsJob = viewModelScope.launch {
            panelRepository.getPanels(clientDocName)
                .catch { error ->
                    Log.e(TAG, "Error en listener de paneles", error)
                    panelsLoaded = true
                    checkAndUpdateLoadingState()
                    _uiState.value = _uiState.value.copy(
                        error = "Error cargando paneles: ${error.message}"
                    )
                }
                .collect { panels ->
                    Log.d(TAG, "Paneles recibidos: ${panels.size}")

                    panels.forEach { panel ->
                        Log.d(TAG, "Panel ESP32: ${panel.name}, Relays: ${panel.relays.size}, Active: ${panel.activeRelays.size}, HasIssues: ${panel.hasIssues}")
                        panel.relays.forEach { relay ->
                            Log.d(TAG, "  Relay ESP32: ${relay.name}, isActive: ${relay.isActive}, status: ${relay.status}")
                        }
                    }

                    panelsLoaded = true
                    updatePanels(panels)
                    checkAndUpdateLoadingState()
                }
        }

        assignedESP32Job = viewModelScope.launch {
            esp32Repository.observeAssignedESP32s(clientDocName)
                .catch { error ->
                    Log.e(TAG, "Error en listener de ESP32s asignados", error)
                    assignedESP32sLoaded = true
                    checkAndUpdateLoadingState()
                }
                .collect { assignedESP32s ->
                    Log.d(TAG, "ESP32s asignados recibidos: ${assignedESP32s.size}")
                    assignedESP32sLoaded = true
                    updateAssignedESP32s(assignedESP32s)
                    checkAndUpdateLoadingState()
                }
        }

        unassignedESP32Job = viewModelScope.launch {
            esp32Repository.observeUnassignedESP32s()
                .catch { error ->
                    Log.e(TAG, "Error en listener de ESP32s no asignados", error)
                    unassignedESP32sLoaded = true
                    checkAndUpdateLoadingState()
                }
                .collect { unassignedESP32s ->
                    Log.d(TAG, "ESP32s no asignados recibidos: ${unassignedESP32s.size}")
                    unassignedESP32sLoaded = true
                    updateUnassignedESP32s(unassignedESP32s)
                    checkAndUpdateLoadingState()
                }
        }
    }

    private fun checkAndUpdateLoadingState() {
        if (panelsLoaded && assignedESP32sLoaded && unassignedESP32sLoaded) {
            val hasData = _uiState.value.panels.isNotEmpty() || _uiState.value.availableESP32s.isNotEmpty()
            if (_uiState.value.isLoading && hasData) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                Log.d(TAG, "Todos los datos cargados con contenido, ocultando loading")
            } else if (_uiState.value.isLoading && !hasData) {
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1000)
                    if (_uiState.value.isLoading) {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        Log.d(TAG, "Timeout de loading alcanzado, ocultando loading")
                    }
                }
            }
        }
    }

    private fun cancelJobs() {
        Log.d(TAG, "cancelJobs called - maintaining jobs for continuous monitoring")
    }

    private fun updatePanels(panels: List<Panel>) {
        Log.d(TAG, "Actualizando paneles en estado: ${panels.size}")

        panels.forEach { panel ->
            val activeRelaysInDisc = panel.activeRelays.count { it.status == "DISC" }
            Log.d(TAG, "Panel: ${panel.name}, ESP32: ${panel.esp32_id}, HasIssues: ${panel.hasIssues}, ActiveRelaysInDisc: $activeRelaysInDisc")
        }

        val currentState = _uiState.value
        _uiState.value = currentState.copy(
            panels = panels,
            lastUpdate = System.currentTimeMillis()
        )
        updateStats()
        Log.d(TAG, "Estado de paneles actualizado correctamente")
    }

    private fun updateAssignedESP32s(assignedESP32s: List<ESP32Device>) {
        val currentState = _uiState.value
        val updatedStatusMap = currentState.esp32StatusMap.toMutableMap()

        assignedESP32s.forEach { esp32 ->
            updatedStatusMap[esp32.documentName] = esp32.status
        }

        _uiState.value = currentState.copy(
            esp32StatusMap = updatedStatusMap,
            lastUpdate = System.currentTimeMillis()
        )
        updateStats()
        Log.d(TAG, "ESP32s asignados actualizados: ${assignedESP32s.size}")
    }

    private fun updateUnassignedESP32s(unassignedESP32s: List<ESP32Device>) {
        val availableESP32s = unassignedESP32s.filter { esp32 ->
            esp32.status in listOf(
                ESP32Device.STATUS_AWAITING_CONFIG,
                ESP32Device.STATUS_PENDING_ASSIGNMENT,
                ESP32Device.STATUS_ONLINE,
                ESP32Device.STATUS_RUNNING
            )
        }

        val currentState = _uiState.value
        val updatedStatusMap = currentState.esp32StatusMap.toMutableMap()

        unassignedESP32s.forEach { esp32 ->
            updatedStatusMap[esp32.documentName] = esp32.status
        }

        _uiState.value = currentState.copy(
            availableESP32s = availableESP32s,
            esp32StatusMap = updatedStatusMap,
            lastUpdate = System.currentTimeMillis()
        )
        updateStats()
        Log.d(TAG, "ESP32s disponibles actualizados: ${availableESP32s.size}")
    }

    private fun updateStats() {
        val currentState = _uiState.value
        val onlineCount = currentState.esp32StatusMap.values.count { status ->
            status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
        }

        _uiState.value = currentState.copy(
            onlineESP32Count = onlineCount
        )
    }

    fun refreshData() {
        Log.d(TAG, "Refresh manual solicitado")
        loadData()
    }

    fun deletePanel(panelId: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Eliminando panel: $panelId")

                val currentUser = userRepository.getCurrentUser()
                if (currentUser == null) {
                    _uiState.value = _uiState.value.copy(error = "Usuario no encontrado")
                    return@launch
                }

                val clientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> {
                        val panel = _uiState.value.panels.find { it.documentName == panelId }
                        panel?.clientName ?: run {
                            _uiState.value = _uiState.value.copy(error = "Panel no encontrado")
                            return@launch
                        }
                    }
                }

                panelRepository.deletePanel(clientDocName, panelId)
                    .onSuccess {
                        Log.d(TAG, "Panel eliminado exitosamente")
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Error eliminando panel", error)
                        _uiState.value = _uiState.value.copy(
                            error = "Error eliminando panel: ${error.message}"
                        )
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Error en deletePanel", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error eliminando panel: ${e.message}"
                )
            }
        }
    }

    fun getPanelForEdit(panelId: String): Panel? {
        return _uiState.value.panels.find { it.documentName == panelId }
    }

    fun getAvailableESP32s(): List<ESP32Device> {
        return _uiState.value.availableESP32s
    }

    fun hasAvailableESP32s(): Boolean {
        return _uiState.value.availableESP32s.isNotEmpty()
    }

    fun getSummaryStats(): Triple<Int, Int, Int> {
        val state = _uiState.value
        return Triple(
            state.panels.size,
            state.onlineESP32Count,
            state.availableESP32s.size
        )
    }

    fun isESP32Available(esp32Id: String): Boolean {
        return _uiState.value.availableESP32s.any { it.documentName == esp32Id }
    }

    fun getESP32Status(esp32Id: String): String? {
        return _uiState.value.esp32StatusMap[esp32Id]
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
    }
}