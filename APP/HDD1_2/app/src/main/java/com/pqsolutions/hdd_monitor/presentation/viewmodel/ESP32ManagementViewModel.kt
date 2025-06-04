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
        private const val REFRESH_INTERVAL = 30000L // 30 segundos
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
    }

    /**
     * Carga todos los datos necesarios para la pantalla
     */
    fun loadData() {
        // Cancelar job anterior si existe
        dataLoadingJob?.cancel()

        dataLoadingJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Iniciando carga de datos")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                // Obtener usuario actual
                val currentUser = userRepository.getCurrentUser()
                if (currentUser == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                    return@launch
                }

                Log.d(TAG, "Usuario: ${currentUser.name}, Rol: ${currentUser.role}")

                // Determinar qué datos cargar según el rol
                val clientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> null // Admin ve todos
                }

                // Combinar flujos de paneles y ESP32s
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

                        // Crear mapa de estados ESP32
                        val esp32StatusMap = buildMap {
                            assignedESP32s.forEach { esp32 ->
                                put(esp32.documentName, esp32.status)
                            }
                            unassignedESP32s.forEach { esp32 ->
                                put(esp32.documentName, esp32.status)
                            }
                        }

                        // Contar ESP32s online
                        val onlineCount = esp32StatusMap.values.count { status ->
                            status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
                        }

                        // Filtrar ESP32s disponibles (sin asignar y en estados válidos)
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

    /**
     * Refresca los datos manualmente
     */
    fun refreshData() {
        Log.d(TAG, "Refresh manual solicitado")

        viewModelScope.launch {
            try {
                // Limpiar cachés para forzar datos frescos
                esp32Repository.clearListeners()
                panelRepository.clearListeners()

                // Pequeña pausa para permitir limpieza
                delay(300)

                // Recargar datos
                loadData()

            } catch (e: Exception) {
                Log.e(TAG, "Error en refresh manual", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error actualizando datos: ${e.message}"
                )
            }
        }
    }

    /**
     * Elimina un panel y libera su ESP32
     */
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
                        // Para admin, necesitamos encontrar el cliente del panel
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
                        // Los datos se actualizarán automáticamente por los listeners
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

    /**
     * Obtiene información detallada de un panel para edición
     */
    fun getPanelForEdit(panelId: String): Panel? {
        return _uiState.value.panels.find { it.documentName == panelId }
    }

    /**
     * Obtiene ESP32s disponibles para asignación
     */
    fun getAvailableESP32s(): List<ESP32Device> {
        return _uiState.value.availableESP32s
    }

    /**
     * Verifica si hay ESP32s disponibles
     */
    fun hasAvailableESP32s(): Boolean {
        return _uiState.value.availableESP32s.isNotEmpty()
    }

    /**
     * Obtiene estadísticas resumidas
     */
    fun getSummaryStats(): Triple<Int, Int, Int> {
        val state = _uiState.value
        return Triple(
            state.panels.size,           // Total paneles
            state.onlineESP32Count,      // ESP32s online
            state.availableESP32s.size   // ESP32s disponibles
        )
    }

    /**
     * Verifica si un ESP32 específico está disponible
     */
    fun isESP32Available(esp32Id: String): Boolean {
        return _uiState.value.availableESP32s.any { it.documentName == esp32Id }
    }

    /**
     * Obtiene el estado de un ESP32 específico
     */
    fun getESP32Status(esp32Id: String): String? {
        return _uiState.value.esp32StatusMap[esp32Id]
    }

    /**
     * Limpia errores del estado
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Escucha actualizaciones de estado en tiempo real
     */
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

    /**
     * Maneja actualizaciones de estado ESP32
     */
    private fun handleESP32Update(panelDocName: String, newStatus: String) {
        Log.d(TAG, "ESP32 del panel $panelDocName cambió a $newStatus")

        val currentState = _uiState.value

        // Actualizar mapa de estados ESP32
        val updatedStatusMap = currentState.esp32StatusMap.toMutableMap()

        // Buscar el ESP32 asociado al panel
        val panel = currentState.panels.find { it.documentName == panelDocName }
        panel?.let {
            updatedStatusMap[it.esp32_id] = newStatus
        }

        // Recalcular ESP32s online
        val onlineCount = updatedStatusMap.values.count { status ->
            status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
        }

        _uiState.value = currentState.copy(
            esp32StatusMap = updatedStatusMap,
            onlineESP32Count = onlineCount,
            lastUpdate = System.currentTimeMillis()
        )
    }

    /**
     * Maneja actualizaciones de relays (para actualizar estados de paneles)
     */
    private fun handleRelayUpdate(panelDocName: String, relayName: String, newStatus: String) {
        Log.d(TAG, "Relay $relayName del panel $panelDocName cambió a $newStatus")

        // Los paneles se actualizarán automáticamente por el flow del PanelRepository
        // Solo necesitamos actualizar el timestamp
        _uiState.value = _uiState.value.copy(
            lastUpdate = System.currentTimeMillis()
        )
    }

    /**
     * Inicia refresh periódico automático
     */
    private fun startPeriodicRefresh() {
        refreshJob = viewModelScope.launch {
            while (true) {
                try {
                    delay(REFRESH_INTERVAL)

                    // Solo hacer refresh si no estamos cargando
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

    /**
     * Actualiza solo los estados de ESP32 sin recargar todo
     */
    private suspend fun refreshESP32States() {
        try {
            val currentState = _uiState.value
            val updatedStatusMap = currentState.esp32StatusMap.toMutableMap()
            var hasChanges = false

            // Verificar estado de cada ESP32
            currentState.panels.forEach { panel ->
                if (panel.esp32_id.isNotEmpty()) {
                    try {
                        // Aquí podrías implementar una verificación rápida del estado
                        // Por ahora, confiamos en los listeners de tiempo real
                    } catch (e: Exception) {
                        Log.e(TAG, "Error verificando ESP32 ${panel.esp32_id}", e)
                    }
                }
            }

            // Actualizar solo si hay cambios
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

    /**
     * Detiene el refresh periódico
     */
    fun stopPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel limpiado - cancelando jobs")

        // Cancelar todos los jobs
        dataLoadingJob?.cancel()
        statusUpdateJob?.cancel()
        refreshJob?.cancel()

        // Limpiar listeners de repositorios
        esp32Repository.clearListeners()
        panelRepository.clearListeners()

        Log.d(TAG, "ViewModel limpiado exitosamente")
    }
}