package com.pqsolutions.hdd_monitor.presentation.state

import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.esp32.ESP32Device

/**
 * Estado de la UI para la gestión de ESP32s
 */
data class ESP32ManagementState(
    val isLoading: Boolean = true,
    val panels: List<Panel> = emptyList(),
    val availableESP32s: List<ESP32Device> = emptyList(),
    val esp32StatusMap: Map<String, String> = emptyMap(),
    val onlineESP32Count: Int = 0,
    val error: String? = null,
    val lastUpdate: Long = System.currentTimeMillis(),
    val searchQuery: String = "",
    val statusFilter: ESP32StatusFilter = ESP32StatusFilter.ALL,
    val showOnlyAvailable: Boolean = false,
    val operationInProgress: Boolean = false,
    val lastOperationResult: ESP32OperationResult? = null,
    val userRole: String = "USER",
    val clientName: String = "",
    val totalESP32Count: Int = 0
) {
    /**
     * Verifica si hay datos cargados
     */
    val hasData: Boolean
        get() = panels.isNotEmpty() || availableESP32s.isNotEmpty()

    /**
     * Verifica si hay paneles configurados
     */
    val hasPanels: Boolean
        get() = panels.isNotEmpty()

    /**
     * Verifica si hay ESP32s disponibles para asignar
     */
    val hasAvailableESP32s: Boolean
        get() = availableESP32s.isNotEmpty()

    /**
     * Obtiene el número total de ESP32s (asignados + disponibles)
     */
    val totalESP32s: Int
        get() = panels.size + availableESP32s.size

    /**
     * Obtiene el número de ESP32s offline
     */
    val offlineESP32Count: Int
        get() = totalESP32s - onlineESP32Count

    /**
     * Obtiene el porcentaje de conectividad
     */
    val connectivityPercentage: Float
        get() = if (totalESP32s > 0) {
            (onlineESP32Count.toFloat() / totalESP32s.toFloat()) * 100f
        } else 0f

    /**
     * Verifica si hay problemas en algún panel
     */
    val hasIssues: Boolean
        get() = panels.any { it.hasIssues } || offlineESP32Count > 0

    /**
     * Obtiene paneles con problemas
     */
    val panelsWithIssues: List<Panel>
        get() = panels.filter { it.hasIssues || it.isESP32Offline() }

    /**
     * Obtiene paneles filtrados según los criterios actuales
     */
    val filteredPanels: List<Panel>
        get() = panels.filter { panel ->
            // Filtro de búsqueda
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                val query = searchQuery.lowercase()
                panel.name.lowercase().contains(query) ||
                        panel.location.lowercase().contains(query) ||
                        panel.esp32_id.lowercase().contains(query)
            }

            // Filtro de estado
            val matchesStatus = when (statusFilter) {
                ESP32StatusFilter.ALL -> true
                ESP32StatusFilter.ONLINE -> !panel.isESP32Offline()
                ESP32StatusFilter.OFFLINE -> panel.isESP32Offline()
                ESP32StatusFilter.WITH_ISSUES -> panel.hasIssues
                ESP32StatusFilter.OK -> !panel.hasIssues && !panel.isESP32Offline()
            }

            matchesSearch && matchesStatus
        }

    /**
     * Obtiene ESP32s disponibles filtrados
     */
    val filteredAvailableESP32s: List<ESP32Device>
        get() = availableESP32s.filter { esp32 ->
            // Filtro de búsqueda
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                val query = searchQuery.lowercase()
                esp32.documentName.lowercase().contains(query) ||
                        esp32.MAC.lowercase().contains(query) ||
                        esp32.IP.lowercase().contains(query)
            }

            // Filtro de estado
            val matchesStatus = when (statusFilter) {
                ESP32StatusFilter.ALL -> true
                ESP32StatusFilter.ONLINE -> esp32.status in listOf(
                    ESP32Device.STATUS_ONLINE,
                    ESP32Device.STATUS_RUNNING
                )
                ESP32StatusFilter.OFFLINE -> esp32.status == ESP32Device.STATUS_OFFLINE
                ESP32StatusFilter.WITH_ISSUES -> esp32.status == ESP32Device.STATUS_ERROR
                ESP32StatusFilter.OK -> esp32.status in listOf(
                    ESP32Device.STATUS_AWAITING_CONFIG,
                    ESP32Device.STATUS_PENDING_ASSIGNMENT,
                    ESP32Device.STATUS_ONLINE,
                    ESP32Device.STATUS_RUNNING
                )
            }

            matchesSearch && matchesStatus
        }

    /**
     * Obtiene estadísticas resumidas
     */
    val summary: ESP32ManagementSummary
        get() = ESP32ManagementSummary(
            totalPanels = panels.size,
            totalESP32s = totalESP32s,
            onlineESP32s = onlineESP32Count,
            offlineESP32s = offlineESP32Count,
            availableESP32s = availableESP32s.size,
            panelsWithIssues = panelsWithIssues.size,
            connectivityPercentage = connectivityPercentage
        )

    /**
     * Verifica si un ESP32 específico está online
     */
    fun isESP32Online(esp32Id: String): Boolean {
        val status = esp32StatusMap[esp32Id]
        return status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
    }

    /**
     * Obtiene el estado de un ESP32 específico
     */
    fun getESP32Status(esp32Id: String): String {
        return esp32StatusMap[esp32Id] ?: ESP32Device.STATUS_OFFLINE
    }

    /**
     * Busca un panel por su ID
     */
    fun findPanel(panelId: String): Panel? {
        return panels.find { it.documentName == panelId }
    }

    /**
     * Busca un ESP32 disponible por su ID
     */
    fun findAvailableESP32(esp32Id: String): ESP32Device? {
        return availableESP32s.find { it.documentName == esp32Id }
    }

    /**
     * Verifica si se puede crear un nuevo panel
     */
    fun canCreateNewPanel(): Boolean {
        return hasAvailableESP32s && !operationInProgress
    }

    /**
     * Verifica si se puede realizar operaciones
     */
    fun canPerformOperations(): Boolean {
        return !isLoading && !operationInProgress && error == null
    }
}

