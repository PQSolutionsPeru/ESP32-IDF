package com.pqsolutions.hdd_monitor.presentation.state

import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.esp32.ESP32Device

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
    val hasData: Boolean
        get() = panels.isNotEmpty() || availableESP32s.isNotEmpty()

    val hasAnyData: Boolean
        get() = panels.isNotEmpty() || availableESP32s.isNotEmpty() || esp32StatusMap.isNotEmpty()

    val hasPanels: Boolean
        get() = panels.isNotEmpty()

    val hasAvailableESP32s: Boolean
        get() = availableESP32s.isNotEmpty()

    val totalESP32s: Int
        get() = panels.size + availableESP32s.size

    val offlineESP32Count: Int
        get() = totalESP32s - onlineESP32Count

    val connectivityPercentage: Float
        get() = if (totalESP32s > 0) {
            (onlineESP32Count.toFloat() / totalESP32s.toFloat()) * 100f
        } else 0f

    val hasIssues: Boolean
        get() = panels.any { it.hasIssues } || offlineESP32Count > 0

    val panelsWithIssues: List<Panel>
        get() = panels.filter { it.hasIssues || it.isESP32Offline() }

    val filteredPanels: List<Panel>
        get() = panels.filter { panel ->
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                val query = searchQuery.lowercase()
                panel.name.lowercase().contains(query) ||
                        panel.location.lowercase().contains(query) ||
                        panel.esp32_id.lowercase().contains(query)
            }

            val matchesStatus = when (statusFilter) {
                ESP32StatusFilter.ALL -> true
                ESP32StatusFilter.ONLINE -> !panel.isESP32Offline()
                ESP32StatusFilter.OFFLINE -> panel.isESP32Offline()
                ESP32StatusFilter.WITH_ISSUES -> panel.hasIssues
                ESP32StatusFilter.OK -> !panel.hasIssues && !panel.isESP32Offline()
            }

            matchesSearch && matchesStatus
        }

    val filteredAvailableESP32s: List<ESP32Device>
        get() = availableESP32s.filter { esp32 ->
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                val query = searchQuery.lowercase()
                esp32.documentName.lowercase().contains(query) ||
                        esp32.MAC.lowercase().contains(query) ||
                        esp32.IP.lowercase().contains(query)
            }

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

    fun isESP32Online(esp32Id: String): Boolean {
        val status = esp32StatusMap[esp32Id]
        return status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
    }

    fun getESP32Status(esp32Id: String): String {
        return esp32StatusMap[esp32Id] ?: ESP32Device.STATUS_OFFLINE
    }

    fun findPanel(panelId: String): Panel? {
        return panels.find { it.documentName == panelId }
    }

    fun findAvailableESP32(esp32Id: String): ESP32Device? {
        return availableESP32s.find { it.documentName == esp32Id }
    }

    fun canCreateNewPanel(): Boolean {
        return hasAvailableESP32s && !operationInProgress
    }

    fun canPerformOperations(): Boolean {
        return !isLoading && !operationInProgress && error == null
    }
}

enum class ESP32StatusFilter {
    ALL,
    ONLINE,
    OFFLINE,
    WITH_ISSUES,
    OK
}

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

enum class ESP32Operation {
    CREATE_PANEL,
    EDIT_PANEL,
    DELETE_PANEL,
    ASSIGN_ESP32,
    UNASSIGN_ESP32,
    UPDATE_RELAY,
    REFRESH_DATA,
    LOAD_DATA
}

data class ESP32ManagementSummary(
    val totalPanels: Int,
    val totalESP32s: Int,
    val onlineESP32s: Int,
    val offlineESP32s: Int,
    val availableESP32s: Int,
    val panelsWithIssues: Int,
    val connectivityPercentage: Float
) {
    val isHealthy: Boolean
        get() = panelsWithIssues == 0 && connectivityPercentage > 80f

    val healthLevel: HealthLevel
        get() = when {
            connectivityPercentage >= 90f && panelsWithIssues == 0 -> HealthLevel.EXCELLENT
            connectivityPercentage >= 80f && panelsWithIssues <= 1 -> HealthLevel.GOOD
            connectivityPercentage >= 60f && panelsWithIssues <= 2 -> HealthLevel.FAIR
            connectivityPercentage >= 40f -> HealthLevel.POOR
            else -> HealthLevel.CRITICAL
        }

    val statusMessage: String
        get() = when (healthLevel) {
            HealthLevel.EXCELLENT -> "Sistema funcionando perfectamente"
            HealthLevel.GOOD -> "Sistema funcionando bien"
            HealthLevel.FAIR -> "Sistema funcionando con algunos problemas"
            HealthLevel.POOR -> "Sistema con problemas significativos"
            HealthLevel.CRITICAL -> "Sistema crítico - requiere atención inmediata"
        }
}

enum class HealthLevel {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    CRITICAL
}

data class ESP32ViewPreferences(
    val sortBy: ESP32SortOption = ESP32SortOption.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val groupBy: ESP32GroupOption = ESP32GroupOption.NONE,
    val showRelayDetails: Boolean = true,
    val showTechnicalInfo: Boolean = false,
    val autoRefresh: Boolean = true,
    val refreshInterval: Long = 30000L
)

enum class ESP32SortOption {
    NAME,
    LOCATION,
    STATUS,
    LAST_UPDATE,
    ESP32_ID
}

enum class SortDirection {
    ASCENDING,
    DESCENDING
}

enum class ESP32GroupOption {
    NONE,
    STATUS,
    LOCATION,
    CLIENT
}

sealed class ESP32ManagementEvent {
    object LoadData : ESP32ManagementEvent()
    object RefreshData : ESP32ManagementEvent()

    data class UpdateSearchQuery(val query: String) : ESP32ManagementEvent()
    data class UpdateStatusFilter(val filter: ESP32StatusFilter) : ESP32ManagementEvent()
    data class ToggleShowOnlyAvailable(val showOnly: Boolean) : ESP32ManagementEvent()

    data class CreatePanel(val esp32Id: String?) : ESP32ManagementEvent()
    data class EditPanel(val panelId: String) : ESP32ManagementEvent()
    data class DeletePanel(val panelId: String) : ESP32ManagementEvent()
    data class CustomizeRelays(val panelId: String) : ESP32ManagementEvent()

    object ClearError : ESP32ManagementEvent()
    object ClearOperationResult : ESP32ManagementEvent()

    data class UpdateViewPreferences(val preferences: ESP32ViewPreferences) : ESP32ManagementEvent()
}