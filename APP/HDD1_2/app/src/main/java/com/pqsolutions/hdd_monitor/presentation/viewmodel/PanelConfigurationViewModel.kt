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
import com.pqsolutions.hdd_monitor.presentation.state.PanelConfigurationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PanelConfigurationViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val esp32Repository: ESP32Repository,
    private val userRepository: UserRepository
) : ViewModel() {

    companion object {
        private const val TAG = "PanelConfigurationViewModel"
    }

    private val _uiState = MutableStateFlow(PanelConfigurationState())
    val uiState: StateFlow<PanelConfigurationState> = _uiState.asStateFlow()

    private var initializationJob: Job? = null
    private var saveJob: Job? = null

    /**
     * Inicializa la pantalla según el modo (crear/editar)
     */
    fun initializeScreen(panelId: String?) {
        // Cancelar inicialización anterior si existe
        initializationJob?.cancel()

        initializationJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Inicializando pantalla - panelId: $panelId")
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    isEditMode = panelId != null
                )

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

                // Determinar cliente según el rol
                val clientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> {
                        // Para admin, si está editando, necesitamos el cliente del panel
                        if (panelId != null) {
                            // Obtener el panel primero para saber el cliente
                            getPanelClient(panelId)
                        } else {
                            // Para crear, el admin debe especificar cliente (por ahora usar el suyo)
                            currentUser.clientDocName
                        }
                    }
                }

                if (clientDocName.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "No se pudo determinar el cliente"
                    )
                    return@launch
                }

                // Cargar datos necesarios
                if (panelId != null) {
                    // Modo edición: cargar panel existente + ESP32s disponibles
                    loadEditModeData(clientDocName, panelId)
                } else {
                    // Modo creación: solo cargar ESP32s disponibles
                    loadCreateModeData(clientDocName)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error inicializando pantalla", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error inicializando: ${e.message}"
                )
            }
        }
    }

    /**
     * Obtiene el cliente de un panel (para admin editando)
     */
    private suspend fun getPanelClient(panelId: String): String {
        return try {
            // Buscar en todos los clientes hasta encontrar el panel
            // Por simplicidad, usamos el cliente del usuario actual
            // En una implementación completa, buscarías en todos los clientes
            userRepository.getCurrentUser()?.clientDocName ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo cliente del panel", e)
            ""
        }
    }

    /**
     * Carga datos para modo edición
     */
    private suspend fun loadEditModeData(clientDocName: String, panelId: String) {
        try {
            Log.d(TAG, "Cargando datos para edición - Panel: $panelId")

            // Combinar flujos de panel actual y ESP32s disponibles
            combine(
                panelRepository.observePanelUpdates(clientDocName, panelId),
                esp32Repository.observeUnassignedESP32s(),
                esp32Repository.observeAssignedESP32s(clientDocName)
            ) { panel, unassignedESP32s, assignedESP32s ->
                Triple(panel, unassignedESP32s, assignedESP32s)
            }
                .catch { error ->
                    Log.e(TAG, "Error en flujos de modo edición", error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Error cargando datos: ${error.message}"
                    )
                }
                .collect { (panel, unassignedESP32s, assignedESP32s) ->
                    if (panel == null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Panel no encontrado"
                        )
                        return@collect
                    }

                    // ESP32s disponibles = no asignados + el ESP32 actual del panel
                    val availableESP32s = buildList {
                        addAll(unassignedESP32s)
                        // Agregar el ESP32 actual del panel si existe
                        assignedESP32s.find { it.documentName == panel.esp32_id }?.let { add(it) }
                    }

                    // Crear mapa de estados
                    val esp32StatusMap = buildMap {
                        unassignedESP32s.forEach { put(it.documentName, it.status) }
                        assignedESP32s.forEach { put(it.documentName, it.status) }
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentPanel = panel,
                        availableESP32s = availableESP32s,
                        esp32StatusMap = esp32StatusMap,
                        clientDocName = clientDocName,
                        error = null
                    )

                    Log.d(TAG, "Datos de edición cargados - Panel: ${panel.name}, ESP32s disponibles: ${availableESP32s.size}")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error cargando datos de edición", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Error cargando panel: ${e.message}"
            )
        }
    }

    /**
     * Carga datos para modo creación
     */
    private suspend fun loadCreateModeData(clientDocName: String) {
        try {
            Log.d(TAG, "Cargando datos para creación")

            // Solo necesitamos ESP32s no asignados
            esp32Repository.observeUnassignedESP32s()
                .catch { error ->
                    Log.e(TAG, "Error en flujo de ESP32s no asignados", error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Error cargando ESP32s: ${error.message}"
                    )
                }
                .collect { unassignedESP32s ->
                    // Filtrar ESP32s en estados válidos para asignación
                    val availableESP32s = unassignedESP32s.filter { esp32 ->
                        esp32.status in listOf(
                            ESP32Device.STATUS_AWAITING_CONFIG,
                            ESP32Device.STATUS_PENDING_ASSIGNMENT,
                            ESP32Device.STATUS_ONLINE,
                            ESP32Device.STATUS_RUNNING
                        )
                    }

                    // Crear mapa de estados
                    val esp32StatusMap = unassignedESP32s.associate {
                        it.documentName to it.status
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        availableESP32s = availableESP32s,
                        esp32StatusMap = esp32StatusMap,
                        clientDocName = clientDocName,
                        error = null
                    )

                    Log.d(TAG, "Datos de creación cargados - ESP32s disponibles: ${availableESP32s.size}")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error cargando datos de creación", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Error cargando ESP32s: ${e.message}"
            )
        }
    }

    /**
     * Guarda el panel (crear o editar)
     */
    fun savePanel(
        panelId: String?,
        name: String,
        location: String,
        esp32Device: ESP32Device?
    ) {
        // Cancelar guardado anterior si existe
        saveJob?.cancel()

        saveJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Guardando panel - Modo: ${if (panelId != null) "Editar" else "Crear"}")

                // Validaciones
                if (name.isBlank()) {
                    _uiState.value = _uiState.value.copy(error = "El nombre del panel es requerido")
                    return@launch
                }

                if (location.isBlank()) {
                    _uiState.value = _uiState.value.copy(error = "La ubicación es requerida")
                    return@launch
                }

                // Para modo crear, ESP32 es obligatorio
                if (panelId == null && esp32Device == null) {
                    _uiState.value = _uiState.value.copy(error = "Debe seleccionar un ESP32")
                    return@launch
                }

                val clientDocName = _uiState.value.clientDocName
                if (clientDocName.isEmpty()) {
                    _uiState.value = _uiState.value.copy(error = "Error: cliente no identificado")
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    isSaving = true,
                    error = null
                )

                if (panelId != null) {
                    // Modo edición
                    editExistingPanel(clientDocName, panelId, name, location, esp32Device)
                } else {
                    // Modo creación
                    createNewPanel(clientDocName, name, location, esp32Device!!)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error guardando panel", e)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "Error guardando panel: ${e.message}"
                )
            }
        }
    }

    /**
     * Crea un nuevo panel
     */
    private suspend fun createNewPanel(
        clientDocName: String,
        name: String,
        location: String,
        esp32Device: ESP32Device
    ) {
        try {
            Log.d(TAG, "Creando nuevo panel: $name")

            // Crear panel
            val panel = Panel.createNew(
                name = name,
                location = location,
                clientName = clientDocName,
                esp32Id = esp32Device.documentName
            )

            panelRepository.createNewPanel(
                clientDocName = clientDocName,
                panel = panel,
                esp32Id = esp32Device.documentName
            ).onSuccess { panelDocName ->
                Log.d(TAG, "Panel creado exitosamente: $panelDocName")
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true,
                    error = null
                )
            }.onFailure { error ->
                Log.e(TAG, "Error creando panel", error)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "Error creando panel: ${error.message}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en createNewPanel", e)
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                error = "Error creando panel: ${e.message}"
            )
        }
    }

    /**
     * Edita un panel existente
     */
    private suspend fun editExistingPanel(
        clientDocName: String,
        panelId: String,
        name: String,
        location: String,
        esp32Device: ESP32Device?
    ) {
        try {
            Log.d(TAG, "Editando panel existente: $panelId")

            val currentPanel = _uiState.value.currentPanel
            if (currentPanel == null) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "Panel actual no encontrado"
                )
                return
            }

            // Crear panel actualizado
            val updatedPanel = currentPanel.copy(
                name = name,
                location = location,
                lastUpdate = System.currentTimeMillis()
            )

            // Determinar nuevo ESP32 ID
            val newEsp32Id = esp32Device?.documentName ?: currentPanel.esp32_id

            panelRepository.updatePanel(
                clientDocName = clientDocName,
                panel = updatedPanel,
                newEsp32Id = if (newEsp32Id != currentPanel.esp32_id) newEsp32Id else null
            ).onSuccess {
                Log.d(TAG, "Panel editado exitosamente")
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true,
                    error = null
                )
            }.onFailure { error ->
                Log.e(TAG, "Error editando panel", error)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "Error editando panel: ${error.message}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en editExistingPanel", e)
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                error = "Error editando panel: ${e.message}"
            )
        }
    }

    /**
     * Valida si un nombre de panel es único
     */
    fun validatePanelName(name: String): Boolean {
        if (name.isBlank()) return false

        // En modo edición, permitir el nombre actual
        val currentPanel = _uiState.value.currentPanel
        if (currentPanel != null && currentPanel.name == name) {
            return true
        }

        // Verificar que no haya otro panel con el mismo nombre
        // (Esta validación se podría mejorar consultando el repositorio)
        return name.length >= 3 && name.length <= 50
    }

    /**
     * Valida si una ubicación es válida
     */
    fun validateLocation(location: String): Boolean {
        return location.isNotBlank() && location.length >= 3 && location.length <= 100
    }

    /**
     * Verifica si un ESP32 está disponible para asignación
     */
    fun isESP32Available(esp32Id: String): Boolean {
        return _uiState.value.availableESP32s.any { it.documentName == esp32Id }
    }

    /**
     * Obtiene información de un ESP32 específico
     */
    fun getESP32Info(esp32Id: String): ESP32Device? {
        return _uiState.value.availableESP32s.find { it.documentName == esp32Id }
    }

    /**
     * Verifica si se puede guardar con los datos actuales
     */
    fun canSave(name: String, location: String, esp32Device: ESP32Device?): Boolean {
        val nameValid = validatePanelName(name)
        val locationValid = validateLocation(location)
        val esp32Valid = if (_uiState.value.isEditMode) {
            // En modo edición, ESP32 es opcional (puede mantener el actual)
            true
        } else {
            // En modo creación, ESP32 es obligatorio
            esp32Device != null && isESP32Available(esp32Device.documentName)
        }

        return nameValid && locationValid && esp32Valid && !_uiState.value.isSaving
    }

    /**
     * Limpia errores del estado
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Limpia el estado de éxito de guardado
     */
    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }

    /**
     * Cancela operaciones en curso
     */
    fun cancelOperations() {
        initializationJob?.cancel()
        saveJob?.cancel()

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isSaving = false
        )
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel limpiado - cancelando operaciones")

        initializationJob?.cancel()
        saveJob?.cancel()

        // Limpiar listeners de repositorios si es necesario
        esp32Repository.clearListeners()
        panelRepository.clearListeners()

        Log.d(TAG, "ViewModel limpiado exitosamente")
    }
}