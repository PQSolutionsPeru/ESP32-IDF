package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserData
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.presentation.state.ClientManagementState
import com.pqsolutions.hdd_monitor.presentation.state.ClientOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClientManagementViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val panelRepository: PanelRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ClientManagementState())
    val state: StateFlow<ClientManagementState> = _state.asStateFlow()

    init {
        loadClients()
    }

    fun loadClients() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isLoading = true, error = null) }
                val result = userRepository.getClients()
                result.fold(
                    onSuccess = { clients ->
                        _state.update {
                            it.copy(
                                clients = clients,
                                isLoading = false,
                                error = null,
                                lastUpdate = System.currentTimeMillis()
                            )
                        }
                        Log.d(TAG, "Loaded ${clients.size} clients successfully")
                    },
                    onFailure = { error ->
                        handleError("Error loading clients", error)
                    }
                )
            } catch (e: Exception) {
                handleError("Unexpected error loading clients", e)
            }
        }
    }

    fun selectClient(client: Client) {
        _state.update {
            it.copy(
                selectedClient = client,
                currentScreen = ClientManagementState.Screen.ClientDetail
            )
        }
        loadPanelsAndUsers(client.documentName)
    }

    private fun loadPanelsAndUsers(clientDocName: String) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isLoading = true) }

                // Cargar paneles
                panelRepository.getPanels(clientDocName).collect { panels ->
                    // Cargar usuarios
                    val usersResult = userRepository.getUsers()
                    usersResult.fold(
                        onSuccess = { allUsers ->
                            // Filtrar usuarios por clientDocName sin verificar prefijo
                            val clientUsers = allUsers.filter {
                                it.clientDocName == clientDocName
                            }

                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    panels = panels,
                                    users = clientUsers,
                                    error = null
                                )
                            }

                            Log.d("ClientManagementVM", "Loaded ${clientUsers.size} users for client $clientDocName")
                        },
                        onFailure = { error ->
                            handleError("Error loading users", error)
                        }
                    )
                }
            } catch (e: Exception) {
                handleError("Error loading panels and users", e)
            }
        }
    }

    fun createClient(client: Client) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                userRepository.createClient(client)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Cliente creado exitosamente"),
                                showClientDialog = false
                            )
                        }
                        loadClients()
                    }
                    .onFailure { error ->
                        handleError("Error creating client", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error creating client", e)
            }
        }
    }

    fun updateClient(client: Client) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                userRepository.updateClient(client)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Cliente actualizado exitosamente"),
                                showClientDialog = false
                            )
                        }
                        loadClients()
                    }
                    .onFailure { error ->
                        handleError("Error updating client", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error updating client", e)
            }
        }
    }

    fun deleteClient(client: Client) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                userRepository.deleteClient(client.documentName)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Cliente eliminado exitosamente"),
                                selectedClient = null,
                                currentScreen = ClientManagementState.Screen.ClientList
                            )
                        }
                        loadClients()
                    }
                    .onFailure { error ->
                        handleError("Error deleting client", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error deleting client", e)
            }
        }
    }

    fun createPanel(panel: Panel) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                val clientDocName = state.value.selectedClient?.documentName
                    ?: throw IllegalStateException("No client selected")

                panelRepository.createNewPanel(clientDocName, panel)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Panel creado exitosamente"),
                                showPanelDialog = false
                            )
                        }
                        loadPanelsAndUsers(clientDocName)
                    }
                    .onFailure { error ->
                        handleError("Error creating panel", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error creating panel", e)
            }
        }
    }

    fun updatePanel(panel: Panel) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                val clientDocName = state.value.selectedClient?.documentName
                    ?: throw IllegalStateException("No client selected")

                panelRepository.updatePanel(clientDocName, panel)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Panel actualizado exitosamente"),
                                showPanelDialog = false
                            )
                        }
                        loadPanelsAndUsers(clientDocName)
                    }
                    .onFailure { error ->
                        handleError("Error updating panel", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error updating panel", e)
            }
        }
    }

    fun deletePanel(panel: Panel) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                val clientDocName = state.value.selectedClient?.documentName
                    ?: throw IllegalStateException("No client selected")

                panelRepository.deletePanel(clientDocName, panel.documentName)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Panel eliminado exitosamente")
                            )
                        }
                        loadPanelsAndUsers(clientDocName)
                    }
                    .onFailure { error ->
                        handleError("Error deleting panel", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error deleting panel", e)
            }
        }
    }

    fun createUser(userData: UserData) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                userRepository.createUser(userData)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Usuario creado exitosamente"),
                                showUserDialog = false
                            )
                        }
                        loadPanelsAndUsers(userData.clientDocName)
                    }
                    .onFailure { error ->
                        handleError("Error creating user", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error creating user", e)
            }
        }
    }

    fun updateUser(userData: UserData) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                userRepository.updateUser(userData)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Usuario actualizado exitosamente"),
                                showUserDialog = false
                            )
                        }
                        loadPanelsAndUsers(userData.clientDocName)
                    }
                    .onFailure { error ->
                        handleError("Error updating user", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error updating user", e)
            }
        }
    }

    fun deleteUser(userData: UserData) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = ClientOperation.Loading) }

                userRepository.deleteUser(userData.documentName, userData.clientDocName)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = ClientOperation.Success("Usuario eliminado exitosamente")
                            )
                        }
                        loadPanelsAndUsers(userData.clientDocName)
                    }
                    .onFailure { error ->
                        handleError("Error deleting user", error)
                    }
            } catch (e: Exception) {
                handleError("Unexpected error deleting user", e)
            }
        }
    }

    fun showPanelList() {
        _state.update { it.copy(currentScreen = ClientManagementState.Screen.PanelList) }
    }

    fun showUserList() {
        _state.update { it.copy(currentScreen = ClientManagementState.Screen.UserList) }
    }

    fun showCreatePanelDialog() {
        _state.update {
            it.copy(
                showPanelDialog = true,
                selectedPanel = null
            )
        }
    }

    fun showEditPanelDialog(panel: Panel) {
        _state.update {
            it.copy(
                showPanelDialog = true,
                selectedPanel = panel
            )
        }
    }

    fun dismissPanelDialog() {
        _state.update {
            it.copy(
                showPanelDialog = false,
                selectedPanel = null
            )
        }
    }

    fun showCreateUserDialog() {
        _state.update {
            it.copy(
                showUserDialog = true,
                selectedUser = null
            )
        }
    }

    fun showEditUserDialog(user: UserData) {
        _state.update {
            it.copy(
                showUserDialog = true,
                selectedUser = user
            )
        }
    }

    fun dismissUserDialog() {
        _state.update {
            it.copy(
                showUserDialog = false,
                selectedUser = null
            )
        }
    }

    fun showCreateClientDialog() {
        _state.update { it.copy(showClientDialog = true) }
    }

    fun dismissClientDialog() {
        _state.update { it.copy(showClientDialog = false) }
    }

    fun navigateBack() {
        _state.update { state ->
            when (state.currentScreen) {
                ClientManagementState.Screen.ClientDetail -> state.copy(
                    selectedClient = null,
                    currentScreen = ClientManagementState.Screen.ClientList
                )
                ClientManagementState.Screen.PanelList,
                ClientManagementState.Screen.UserList -> state.copy(
                    currentScreen = ClientManagementState.Screen.ClientDetail
                )
                ClientManagementState.Screen.PanelDetail -> state.copy(
                    currentScreen = ClientManagementState.Screen.PanelList
                )
                ClientManagementState.Screen.UserDetail -> state.copy(
                    currentScreen = ClientManagementState.Screen.UserList
                )
                else -> state
            }
        }
    }

    private fun handleError(message: String, error: Throwable) {
        Log.e(TAG, "$message: ${error.message}", error)
        _state.update {
            it.copy(
                isLoading = false,
                error = error.message ?: "Error desconocido",
                currentOperation = ClientOperation.Error(error.message ?: "Error desconocido")
            )
        }
    }

    fun clearError() {
        _state.update {
            it.copy(
                error = null,
                currentOperation = ClientOperation.None
            )
        }
    }

    companion object {
        private const val TAG = "ClientManagementVM"
    }
}