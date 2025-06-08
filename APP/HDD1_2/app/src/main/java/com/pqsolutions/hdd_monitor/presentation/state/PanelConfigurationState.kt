package com.pqsolutions.hdd_monitor.presentation.state

import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.esp32.ESP32Device

data class PanelConfigurationState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,

    val isEditMode: Boolean = false,
    val currentPanel: Panel? = null,

    val availableESP32s: List<ESP32Device> = emptyList(),
    val esp32StatusMap: Map<String, String> = emptyMap(),

    val clientDocName: String = "",
    val clientDisplayName: String = "",  // NUEVO CAMPO: Nombre legible del cliente
    val userRole: String = "USER",
    val isAdmin: Boolean = false,

    val availableClients: List<Client> = emptyList(),

    val formData: PanelFormData = PanelFormData(),
    val validationErrors: PanelValidationErrors = PanelValidationErrors(),

    val showESP32Dropdown: Boolean = false,
    val selectedESP32: ESP32Device? = null,

    val lastUpdate: Long = System.currentTimeMillis()
) {
    val hasData: Boolean
        get() = !isLoading && error == null

    val hasAvailableESP32s: Boolean
        get() = availableESP32s.isNotEmpty()

    val canPerformOperations: Boolean
        get() = hasData && !isSaving

    val screenTitle: String
        get() = if (isEditMode) "Editar Panel" else "Crear Panel"

    val saveButtonText: String
        get() = when {
            isSaving -> "Guardando..."
            isEditMode -> "Actualizar Panel"
            else -> "Crear Panel"
        }

    val isFormValid: Boolean
        get() = formData.isValid && !validationErrors.hasErrors

    val canSave: Boolean
        get() = isFormValid && canPerformOperations &&
                (isEditMode || selectedESP32 != null)

    val onlineESP32s: List<ESP32Device>
        get() = availableESP32s.filter { esp32 ->
            val status = esp32StatusMap[esp32.documentName]
            status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
        }

    val offlineESP32s: List<ESP32Device>
        get() = availableESP32s.filter { esp32 ->
            val status = esp32StatusMap[esp32.documentName]
            status == ESP32Device.STATUS_OFFLINE
        }

    val pendingESP32s: List<ESP32Device>
        get() = availableESP32s.filter { esp32 ->
            val status = esp32StatusMap[esp32.documentName]
            status in listOf(
                ESP32Device.STATUS_AWAITING_CONFIG,
                ESP32Device.STATUS_PENDING_ASSIGNMENT
            )
        }

    val esp32Stats: ESP32Stats
        get() = ESP32Stats(
            total = availableESP32s.size,
            online = onlineESP32s.size,
            offline = offlineESP32s.size,
            pending = pendingESP32s.size
        )

    fun isESP32Online(esp32Id: String): Boolean {
        val status = esp32StatusMap[esp32Id]
        return status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING
    }

    fun getESP32Status(esp32Id: String): String {
        return esp32StatusMap[esp32Id] ?: ESP32Device.STATUS_OFFLINE
    }

    fun findESP32(esp32Id: String): ESP32Device? {
        return availableESP32s.find { it.documentName == esp32Id }
    }

    fun hasChanges(): Boolean {
        if (!isEditMode || currentPanel == null) return true

        return formData.name != currentPanel.name ||
                formData.location != currentPanel.location ||
                selectedESP32?.documentName != currentPanel.esp32_id
    }

    val currentESP32Info: ESP32Device?
        get() = if (isEditMode && currentPanel != null) {
            availableESP32s.find { it.documentName == currentPanel.esp32_id }
        } else null

    val isChangingESP32: Boolean
        get() = isEditMode && currentPanel != null &&
                selectedESP32?.documentName != currentPanel.esp32_id

    val warnings: List<PanelWarning>
        get() = buildList {
            if (isChangingESP32) {
                add(PanelWarning.ESP32_CHANGE)
            }
            if (offlineESP32s.isNotEmpty() && selectedESP32 != null && !isESP32Online(selectedESP32.documentName)) {
                add(PanelWarning.ESP32_OFFLINE)
            }
            if (!hasAvailableESP32s && !isEditMode) {
                add(PanelWarning.NO_ESP32_AVAILABLE)
            }
        }

    // NUEVO: Propiedad para obtener el texto a mostrar del cliente
    val clientInfoText: String
        get() = when {
            clientDisplayName.isNotEmpty() -> clientDisplayName
            clientDocName.isNotEmpty() -> "Cliente: $clientDocName"
            else -> ""
        }

    // NUEVO: Verifica si hay información del cliente disponible
    val hasClientInfo: Boolean
        get() = clientDisplayName.isNotEmpty() || clientDocName.isNotEmpty()
}

data class PanelFormData(
    val name: String = "",
    val location: String = "",
    val description: String = ""
) {
    val isValid: Boolean
        get() = name.isNotBlank() &&
                location.isNotBlank() &&
                name.length >= 3 &&
                location.length >= 3
}