/**
 * Filtros de estado para ESP32s
 */
enum class ESP32StatusFilter {
    ALL,           // Todos
    ONLINE,        // Solo online
    OFFLINE,       // Solo offline
    WITH_ISSUES,   // Con problemas
    OK             // Funcionando correctamente
}

/**
 * Resultado de operaciones con ESP32s
 */
sealed class ESP32OperationResult {
    data class Success(
        val message: String,
        val operation: ESP32Operation,
        val affectedItem: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : ESP32OperationResult()

    data class Error(
        val message: String,
        val operation: ESP32Operation,
        val errorCode: String? = null,
        val affectedItem: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : ESP32OperationResult()

    data class Warning(
        val message: String,
        val operation: ESP32Operation,
        val affectedItem: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : ESP32OperationResult()
}

/**
 * Tipos de operaciones con ESP32s
 */
enum class ESP32Operation {
    CREATE_PANEL,      // Crear panel
    EDIT_PANEL,        // Editar panel
    DELETE_PANEL,      // Eliminar panel
    ASSIGN_ESP32,      // Asignar ESP32 a panel
    UNASSIGN_ESP32,    // Desasignar ESP32
    UPDATE_RELAY,      // Actualizar configuración de relay
    REFRESH_DATA,      // Actualizar datos
    LOAD_DATA          // Cargar datos inicial
}

/**
 * Estadísticas resumidas de ESP32s
 */
data class ESP32ManagementSummary(
    val totalPanels: Int,
    val totalESP32s: Int,
    val onlineESP32s: Int,
    val offlineESP32s: Int,
    val availableESP32s: Int,
    val panelsWithIssues: Int,
    val connectivityPercentage: Float
) {
    /**
     * Verifica si el sistema está funcionando bien
     */
    val isHealthy: Boolean
        get() = panelsWithIssues == 0 && connectivityPercentage > 80f

    /**
     * Obtiene el nivel de salud del sistema
     */
    val healthLevel: HealthLevel
        get() = when {
            connectivityPercentage >= 90f && panelsWithIssues == 0 -> HealthLevel.EXCELLENT
            connectivityPercentage >= 80f && panelsWithIssues <= 1 -> HealthLevel.GOOD
            connectivityPercentage >= 60f && panelsWithIssues <= 2 -> HealthLevel.FAIR
            connectivityPercentage >= 40f -> HealthLevel.POOR
            else -> HealthLevel.CRITICAL
        }

    /**
     * Mensaje de estado general
     */
    val statusMessage: String
        get() = when (healthLevel) {
            HealthLevel.EXCELLENT -> "Sistema funcionando perfectamente"
            HealthLevel.GOOD -> "Sistema funcionando bien"
            HealthLevel.FAIR -> "Sistema funcionando con algunos problemas"
            HealthLevel.POOR -> "Sistema con problemas significativos"
            HealthLevel.CRITICAL -> "Sistema crítico - requiere atención inmediata"
        }
}

/**
 * Niveles de salud del sistema
 */
enum class HealthLevel {
    EXCELLENT,    // Excelente (90%+ conectividad, 0 problemas)
    GOOD,         // Bueno (80%+ conectividad, ≤1 problema)
    FAIR,         // Regular (60%+ conectividad, ≤2 problemas)
    POOR,         // Malo (40%+ conectividad)
    CRITICAL      // Crítico (<40% conectividad)
}

/**
 * Configuración de filtros y preferencias de vista
 */
data class ESP32ViewPreferences(
    val sortBy: ESP32SortOption = ESP32SortOption.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val groupBy: ESP32GroupOption = ESP32GroupOption.NONE,
    val showRelayDetails: Boolean = true,
    val showTechnicalInfo: Boolean = false,
    val autoRefresh: Boolean = true,
    val refreshInterval: Long = 30000L // 30 segundos
)

/**
 * Opciones de ordenamiento
 */
enum class ESP32SortOption {
    NAME,           // Por nombre de panel
    LOCATION,       // Por ubicación
    STATUS,         // Por estado
    LAST_UPDATE,    // Por última actualización
    ESP32_ID        // Por ID de ESP32
}

/**
 * Dirección de ordenamiento
 */
enum class SortDirection {
    ASCENDING,
    DESCENDING
}

/**
 * Opciones de agrupamiento
 */
enum class ESP32GroupOption {
    NONE,           // Sin agrupamiento
    STATUS,         // Agrupar por estado
    LOCATION,       // Agrupar por ubicación
    CLIENT          // Agrupar por cliente (solo admin)
}

/**
 * Eventos de UI para la gestión de ESP32s
 */
sealed class ESP32ManagementEvent {
    // Eventos de datos
    object LoadData : ESP32ManagementEvent()
    object RefreshData : ESP32ManagementEvent()

    // Eventos de filtros
    data class UpdateSearchQuery(val query: String) : ESP32ManagementEvent()
    data class UpdateStatusFilter(val filter: ESP32StatusFilter) : ESP32ManagementEvent()
    data class ToggleShowOnlyAvailable(val showOnly: Boolean) : ESP32ManagementEvent()

    // Eventos de operaciones
    data class CreatePanel(val esp32Id: String?) : ESP32ManagementEvent()
    data class EditPanel(val panelId: String) : ESP32ManagementEvent()
    data class DeletePanel(val panelId: String) : ESP32ManagementEvent()
    data class CustomizeRelays(val panelId: String) : ESP32ManagementEvent()

    // Eventos de estado
    object ClearError : ESP32ManagementEvent()
    object ClearOperationResult : ESP32ManagementEvent()

    // Eventos de preferencias
    data class UpdateViewPreferences(val preferences: ESP32ViewPreferences) : ESP32ManagementEvent()
}