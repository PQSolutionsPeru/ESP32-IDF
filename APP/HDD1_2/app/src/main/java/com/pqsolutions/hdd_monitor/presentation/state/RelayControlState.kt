package com.pqsolutions.hdd_monitor.presentation.state

import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.Relay

/**
 * Estados de la UI para el control de relays
 */
data class RelayControlState(
    val isLoading: Boolean = false,
    val panels: List<Panel> = emptyList(),
    val groupedPanels: Map<String, List<Panel>> = emptyMap(),
    val clientNames: Map<String, String> = emptyMap(),
    val error: String? = null,
    val operationInProgress: Boolean = false,
    val lastOperationResult: OperationResult? = null,
    val selectedPanel: Panel? = null,
    val selectedRelay: Relay? = null,
    val showRelayConfigDialog: Boolean = false,
    val connectionStatus: Map<String, ESP32ConnectionStatus> = emptyMap(), // ESP32_ID -> Status
    val pendingCommands: Map<String, RelayCommand> = emptyMap(), // CommandId -> Command
    val lastUpdate: Long = System.currentTimeMillis()
) {
    /**
     * Verifica si hay paneles disponibles
     */
    val hasPanels: Boolean
        get() = panels.isNotEmpty()

    /**
     * Verifica si hay operaciones pendientes
     */
    val hasPendingOperations: Boolean
        get() = pendingCommands.isNotEmpty() || operationInProgress

    /**
     * Obtiene el número total de relays en todos los paneles
     */
    val totalRelaysCount: Int
        get() = panels.sumOf { it.relays.size }

    /**
     * Obtiene el número de relays activos (OK)
     */
    val activeRelaysCount: Int
        get() = panels.sumOf { panel ->
            panel.relays.count { it.status == "OK" }
        }

    /**
     * Obtiene el número de paneles online
     */
    val onlinePanelsCount: Int
        get() = panels.count { !it.isESP32Offline() }

    /**
     * Verifica si todos los ESP32s están offline
     */
    val allPanelsOffline: Boolean
        get() = panels.isNotEmpty() && panels.all { it.isESP32Offline() }

    /**
     * Obtiene estadísticas resumidas
     */
    val summary: RelayControlSummary
        get() = RelayControlSummary(
            totalPanels = panels.size,
            onlinePanels = onlinePanelsCount,
            totalRelays = totalRelaysCount,
            activeRelays = activeRelaysCount,
            pendingCommands = pendingCommands.size
        )
}

/**
 * Resultado de una operación de control de relay
 */
