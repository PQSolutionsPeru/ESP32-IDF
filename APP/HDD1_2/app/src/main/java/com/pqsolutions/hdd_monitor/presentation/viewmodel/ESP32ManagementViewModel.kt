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
import com.pqsolutions.hdd_monitor.util.StatusUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
        private const val REFRESH_INTERVAL = 30000L
    }

    private val _uiState = MutableStateFlow(ESP32ManagementState())
    val uiState: StateFlow<ESP32ManagementState> = _uiState.asStateFlow()

    private var dataLoadingJob: Job? = null
    private var statusUpdateJob: Job? = null
    private var refreshJob: Job? = null

    init {
        Log.d(TAG, "ESP32ManagementViewModel inicializado")
        startStatusUpdatesListener()
        startPeriodicRefresh()
        loadData()
    }

    fun loadData() {
        if (dataLoadingJob?.isActive == true) {
            Log.d(TAG, "Ya hay una carga de datos en progreso, ignorando solicitud")
            return
        }

        dataLoadingJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Iniciando carga de datos")
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

                combine(
                    panelRepository.getPanels(clientDocName),
                    esp32Repository.observeAssignedESP32s(clientDocName),
                    esp32Repository.observeUnassignedESP32s()
                ) { panels, assignedESP32s, unassignedESP32s ->
                    Triple(panels, assignedESP32s, unassignedESP32s)
                }
                    .catch { error ->
                        Log.e(TAG, "Error en flujos combinados", error)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Error cargando datos: ${error.message}"
                        )
                    }
                    .collect { (panels, assignedESP32s, unassignedESP32s) ->
                        Log.d(TAG, "Datos recibidos - Paneles: ${panels.size}, ESP32s asignados: ${assignedESP32s.size}, ESP32s disponibles: ${unassignedESP32s.size}")

                        val esp32StatusMap = buildMap {
                            assignedESP32s.forEach { esp32 ->
                                put(esp32.documentName, esp32.status)
                            }
                            unassignedESP32s.forEach { esp32 ->
                                put(esp32.documentName, esp32.status)
                            }
                        }

                        val onlineCount = esp32StatusMap.values.count { status ->
                            status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
                        }

                        val availableESP32s = unassignedESP32s.filter { esp32 ->
                            esp32.status in listOf(
                                ESP32Device.STATUS_AWAITING_CONFIG,
                                ESP32Device.STATUS_PENDING_ASSIGNMENT,
                                ESP32Device.STATUS_ONLINE,
                                ESP32Device.STATUS_RUNNING
                            )
                        }

                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            panels = panels,
                            availableESP32s = availableESP32s,
                            esp32StatusMap = esp32StatusMap,
                            onlineESP32Count = onlineCount,
                            error = null,
                            lastUpdate = System.currentTimeMillis()
                        )

                        Log.d(TAG, "Estado actualizado - ${panels.size} paneles, ${availableESP32s.size} ESP32s disponibles, $onlineCount online")
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Error cargando datos", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error cargando datos: ${e.message}"
                )
            }
        }
    }

    fun refreshData() {
        Log.d(TAG, "Refresh manual solicitado")

        viewModelScope.launch {
            try {
                dataLoadingJob?.cancel()
                dataLoadingJob?.join()

                esp32Repository.clearListeners()
                panelRepository.clearListeners()

                delay(300)

                loadData()

            } catch (e: Exception) {
                Log.e(TAG, "Error en refresh manual", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error actualizando datos: ${e.message}"
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

                        if (update.isEsp32) {
                            handleESP32Update(update.panelDocName, update.newStatus)
                        } else {
                            handleRelayUpdate(update.panelDocName, update.relayName, update.newStatus)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error en listener de actualizaciones", e)
            }
        }
    }

    private fun handleESP32Update(panelDocName: String, newStatus: String) {
        Log.d(TAG, "ESP32 del panel $panelDocName cambió a $newStatus")

        val currentState = _uiState.value

        val updatedStatusMap = currentState.esp32StatusMap.toMutableMap()

        val panel = currentState.panels.find { it.documentName == panelDocName }
        panel?.let {
            updatedStatusMap[it.esp32_id] = newStatus
        }

        val onlineCount = updatedStatusMap.values.count { status ->
            status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
        }

        _uiState.value = currentState.copy(
            esp32StatusMap = updatedStatusMap,
            onlineESP32Count = onlineCount,
            lastUpdate = System.currentTimeMillis()
        )
    }

    private fun handleRelayUpdate(panelDocName: String, relayName: String, newStatus: String) {
        Log.d(TAG, "Relay $relayName del panel $panelDocName cambió a $newStatus")

        _uiState.value = _uiState.value.copy(
            lastUpdate = System.currentTimeMillis()
        )
    }

    private fun startPeriodicRefresh() {
        refreshJob = viewModelScope.launch {
            while (true) {
                try {
                    delay(REFRESH_INTERVAL)

                    val currentState = _uiState.value
                    if (!currentState.isLoading) {
                        Log.d(TAG, "Refresh periódico automático")
                        refreshESP32States()
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

    private suspend fun refreshESP32States() {
        try {
            val currentState = _uiState.value
            val updatedStatusMap = currentState.esp32StatusMap.toMutableMap()
            var hasChanges = false

            currentState.panels.forEach { panel ->
                if (panel.esp32_id.isNotEmpty()) {
                    try {
                    } catch (e: Exception) {
                        Log.e(TAG, "Error verificando ESP32 ${panel.esp32_id}", e)
                    }
                }
            }

            if (hasChanges) {
                val onlineCount = updatedStatusMap.values.count { status ->
                    status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
                }

                _uiState.value = currentState.copy(
                    esp32StatusMap = updatedStatusMap,
                    onlineESP32Count = onlineCount,
                    lastUpdate = System.currentTimeMillis()
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en refresh de estados ESP32", e)
        }
    }

    fun stopPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel limpiado - cancelando jobs")

        dataLoadingJob?.cancel()
        statusUpdateJob?.cancel()
        refreshJob?.cancel()

        esp32Repository.clearListeners()
        panelRepository.clearListeners()

        Log.d(TAG, "ViewModel limpiado exitosamente")
    }
}