data class PanelValidationErrors(
    val nameError: String? = null,
    val locationError: String? = null,
    val esp32Error: String? = null,
    val generalError: String? = null
) {
    val hasErrors: Boolean
        get() = nameError != null || locationError != null ||
                esp32Error != null || generalError != null

    val allErrors: List<String>
        get() = listOfNotNull(nameError, locationError, esp32Error, generalError)

    val firstError: String?
        get() = allErrors.firstOrNull()
}

data class ESP32Stats(
    val total: Int,
    val online: Int,
    val offline: Int,
    val pending: Int
) {
    val onlinePercentage: Float
        get() = if (total > 0) (online.toFloat() / total.toFloat()) * 100f else 0f

    val hasGoodConnectivity: Boolean
        get() = onlinePercentage >= 80f

    val statusMessage: String
        get() = when {
            total == 0 -> "No hay ESP32s disponibles"
            onlinePercentage >= 90f -> "Excelente conectividad"
            onlinePercentage >= 70f -> "Buena conectividad"
            onlinePercentage >= 50f -> "Conectividad regular"
            else -> "Conectividad pobre"
        }
}

enum class PanelWarning {
    ESP32_CHANGE,
    ESP32_OFFLINE,
    NO_ESP32_AVAILABLE,
    NAME_ALREADY_EXISTS,
    UNSAVED_CHANGES
}

sealed class PanelConfigurationResult {
    data class Success(
        val operation: PanelOperation,
        val panelName: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : PanelConfigurationResult()

    data class Error(
        val operation: PanelOperation,
        val message: String,
        val errorCode: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : PanelConfigurationResult()

    data class Warning(
        val operation: PanelOperation,
        val message: String,
        val warning: PanelWarning,
        val timestamp: Long = System.currentTimeMillis()
    ) : PanelConfigurationResult()
}

enum class PanelOperation {
    CREATE,
    UPDATE,
    VALIDATE,
    LOAD_DATA,
    SELECT_ESP32,
    SAVE_FORM
}

data class PanelValidationConfig(
    val minNameLength: Int = 3,
    val maxNameLength: Int = 50,
    val minLocationLength: Int = 3,
    val maxLocationLength: Int = 100,
    val allowEmptyDescription: Boolean = true,
    val requireUniqueNames: Boolean = true,
    val validateESP32Availability: Boolean = true
)

sealed class PanelConfigurationEvent {
    data class InitializeScreen(val panelId: String?) : PanelConfigurationEvent()
    object LoadData : PanelConfigurationEvent()

    data class UpdatePanelName(val name: String) : PanelConfigurationEvent()
    data class UpdatePanelLocation(val location: String) : PanelConfigurationEvent()
    data class UpdateDescription(val description: String) : PanelConfigurationEvent()

    data class SelectESP32(val esp32: ESP32Device) : PanelConfigurationEvent()
    data class ClearESP32Selection(val clearSelection: Boolean) : PanelConfigurationEvent()
    data class ToggleESP32Dropdown(val show: Boolean) : PanelConfigurationEvent()

    object SavePanel : PanelConfigurationEvent()
    object CancelEditing : PanelConfigurationEvent()

    data class ValidateField(val field: String, val value: String) : PanelConfigurationEvent()
    object ValidateForm : PanelConfigurationEvent()

    object ClearError : PanelConfigurationEvent()
    object ClearWarnings : PanelConfigurationEvent()
    object ResetForm : PanelConfigurationEvent()
}

data class PanelConfigurationPreferences(
    val autoSave: Boolean = false,
    val validateOnChange: Boolean = true,
    val showAdvancedOptions: Boolean = false,
    val rememberLastESP32: Boolean = true,
    val confirmBeforeCancel: Boolean = true,
    val showESP32Details: Boolean = true
)

object PanelConfigurationStateHelper {

    fun createModeInitial(): PanelConfigurationState {
        return PanelConfigurationState(
            isEditMode = false,
            formData = PanelFormData()
        )
    }

    fun editModeInitial(panel: Panel): PanelConfigurationState {
        return PanelConfigurationState(
            isEditMode = true,
            currentPanel = panel,
            formData = PanelFormData(
                name = panel.name,
                location = panel.location
            )
        )
    }

    fun loading(isEditMode: Boolean = false): PanelConfigurationState {
        return PanelConfigurationState(
            isLoading = true,
            isEditMode = isEditMode
        )
    }

    fun error(message: String, isEditMode: Boolean = false): PanelConfigurationState {
        return PanelConfigurationState(
            isLoading = false,
            isEditMode = isEditMode,
            error = message
        )
    }

    fun saveSuccess(isEditMode: Boolean = false): PanelConfigurationState {
        return PanelConfigurationState(
            isLoading = false,
            isSaving = false,
            isEditMode = isEditMode,
            saveSuccess = true
        )
    }
}