sealed class OperationResult {
    data class Success(
        val message: String,
        val panelName: String,
        val relayName: String,
        val newStatus: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : OperationResult()

    data class Error(
        val message: String,
        val panelName: String? = null,
        val relayName: String? = null,
        val errorCode: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : OperationResult()

    data class Timeout(
        val message: String,
        val panelName: String,
        val relayName: String,
        val timeoutDuration: Long,
        val timestamp: Long = System.currentTimeMillis()
    ) : OperationResult()
}

/**
 * Estado de conexión de un ESP32
 */
data class ESP32ConnectionStatus(
    val esp32Id: String,
    val status: String, // ONLINE, OFFLINE, CONNECTING, ERROR
    val lastSeen: Long = System.currentTimeMillis(),
    val responseTime: Long? = null, // Tiempo de respuesta en ms
    val signalStrength: Int? = null, // RSSI si está disponible
    val version: String? = null, // Versión del firmware
    val isControllable: Boolean = true // Si permite control remoto
) {
    val isOnline: Boolean
        get() = status == "ONLINE"

    val isOffline: Boolean
        get() = status == "OFFLINE"

    val hasGoodConnection: Boolean
        get() = isOnline && (responseTime ?: Long.MAX_VALUE) < 5000 // < 5 segundos
}

/**
 * Comando de control de relay
 */
data class RelayCommand(
    val commandId: String,
    val panelId: String,
    val esp32Id: String,
    val relayName: String,
    val targetStatus: String, // OK, DISC
    val sentAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 30000, // 30 segundos
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val commandType: RelayCommandType = RelayCommandType.TOGGLE
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAt

    val canRetry: Boolean
        get() = retryCount < maxRetries && !isExpired

    fun withRetry(): RelayCommand = copy(
        retryCount = retryCount + 1,
        sentAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 30000
    )
}

/**
 * Tipos de comandos para relays
 */
enum class RelayCommandType {
    TOGGLE,           // Cambiar estado ON/OFF
    SET_STATUS,       // Establecer estado específico
    UPDATE_CONFIG,    // Actualizar configuración (nombre, etc.)
    RESET,           // Resetear relay
    TEST             // Comando de prueba
}

/**
 * Resumen del estado de control de relays
 */
data class RelayControlSummary(
    val totalPanels: Int,
    val onlinePanels: Int,
    val totalRelays: Int,
    val activeRelays: Int,
    val pendingCommands: Int
) {
    val offlinePanels: Int
        get() = totalPanels - onlinePanels

    val inactiveRelays: Int
        get() = totalRelays - activeRelays

    val connectivityPercentage: Float
        get() = if (totalPanels > 0) {
            (onlinePanels.toFloat() / totalPanels.toFloat()) * 100f
        } else 0f

    val relayActivityPercentage: Float
        get() = if (totalRelays > 0) {
            (activeRelays.toFloat() / totalRelays.toFloat()) * 100f
        } else 0f

    val hasIssues: Boolean
        get() = offlinePanels > 0 || pendingCommands > 0
}

/**
 * Configuración de un relay
 */
data class RelayConfiguration(
    val name: String,
    val customName: String? = null,
    val isControllable: Boolean = true,
    val isActive: Boolean = true, // Si el relay está habilitado para uso
    val contactType: String = "NO", // NO (Normally Open) o NC (Normally Closed)
    val description: String? = null,
    val alertOnChange: Boolean = true,
    val autoResetTime: Long? = null // Tiempo en ms para auto-reset, null = manual
) {
    val displayName: String
        get() = customName?.takeIf { it.isNotBlank() } ?: name

    val isConfigured: Boolean
        get() = customName != null || description != null
}

/**
 * Eventos de UI para el control de relays
 */
sealed class RelayControlEvent {
    // Eventos de carga
    object LoadPanels : RelayControlEvent()
    object RefreshPanels : RelayControlEvent()

    // Eventos de control
    data class ToggleRelay(val panel: Panel, val relay: Relay) : RelayControlEvent()
    data class SetRelayStatus(val panel: Panel, val relay: Relay, val status: String) : RelayControlEvent()

    // Eventos de configuración
    data class ShowRelayConfig(val panel: Panel, val relay: Relay) : RelayControlEvent()
    data class UpdateRelayConfig(val panel: Panel, val relay: Relay, val config: RelayConfiguration) : RelayControlEvent()
    object DismissRelayConfig : RelayControlEvent()

    // Eventos de estado
    data class UpdateESP32Status(val esp32Id: String, val status: ESP32ConnectionStatus) : RelayControlEvent()
    data class CommandCompleted(val commandId: String, val result: OperationResult) : RelayControlEvent()
    data class CommandTimeout(val commandId: String) : RelayControlEvent()

    // Eventos de error
    data class ShowError(val message: String) : RelayControlEvent()
    object ClearError : RelayControlEvent()
    object ClearLastOperationResult : RelayControlEvent()

    // Eventos de UI
    data class SelectPanel(val panel: Panel) : RelayControlEvent()
    data class SelectRelay(val relay: Relay) : RelayControlEvent()
    object ClearSelection : RelayControlEvent()
}

/**
 * Filtros para la vista de relays
 */
data class RelayControlFilters(
    val showOnlineOnly: Boolean = false,
    val showActiveOnly: Boolean = false,
    val clientFilter: String? = null,
    val relayStatusFilter: String? = null, // OK, DISC, ALL
    val searchQuery: String = ""
) {
    fun matches(panel: Panel): Boolean {
        // Filtro de conectividad
        if (showOnlineOnly && panel.isESP32Offline()) return false

        // Filtro de cliente
        if (clientFilter != null && !panel.clientName.contains(clientFilter, ignoreCase = true)) {
            return false
        }

        // Filtro de búsqueda
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            val matchesPanel = panel.name.lowercase().contains(query) ||
                    panel.location.lowercase().contains(query)
            val matchesRelay = panel.relays.any {
                it.name.lowercase().contains(query)
            }
            if (!matchesPanel && !matchesRelay) return false
        }

        return true
    }

    fun matchesRelay(relay: Relay): Boolean {
        // Filtro de estado de relay
        if (relayStatusFilter != null && relayStatusFilter != "ALL") {
            if (relay.status != relayStatusFilter) return false
        }

        // Filtro de actividad
        if (showActiveOnly && relay.status != "OK") return false

        return true
    }
}