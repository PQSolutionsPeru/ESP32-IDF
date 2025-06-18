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
    private var isInitialized = false

    private var panelsReceived = false
    private var assignedESP32sReceived = false
    private var unassignedESP32sReceived = false

    fun initializeIfNeeded() {
        if (!isInitialized) {
            isInitialized = true
            Log.d(TAG, "ESP32ManagementViewModel inicializado")
            loadData()
        }
    }

    private fun loadData() {
        if (_uiState.value.isLoading) {
            Log.d(TAG, "Ya está cargando datos, saltando")
            return
        }

        Log.d(TAG, "Iniciando carga de datos")

        panelsReceived = false
        assignedESP32sReceived = false
        unassignedESP32sReceived = false

        viewModelScope.launch {
            try {
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
        cancelJobs()

        panelsJob = viewModelScope.launch {
            panelRepository.getPanels(clientDocName)
                .catch { error ->
                    Log.e(TAG, "Error en listener de paneles", error)
                    panelsReceived = true
                    checkInitialDataLoaded()
                }
                .collect { panels ->
                    Log.d(TAG, "Paneles recibidos: ${panels.size}")
                    updatePanels(panels)
                    if (!panelsReceived) {
                        panelsReceived = true
                        checkInitialDataLoaded()
                    }
                }
        }

        assignedESP32Job = viewModelScope.launch {
            esp32Repository.observeAssignedESP32s(clientDocName)
                .catch { error ->
                    Log.e(TAG, "Error en listener de ESP32s asignados", error)
                    assignedESP32sReceived = true
                    checkInitialDataLoaded()
                }
                .collect { assignedESP32s ->
                    Log.d(TAG, "ESP32s asignados recibidos: ${assignedESP32s.size}")
                    updateAssignedESP32s(assignedESP32s)
                    if (!assignedESP32sReceived) {
                        assignedESP32sReceived = true
                        checkInitialDataLoaded()
                    }
                }
        }

        unassignedESP32Job = viewModelScope.launch {
            esp32Repository.observeUnassignedESP32s()
                .catch { error ->
                    Log.e(TAG, "Error en listener de ESP32s no asignados", error)
                    unassignedESP32sReceived = true
                    checkInitialDataLoaded()
                }
                .collect { unassignedESP32s ->
                    Log.d(TAG, "ESP32s no asignados recibidos: ${unassignedESP32s.size}")
                    updateUnassignedESP32s(unassignedESP32s)
                    if (!unassignedESP32sReceived) {
                        unassignedESP32sReceived = true
                        checkInitialDataLoaded()
                    }
                }
        }
    }

    private fun checkInitialDataLoaded() {
        if (panelsReceived && assignedESP32sReceived && unassignedESP32sReceived) {
            if (_uiState.value.isLoading) {
                Log.d(TAG, "Datos iniciales cargados completamente")
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun cancelJobs() {
        panelsJob?.cancel()
        assignedESP32Job?.cancel()
        unassignedESP32Job?.cancel()
    }

    private fun updatePanels(panels: List<Panel>) {
        val currentState = _uiState.value
        _uiState.value = currentState.copy(
            panels = panels,
            lastUpdate = System.currentTimeMillis()
        )
        updateStats()
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
        try {
            super.onCleared()
            Log.d(TAG, "ESP32ManagementViewModel limpiado - cancelando jobs")

            cancelJobs()

            try {
                esp32Repository.clearListeners()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing ESP32 listeners", e)
            }

            try {
                panelRepository.clearListeners()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing panel listeners", e)
            }

            Log.d(TAG, "ESP32ManagementViewModel limpiado exitosamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error in ESP32ManagementViewModel.onCleared", e)
        }
    }
}