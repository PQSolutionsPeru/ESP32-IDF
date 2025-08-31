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
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class ESP32ManagementViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val esp32Repository: ESP32Repository,
    private val userRepository: UserRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ESP32ManagementViewModel"
        private const val LOADING_TIMEOUT_MS = 10000L
    }

    private val _uiState = MutableStateFlow(ESP32ManagementState())
    val uiState: StateFlow<ESP32ManagementState> = _uiState.asStateFlow()

    private var panelsJob: Job? = null
    private var assignedESP32Job: Job? = null
    private var unassignedESP32Job: Job? = null
    private var loadingTimeoutJob: Job? = null
    private var currentClientDocName: String? = null

    private var panelsLoaded = false
    private var panelsInitialLoadComplete = false
    private var assignedESP32sLoaded = false
    private var unassignedESP32sLoaded = false

    init {
        Log.d(TAG, "ESP32ManagementViewModel initialized")
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadData()
    }

    fun onResume() {
        Log.d(TAG, "ESP32ManagementViewModel resumed - forzando recarga de listeners")

        cancelExistingJobs()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        resetLoadingFlags()
        loadData()
    }

    private fun resetLoadingFlags() {
        panelsLoaded = false
        panelsInitialLoadComplete = false
        assignedESP32sLoaded = false
        unassignedESP32sLoaded = false
    }

    private fun cancelExistingJobs() {
        panelsJob?.cancel()
        assignedESP32Job?.cancel()
        unassignedESP32Job?.cancel()
        loadingTimeoutJob?.cancel()

        panelsJob = null
        assignedESP32Job = null
        unassignedESP32Job = null
        loadingTimeoutJob = null
    }

    private fun loadData() {
        Log.d(TAG, "Iniciando carga de datos con listeners frescos")

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

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

                currentClientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> null
                }

                cancelExistingJobs()
                startFreshDataListeners(currentClientDocName)

            } catch (e: Exception) {
                Log.e(TAG, "Error cargando datos", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error cargando datos: ${e.message}"
                )
            }
        }
    }

    private fun startFreshDataListeners(clientDocName: String?) {
        Log.d(TAG, "Iniciando listeners frescos para cliente: $clientDocName")

        startLoadingTimeout()

        panelsJob = viewModelScope.launch {
            Log.d(TAG, "Iniciando listener de paneles")
            panelRepository.getPanels(clientDocName)
                .catch { error ->
                    Log.e(TAG, "Error en listener de paneles", error)
                    panelsLoaded = true
                    panelsInitialLoadComplete = true
                    checkAndUpdateLoadingState()
                    _uiState.value = _uiState.value.copy(
                        error = "Error cargando paneles: ${error.message}"
                    )
                }
                .collect { panels ->
                    Log.d(TAG, "Paneles recibidos: ${panels.size}")

                    if (!panelsInitialLoadComplete) {
                        panelsInitialLoadComplete = true
                        Log.d(TAG, "Carga inicial de paneles completada")
                    }

                    panelsLoaded = true
                    updatePanels(panels)
                    checkAndUpdateLoadingState()
                }
        }

        assignedESP32Job = viewModelScope.launch {
            Log.d(TAG, "Iniciando listener de ESP32s asignados")
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
            Log.d(TAG, "Iniciando listener de ESP32s no asignados")
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

    private fun startLoadingTimeout() {
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = viewModelScope.launch {
            delay(LOADING_TIMEOUT_MS)
            if (!panelsInitialLoadComplete) {
                Log.w(TAG, "Loading timeout alcanzado, forzando completion")
                panelsInitialLoadComplete = true
                panelsLoaded = true
                checkAndUpdateLoadingState()
            }
        }
    }

    private fun checkAndUpdateLoadingState() {
        if (panelsInitialLoadComplete && assignedESP32sLoaded && unassignedESP32sLoaded) {
            loadingTimeoutJob?.cancel()
            _uiState.value = _uiState.value.copy(isLoading = false)
            Log.d(TAG, "Todos los datos cargados correctamente")
        }
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
        Log.d(TAG, "Refresh manual solicitado - reiniciando todos los listeners")
        onResume()
    }

    fun forceRefresh() {
        Log.d(TAG, "Refresh forzado solicitado")

        viewModelScope.launch {
            try {
                panelRepository.performPeriodicCleanup()
                esp32Repository.clearListeners()

                kotlinx.coroutines.delay(1000)

                onResume()
            } catch (e: Exception) {
                Log.e(TAG, "Error en refresh forzado", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error en refresh: ${e.message}"
                )
            }
        }
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
        Log.d(TAG, "ViewModel siendo limpiado")
        cancelExistingJobs()
    }
}