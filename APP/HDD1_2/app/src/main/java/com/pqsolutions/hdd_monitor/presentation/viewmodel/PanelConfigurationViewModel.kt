package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.data.Client
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

    fun initializeScreen(panelId: String?) {
        initializationJob?.cancel()

        initializationJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    isEditMode = panelId != null
                )

                val currentUser = userRepository.getCurrentUser()
                if (currentUser == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                    return@launch
                }

                val isAdmin = currentUser.role == UserRole.ADMIN
                _uiState.value = _uiState.value.copy(
                    userRole = currentUser.role.name,
                    isAdmin = isAdmin
                )

                if (isAdmin && panelId == null) {
                    loadClientsForAdmin()
                }

                val (clientDocName, clientDisplayName) = when (currentUser.role) {
                    UserRole.USER -> {
                        val displayName = if (currentUser.clientDocName.isNotEmpty()) {
                            getClientDisplayName(currentUser.clientDocName)
                        } else ""
                        Pair(currentUser.clientDocName, displayName)
                    }
                    UserRole.ADMIN -> {
                        if (panelId != null) {
                            val clientInfo = getPanelClient(panelId)
                            if (clientInfo.first.isEmpty()) {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    error = "No se pudo determinar el cliente del panel"
                                )
                                return@launch
                            }
                            clientInfo
                        } else {
                            Pair("", "")
                        }
                    }
                }

                _uiState.value = _uiState.value.copy(
                    clientDocName = clientDocName,
                    clientDisplayName = clientDisplayName
                )

                if (panelId != null) {
                    loadEditModeData(clientDocName, panelId)
                } else if (currentUser.role == UserRole.USER) {
                    loadCreateModeData(clientDocName)
                } else {
                    loadCreateModeDataForAdmin()
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error inicializando: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadClientsForAdmin() {
        try {
            userRepository.getClients().fold(
                onSuccess = { clients ->
                    _uiState.value = _uiState.value.copy(
                        availableClients = clients
                    )
                    Log.d(TAG, "Clientes cargados: ${clients.size}")
                },
                onFailure = { error ->
                    Log.e(TAG, "Error cargando clientes", error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando clientes", e)
        }
    }

    private suspend fun loadCreateModeDataForAdmin() {
        try {
            Log.d(TAG, "Cargando datos para creación (admin)")

            esp32Repository.observeUnassignedESP32s()
                .catch { error ->
                    Log.e(TAG, "Error en flujo de ESP32s no asignados", error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Error cargando ESP32s: ${error.message}"
                    )
                }
                .collect { unassignedESP32s ->
                    val availableESP32s = unassignedESP32s.filter { esp32 ->
                        esp32.status in listOf(
                            ESP32Device.STATUS_AWAITING_CONFIG,
                            ESP32Device.STATUS_PENDING_ASSIGNMENT,
                            ESP32Device.STATUS_ONLINE,
                            ESP32Device.STATUS_RUNNING
                        )
                    }

                    val esp32StatusMap = unassignedESP32s.associate {
                        it.documentName to it.status
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        availableESP32s = availableESP32s,
                        esp32StatusMap = esp32StatusMap,
                        error = null
                    )

                    Log.d(TAG, "Datos de creación (admin) cargados - ESP32s disponibles: ${availableESP32s.size}")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error cargando datos de creación", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Error cargando ESP32s: ${e.message}"
            )
        }
    }

    private suspend fun getClientDisplayName(clientDocName: String): String {
        return try {
            userRepository.getClients()
                .getOrNull()
                ?.find { it.documentName == clientDocName }
                ?.name ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun getPanelClient(panelId: String): Pair<String, String> {
        return try {
            val parts = panelId.split("_")
            if (parts.size >= 3 && parts.last().startsWith("client")) {
                val clientDocName = parts.takeLast(2).joinToString("_")
                val clientName = getClientDisplayName(clientDocName)
                return Pair(clientDocName, clientName)
            }

            val clientsResult = userRepository.getClients()
            if (clientsResult.isFailure) {
                return Pair("", "")
            }

            val clients = clientsResult.getOrNull() ?: emptyList()

            for (client in clients) {
                val panelExists = panelRepository.verifyPanelExists(client.documentName, panelId)
                if (panelExists) {
                    return Pair(client.documentName, client.name)
                }
            }

            Pair("", "")
        } catch (e: Exception) {
            Pair("", "")
        }
    }

    private suspend fun loadEditModeData(clientDocName: String, panelId: String) {
        try {
            combine(
                panelRepository.observePanelUpdates(clientDocName, panelId),
                esp32Repository.observeUnassignedESP32s(),
                esp32Repository.observeAssignedESP32s(clientDocName)
            ) { panel, unassignedESP32s, assignedESP32s ->
                Triple(panel, unassignedESP32s, assignedESP32s)
            }
                .catch { error ->
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

                    val availableESP32s = buildList {
                        addAll(unassignedESP32s)
                        assignedESP32s.find { it.documentName == panel.esp32_id }?.let { add(it) }
                    }

                    val esp32StatusMap = buildMap {
                        unassignedESP32s.forEach { put(it.documentName, it.status) }
                        assignedESP32s.forEach { put(it.documentName, it.status) }
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentPanel = panel,
                        availableESP32s = availableESP32s,
                        esp32StatusMap = esp32StatusMap,
                        error = null
                    )
                }

        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Error cargando panel: ${e.message}"
            )
        }
    }

    private suspend fun loadCreateModeData(clientDocName: String) {
        try {
            esp32Repository.observeUnassignedESP32s()
                .catch { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Error cargando ESP32s: ${error.message}"
                    )
                }
                .collect { unassignedESP32s ->
                    val availableESP32s = unassignedESP32s.filter { esp32 ->
                        esp32.status in listOf(
                            ESP32Device.STATUS_AWAITING_CONFIG,
                            ESP32Device.STATUS_PENDING_ASSIGNMENT,
                            ESP32Device.STATUS_ONLINE,
                            ESP32Device.STATUS_RUNNING
                        )
                    }

                    val esp32StatusMap = unassignedESP32s.associate {
                        it.documentName to it.status
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        availableESP32s = availableESP32s,
                        esp32StatusMap = esp32StatusMap,
                        error = null
                    )
                }

        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Error cargando ESP32s: ${e.message}"
            )
        }
    }

    fun savePanel(
        panelId: String?,
        name: String,
        location: String,
        esp32Device: ESP32Device?,
        selectedClient: Client? = null
    ) {
        saveJob?.cancel()

        saveJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Guardando panel - Modo: ${if (panelId != null) "Editar" else "Crear"}")

                if (name.isBlank()) {
                    _uiState.value = _uiState.value.copy(error = "El nombre del panel es requerido")
                    return@launch
                }

                if (location.isBlank()) {
                    _uiState.value = _uiState.value.copy(error = "La ubicación es requerida")
                    return@launch
                }

                if (panelId == null && esp32Device == null) {
                    _uiState.value = _uiState.value.copy(error = "Debe seleccionar un ESP32")
                    return@launch
                }

                var clientDocName = _uiState.value.clientDocName

                if (_uiState.value.isAdmin && panelId == null) {
                    if (selectedClient == null) {
                        _uiState.value = _uiState.value.copy(error = "Debe seleccionar un cliente")
                        return@launch
                    }
                    clientDocName = selectedClient.documentName
                }

                if (clientDocName.isEmpty()) {
                    _uiState.value = _uiState.value.copy(error = "Error: cliente no identificado")
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    isSaving = true,
                    error = null
                )

                if (panelId != null) {
                    editExistingPanel(clientDocName, panelId, name, location, esp32Device)
                } else {
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

    private suspend fun createNewPanel(
        clientDocName: String,
        name: String,
        location: String,
        esp32Device: ESP32Device
    ) {
        try {
            Log.d(TAG, "Creando nuevo panel: $name")

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

            val updatedPanel = currentPanel.copy(
                name = name,
                location = location,
                lastUpdate = System.currentTimeMillis()
            )

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

    fun validatePanelName(name: String): Boolean {
        if (name.isBlank()) return false

        val currentPanel = _uiState.value.currentPanel
        if (currentPanel != null && currentPanel.name == name) {
            return true
        }

        return name.length >= 3 && name.length <= 50
    }

    fun validateLocation(location: String): Boolean {
        return location.isNotBlank() && location.length >= 3 && location.length <= 100
    }

    fun isESP32Available(esp32Id: String): Boolean {
        return _uiState.value.availableESP32s.any { it.documentName == esp32Id }
    }

    fun getESP32Info(esp32Id: String): ESP32Device? {
        return _uiState.value.availableESP32s.find { it.documentName == esp32Id }
    }

    fun canSave(name: String, location: String, esp32Device: ESP32Device?, selectedClient: Client?): Boolean {
        val nameValid = validatePanelName(name)
        val locationValid = validateLocation(location)
        val esp32Valid = if (_uiState.value.isEditMode) {
            true
        } else {
            esp32Device != null && isESP32Available(esp32Device.documentName)
        }

        val clientValid = if (_uiState.value.isAdmin && !_uiState.value.isEditMode) {
            selectedClient != null
        } else {
            true
        }

        return nameValid && locationValid && esp32Valid && clientValid && !_uiState.value.isSaving
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }

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

        esp32Repository.clearListeners()
        panelRepository.clearListeners()

        Log.d(TAG, "ViewModel limpiado exitosamente")
    }
}