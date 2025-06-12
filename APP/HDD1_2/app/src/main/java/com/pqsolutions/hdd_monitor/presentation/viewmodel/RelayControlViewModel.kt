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

    // Un solo job para manejar toda la carga de paneles
    private var loadingJob: Job? = null
    private var statusUpdateJob: Job? = null
    private var refreshJob: Job? = null

    // Cache de comandos pendientes
    private val pendingCommands = mutableMapOf<String, Long>()

    init {
        Log.d(TAG, "RelayControlViewModel inicializado")
        startStatusUpdatesListener()
        startPeriodicRefresh()
    }

    /**
     * Carga los paneles - simplificado para evitar race conditions
     */
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

    /**
     * Refresca los paneles manualmente
     */
    fun refreshPanels() {
        Log.d(TAG, "Refresh manual solicitado")
        loadPanels()
    }

    /**
     * Alterna el estado de un relay
     */
    fun toggleRelay(panel: Panel, relay: Relay) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Toggle relay ${relay.name} del panel ${panel.name}")

                // Verificar ESP32 online
                if (panel.isESP32Offline()) {
                    _uiState.value = _uiState.value.copy(
                        error = "ESP32 desconectado - No se puede controlar el relay"
                    )
                    return@launch
                }

                // Generar ID único para comando
                val commandId = "${panel.esp32_id}_${relay.name}_${System.currentTimeMillis()}"
                val newStatus = if (relay.status == "OK") "DISC" else "OK"

                // Marcar operación en progreso
                _uiState.value = _uiState.value.copy(operationInProgress = true)

                Log.d(TAG, "Enviando comando: ${relay.name} -> $newStatus")

                // Enviar comando
                val success = relayControlRepository.sendRelayCommand(
                    esp32Id = panel.esp32_id,
                    clientId = panel.clientName,
                    panelId = panel.documentName,
                    relayName = relay.name,
                    newStatus = newStatus,
                    commandId = commandId
                )

                if (success) {
                    // Agregar a comandos pendientes
                    pendingCommands[commandId] = System.currentTimeMillis()

                    // Timeout para el comando
                    launch {
                        delay(COMMAND_TIMEOUT)
                        if (pendingCommands.remove(commandId) != null) {
                            Log.w(TAG, "Timeout para comando: $commandId")
                            _uiState.value = _uiState.value.copy(
                                operationInProgress = pendingCommands.isNotEmpty(),
                                error = "Timeout - El comando no fue confirmado"
                            )
                        }
                    }

                    Log.d(TAG, "Comando enviado exitosamente: $commandId")
                } else {
                    _uiState.value = _uiState.value.copy(
                        operationInProgress = false,
                        error = "Error enviando comando al ESP32"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en toggleRelay", e)
                _uiState.value = _uiState.value.copy(
                    operationInProgress = false,
                    error = "Error controlando relay: ${e.message}"
                )
            }
        }
    }

    /**
     * Actualiza la configuración de un relay
     */
    fun updateRelayConfig(panel: Panel, updatedRelay: Relay) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Actualizando configuración completa del relay ${updatedRelay.name}")

                _uiState.value = _uiState.value.copy(operationInProgress = true)

                // Usar el nuevo método para actualización completa
                val success = relayControlRepository.updateCompleteRelayConfiguration(
                    clientId = panel.clientName,
                    panelId = panel.documentName,
                    relayName = updatedRelay.name,
                    customName = updatedRelay.customName,
                    isActive = updatedRelay.isActive,
                    isControllable = updatedRelay.isControllable,
                    contactType = updatedRelay.contactType
                )

                _uiState.value = _uiState.value.copy(operationInProgress = false)

                if (success) {
                    Log.d(TAG, "Configuración actualizada exitosamente")

                    // Actualizar inmediatamente en el estado local para UI responsiva
                    updateRelayInState(panel.documentName, updatedRelay.name) { _ ->
                        updatedRelay.copy(
                            // Mantener campos que no se modifican en configuración
                            status = updatedRelay.status,
                            date_time = updatedRelay.date_time,
                            lastCommandSent = updatedRelay.lastCommandSent,
                            commandSource = updatedRelay.commandSource
                        )
                    }

                    // Mensaje de éxito (opcional - podrías agregarlo al estado)
                    Log.d(TAG, "Relay ${updatedRelay.displayName} configurado exitosamente")

                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Error actualizando configuración del relay"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando configuración completa", e)
                _uiState.value = _uiState.value.copy(
                    operationInProgress = false,
                    error = "Error actualizando configuración: ${e.message}"
                )
            }
        }
    }

    /**
     * Limpia los errores del estado
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Escucha actualizaciones de estado - simplificado
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

    /**
     * Maneja actualizaciones de relay
     */
    private fun handleRelayUpdate(panelDocName: String, relayName: String, newStatus: String) {
        Log.d(TAG, "Relay $relayName del panel $panelDocName cambió a $newStatus")

        // Verificar comando pendiente
        val commandKey = pendingCommands.keys.find {
            it.contains("${panelDocName}_$relayName")
        }
        if (commandKey != null) {
            Log.d(TAG, "Comando confirmado: $commandKey")
            pendingCommands.remove(commandKey)
        }

        // Actualizar UI
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

        // Actualizar estado de operación
        _uiState.value = _uiState.value.copy(
            operationInProgress = pendingCommands.isNotEmpty()
        )
    }

    /**
     * Maneja actualizaciones de ESP32
     */
    private fun handleESP32Update(panelDocName: String, newStatus: String) {
        Log.d(TAG, "ESP32 del panel $panelDocName cambió a $newStatus")

        updatePanelInState(panelDocName) { panel ->
            panel.copy(esp32Status = newStatus)
        }
    }

    /**
     * Actualiza un panel específico en el estado
     */
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

    /**
     * Inicia refresh periódico
     */
    private fun startPeriodicRefresh() {
        refreshJob = viewModelScope.launch {
            while (true) {
                try {
                    delay(REFRESH_INTERVAL)

                    // Solo hacer refresh si no estamos cargando y tenemos paneles
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

    /**
     * Actualiza estados de paneles sin recargar todo
     */
    private suspend fun refreshPanelStates() {
        try {
            val currentPanels = _uiState.value.panels
            currentPanels.forEach { panel ->
                // Solo verificar estado ESP32 sin recargar todo
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

    fun updateRelayCustomName(
        panel: Panel,
        relay: Relay,
        newCustomName: String
    ) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Actualizando nombre personalizado del relay ${relay.name} a: $newCustomName")

                _uiState.value = _uiState.value.copy(operationInProgress = true)

                val success = relayControlRepository.updateRelayName(
                    clientId = panel.clientName,
                    panelId = panel.documentName,
                    relayName = relay.name,
                    customName = newCustomName
                )

                _uiState.value = _uiState.value.copy(operationInProgress = false)

                if (success) {
                    Log.d(TAG, "Nombre personalizado actualizado exitosamente")
                    // Actualizar inmediatamente en el estado local
                    updateRelayInState(panel.documentName, relay.name) { currentRelay ->
                        currentRelay.copy(customName = newCustomName.takeIf { it.isNotBlank() })
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Error actualizando nombre del relay"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando nombre personalizado", e)
                _uiState.value = _uiState.value.copy(
                    operationInProgress = false,
                    error = "Error actualizando nombre: ${e.message}"
                )
            }
        }
    }

    fun updateRelayActiveState(
        panel: Panel,
        relay: Relay,
        isActive: Boolean
    ) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Actualizando estado activo del relay ${relay.name} a: $isActive")

                _uiState.value = _uiState.value.copy(operationInProgress = true)

                val success = relayControlRepository.updateRelayActiveState(
                    clientId = panel.clientName,
                    panelId = panel.documentName,
                    relayName = relay.name,
                    isActive = isActive
                )

                _uiState.value = _uiState.value.copy(operationInProgress = false)

                if (success) {
                    Log.d(TAG, "Estado activo actualizado exitosamente")
                    // Actualizar inmediatamente en el estado local
                    updateRelayInState(panel.documentName, relay.name) { currentRelay ->
                        currentRelay.copy(isActive = isActive)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Error actualizando estado del relay"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando estado activo", e)
                _uiState.value = _uiState.value.copy(
                    operationInProgress = false,
                    error = "Error actualizando estado: ${e.message}"
                )
            }
        }
    }

    fun updateRelayControllability(
        panel: Panel,
        relay: Relay,
        isControllable: Boolean
    ) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Actualizando controlabilidad del relay ${relay.name} a: $isControllable")

                _uiState.value = _uiState.value.copy(operationInProgress = true)

                val success = relayControlRepository.updateRelayControlConfig(
                    clientId = panel.clientName,
                    panelId = panel.documentName,
                    relayName = relay.name,
                    isControllable = isControllable
                )

                _uiState.value = _uiState.value.copy(operationInProgress = false)

                if (success) {
                    Log.d(TAG, "Controlabilidad actualizada exitosamente")
                    // Actualizar inmediatamente en el estado local
                    updateRelayInState(panel.documentName, relay.name) { currentRelay ->
                        currentRelay.copy(isControllable = isControllable)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Error actualizando controlabilidad del relay"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando controlabilidad", e)
                _uiState.value = _uiState.value.copy(
                    operationInProgress = false,
                    error = "Error actualizando controlabilidad: ${e.message}"
                )
            }
        }
    }

    fun updateRelayContactType(
        panel: Panel,
        relay: Relay,
        contactType: String
    ) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Actualizando tipo de contacto del relay ${relay.name} a: $contactType")

                _uiState.value = _uiState.value.copy(operationInProgress = true)

                val success = relayControlRepository.updateRelayContactType(
                    clientId = panel.clientName,
                    panelId = panel.documentName,
                    relayName = relay.name,
                    contactType = contactType
                )

                _uiState.value = _uiState.value.copy(operationInProgress = false)

                if (success) {
                    Log.d(TAG, "Tipo de contacto actualizado exitosamente")
                    // Actualizar inmediatamente en el estado local
                    updateRelayInState(panel.documentName, relay.name) { currentRelay ->
                        currentRelay.copy(contactType = contactType)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Error actualizando tipo de contacto del relay"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando tipo de contacto", e)
                _uiState.value = _uiState.value.copy(
                    operationInProgress = false,
                    error = "Error actualizando tipo de contacto: ${e.message}"
                )
            }
        }
    }

    fun updateCompleteRelayConfig(
        panel: Panel,
        originalRelay: Relay,
        updatedRelay: Relay
    ) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Actualizando configuración completa del relay ${originalRelay.name}")

                _uiState.value = _uiState.value.copy(operationInProgress = true)

                // Actualizar todas las configuraciones en una sola operación
                val success = relayControlRepository.updateCompleteRelayConfiguration(
                    clientId = panel.clientName,
                    panelId = panel.documentName,
                    relayName = originalRelay.name,
                    customName = updatedRelay.customName,
                    isActive = updatedRelay.isActive,
                    isControllable = updatedRelay.isControllable,
                    contactType = updatedRelay.contactType
                )

                _uiState.value = _uiState.value.copy(operationInProgress = false)

                if (success) {
                    Log.d(TAG, "Configuración completa actualizada exitosamente")
                    // Actualizar inmediatamente en el estado local
                    updateRelayInState(panel.documentName, originalRelay.name) { _ ->
                        updatedRelay
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Error actualizando configuración del relay"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando configuración completa", e)
                _uiState.value = _uiState.value.copy(
                    operationInProgress = false,
                    error = "Error actualizando configuración: ${e.message}"
                )
            }
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

    fun getRelayConfiguration(panelId: String, relayName: String): RelayConfiguration? {
        val panel = _uiState.value.panels.find { it.documentName == panelId }
        val relay = panel?.relays?.find { it.name == relayName }

        return relay?.let {
            RelayConfiguration(
                name = it.name,
                customName = it.customName,
                isControllable = it.isControllable,
                isActive = it.isActive,
                contactType = it.contactType
            )
        }
    }

    fun canControlRelay(panel: Panel, relay: Relay): Boolean {
        return !panel.isESP32Offline() &&
                relay.isActive &&
                relay.isControllable &&
                !_uiState.value.operationInProgress
    }

    fun getRelayConfigStats(): RelayConfigStats {
        val allRelays = _uiState.value.panels.flatMap { it.relays }

        return RelayConfigStats(
            totalRelays = allRelays.size,
            activeRelays = allRelays.count { it.isActive },
            controllableRelays = allRelays.count { it.isControllable && it.isActive },
            customNamedRelays = allRelays.count { !it.customName.isNullOrBlank() },
            normallyOpenRelays = allRelays.count { it.contactType == "NO" },
            normallyClosedRelays = allRelays.count { it.contactType == "NC" }
        )
    }

    data class RelayConfigStats(
        val totalRelays: Int,
        val activeRelays: Int,
        val controllableRelays: Int,
        val customNamedRelays: Int,
        val normallyOpenRelays: Int,
        val normallyClosedRelays: Int
    ) {
        val inactiveRelays: Int get() = totalRelays - activeRelays
        val readOnlyRelays: Int get() = activeRelays - controllableRelays
        val customizationPercentage: Float get() = if (totalRelays > 0) (customNamedRelays.toFloat() / totalRelays) * 100f else 0f
    }

    private suspend fun loadClientNames(clientDocName: String?): Map<String, String> {
        return try {
            if (clientDocName != null) {
                // Usuario normal - solo su cliente
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
                // Admin - todos los clientes
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

        // Cancelar todos los jobs de forma ordenada
        loadingJob?.cancel()
        statusUpdateJob?.cancel()
        refreshJob?.cancel()

        // Limpiar comandos pendientes
        pendingCommands.clear()

        Log.d(TAG, "ViewModel limpiado exitosamente")
    }
}