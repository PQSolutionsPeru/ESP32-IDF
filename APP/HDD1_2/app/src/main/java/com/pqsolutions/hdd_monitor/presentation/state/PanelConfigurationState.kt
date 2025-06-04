package com.pqsolutions.hdd_monitor.presentation.state

import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.esp32.ESP32Device

/**
 * Estado de la UI para la configuración de paneles
 */
data class PanelConfigurationState(
    // Estados principales
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,

    // Modo de operación
    val isEditMode: Boolean = false,
    val currentPanel: Panel? = null,

    // Datos disponibles
    val availableESP32s: List<ESP32Device> = emptyList(),
    val esp32StatusMap: Map<String, String> = emptyMap(),

    // Contexto del usuario
    val clientDocName: String = "",
    val userRole: String = "USER",

    // Estados del formulario
    val formData: PanelFormData = PanelFormData(),
    val validationErrors: PanelValidationErrors = PanelValidationErrors(),

    // Estados de UI
    val showESP32Dropdown: Boolean = false,
    val selectedESP32: ESP32Device? = null,

    // Información adicional
    val lastUpdate: Long = System.currentTimeMillis()
) {
    /**
     * Verifica si hay datos cargados
     */
    val hasData: Boolean
        get() = !isLoading && error == null

    /**
     * Verifica si hay ESP32s disponibles
     */
    val hasAvailableESP32s: Boolean
        get() = availableESP32s.isNotEmpty()

    /**
     * Verifica si se puede realizar operaciones
     */
    val canPerformOperations: Boolean
        get() = hasData && !isSaving

    /**
     * Obtiene el título de la pantalla
     */
    val screenTitle: String
        get() = if (isEditMode) "Editar Panel" else "Crear Panel"

    /**
     * Obtiene el texto del botón principal
     */
    val saveButtonText: String
        get() = when {
            isSaving -> "Guardando..."
            isEditMode -> "Actualizar Panel"
            else -> "Crear Panel"
        }

    /**
     * Verifica si el formulario es válido
     */
    val isFormValid: Boolean
        get() = formData.isValid && !validationErrors.hasErrors

    /**
     * Verifica si se puede guardar
     */
    val canSave: Boolean
        get() = isFormValid && canPerformOperations &&
                (isEditMode || selectedESP32 != null)

    /**
     * Obtiene ESP32s filtrados por estado
     */
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

    /**
     * Obtiene estadísticas de ESP32s
     */
    val esp32Stats: ESP32Stats
        get() = ESP32Stats(
            total = availableESP32s.size,
            online = onlineESP32s.size,
            offline = offlineESP32s.size,
            pending = pendingESP32s.size
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
     * Busca un ESP32 por ID
     */
    fun findESP32(esp32Id: String): ESP32Device? {
        return availableESP32s.find { it.documentName == esp32Id }
    }

    /**
     * Verifica si hay cambios en el panel (modo edición)
     */
    fun hasChanges(): Boolean {
        if (!isEditMode || currentPanel == null) return true

        return formData.name != currentPanel.name ||
                formData.location != currentPanel.location ||
                selectedESP32?.documentName != currentPanel.esp32_id
    }

    /**
     * Obtiene información del ESP32 actual (modo edición)
     */
    val currentESP32Info: ESP32Device?
        get() = if (isEditMode && currentPanel != null) {
            availableESP32s.find { it.documentName == currentPanel.esp32_id }
        } else null

    /**
     * Verifica si se está cambiando el ESP32 (modo edición)
     */
    val isChangingESP32: Boolean
        get() = isEditMode && currentPanel != null &&
                selectedESP32?.documentName != currentPanel.esp32_id

    /**
     * Obtiene advertencias para el usuario
     */
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
}

/**
 * Datos del formulario de panel
 */
data class PanelFormData(
    val name: String = "",
    val location: String = "",
    val description: String = ""
) {
    /**
     * Verifica si los datos básicos son válidos
     */
    val isValid: Boolean
        get() = name.isNotBlank() &&
                location.isNotBlank() &&
                name.length >= 3 &&
                location.length >= 3
}

/**
 * Errores de validación del formulario
 */
data class PanelValidationErrors(
    val nameError: String? = null,
    val locationError: String? = null,
    val esp32Error: String? = null,
    val generalError: String? = null
) {
    /**
     * Verifica si hay errores
     */
    val hasErrors: Boolean
        get() = nameError != null || locationError != null ||
                esp32Error != null || generalError != null

    /**
     * Obtiene todos los errores como lista
     */
    val allErrors: List<String>
        get() = listOfNotNull(nameError, locationError, esp32Error, generalError)

    /**
     * Obtiene el primer error encontrado
     */
    val firstError: String?
        get() = allErrors.firstOrNull()
}

/**
 * Estadísticas de ESP32s
 */
data class ESP32Stats(
    val total: Int,
    val online: Int,
    val offline: Int,
    val pending: Int
) {
    /**
     * Porcentaje de ESP32s online
     */
    val onlinePercentage: Float
        get() = if (total > 0) (online.toFloat() / total.toFloat()) * 100f else 0f

    /**
     * Verifica si hay buena conectividad
     */
    val hasGoodConnectivity: Boolean
        get() = onlinePercentage >= 80f

    /**
     * Mensaje de estado
     */
    val statusMessage: String
        get() = when {
            total == 0 -> "No hay ESP32s disponibles"
            onlinePercentage >= 90f -> "Excelente conectividad"
            onlinePercentage >= 70f -> "Buena conectividad"
            onlinePercentage >= 50f -> "Conectividad regular"
            else -> "Conectividad pobre"
        }
}

/**
 * Advertencias para el usuario
 */
enum class PanelWarning {
    ESP32_CHANGE,           // Cambio de ESP32 en modo edición
    ESP32_OFFLINE,          // ESP32 seleccionado está offline
    NO_ESP32_AVAILABLE,     // No hay ESP32s disponibles
    NAME_ALREADY_EXISTS,    // Nombre ya existe
    UNSAVED_CHANGES        // Cambios sin guardar
}

/**
 * Resultado de operaciones de configuración
 */
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

/**
 * Tipos de operaciones de panel
 */
enum class PanelOperation {
    CREATE,                 // Crear panel
    UPDATE,                 // Actualizar panel
    VALIDATE,               // Validar datos
    LOAD_DATA,              // Cargar datos
    SELECT_ESP32,           // Seleccionar ESP32
    SAVE_FORM              // Guardar formulario
}

/**
 * Configuración de validaciones
 */
data class PanelValidationConfig(
    val minNameLength: Int = 3,
    val maxNameLength: Int = 50,
    val minLocationLength: Int = 3,
    val maxLocationLength: Int = 100,
    val allowEmptyDescription: Boolean = true,
    val requireUniqueNames: Boolean = true,
    val validateESP32Availability: Boolean = true
)

/**
 * Eventos de UI para la configuración de paneles
 */
sealed class PanelConfigurationEvent {
    // Eventos de inicialización
    data class InitializeScreen(val panelId: String?) : PanelConfigurationEvent()
    object LoadData : PanelConfigurationEvent()

    // Eventos del formulario
    data class UpdatePanelName(val name: String) : PanelConfigurationEvent()
    data class UpdatePanelLocation(val location: String) : PanelConfigurationEvent()
    data class UpdateDescription(val description: String) : PanelConfigurationEvent()

    // Eventos de ESP32
    data class SelectESP32(val esp32: ESP32Device) : PanelConfigurationEvent()
    data class ClearESP32Selection(val clearSelection: Boolean) : PanelConfigurationEvent()
    data class ToggleESP32Dropdown(val show: Boolean) : PanelConfigurationEvent()

    // Eventos de operaciones
    object SavePanel : PanelConfigurationEvent()
    object CancelEditing : PanelConfigurationEvent()

    // Eventos de validación
    data class ValidateField(val field: String, val value: String) : PanelConfigurationEvent()
    object ValidateForm : PanelConfigurationEvent()

    // Eventos de estado
    object ClearError : PanelConfigurationEvent()
    object ClearWarnings : PanelConfigurationEvent()
    object ResetForm : PanelConfigurationEvent()
}

/**
 * Preferencias de configuración de panel
 */
data class PanelConfigurationPreferences(
    val autoSave: Boolean = false,
    val validateOnChange: Boolean = true,
    val showAdvancedOptions: Boolean = false,
    val rememberLastESP32: Boolean = true,
    val confirmBeforeCancel: Boolean = true,
    val showESP32Details: Boolean = true
)

/**
 * Helper para construir estados específicos
 */
object PanelConfigurationStateHelper {

    /**
     * Estado inicial para crear panel
     */
    fun createModeInitial(): PanelConfigurationState {
        return PanelConfigurationState(
            isEditMode = false,
            formData = PanelFormData()
        )
    }

    /**
     * Estado inicial para editar panel
     */
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

    /**
     * Estado de loading
     */
    fun loading(isEditMode: Boolean = false): PanelConfigurationState {
        return PanelConfigurationState(
            isLoading = true,
            isEditMode = isEditMode
        )
    }

    /**
     * Estado de error
     */
    fun error(message: String, isEditMode: Boolean = false): PanelConfigurationState {
        return PanelConfigurationState(
            isLoading = false,
            isEditMode = isEditMode,
            error = message
        )
    }

    /**
     * Estado de guardado exitoso
     */
    fun saveSuccess(isEditMode: Boolean = false): PanelConfigurationState {
        return PanelConfigurationState(
            isLoading = false,
            isSaving = false,
            isEditMode = isEditMode,
            saveSuccess = true
        )
    }
}