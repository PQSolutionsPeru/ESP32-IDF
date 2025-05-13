package com.pqsolutions.hdd_monitor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.UserData
import com.pqsolutions.hdd_monitor.data.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserManagementViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserManagementUiState())
    val uiState: StateFlow<UserManagementUiState> = _uiState.asStateFlow()

    init {
        loadUsers()
        loadClients()
    }

    private fun loadUsers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = userRepository.getUsers()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    users = result.getOrNull() ?: emptyList(),
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    private fun loadClients() {
        viewModelScope.launch {
            val result = userRepository.getClients()
            _uiState.update { currentState ->
                currentState.copy(
                    clients = result.getOrNull() ?: emptyList(),
                    error = if (currentState.error == null && result.isFailure) {
                        result.exceptionOrNull()?.message
                    } else currentState.error
                )
            }
        }
    }

    fun showCreateUserDialog() {
        _uiState.update { it.copy(showUserDialog = true, selectedUser = null) }
    }

    fun showEditUserDialog(user: UserData) {
        _uiState.update { it.copy(showUserDialog = true, selectedUser = user) }
    }

    fun dismissUserDialog() {
        _uiState.update { it.copy(showUserDialog = false, selectedUser = null) }
    }

    fun showNewClientDialog() {
        _uiState.update { it.copy(showClientDialog = true) }
    }

    fun dismissClientDialog() {
        _uiState.update { it.copy(showClientDialog = false) }
    }

    fun createUser(userData: UserData) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showUserDialog = false) }
            val result = userRepository.createUser(userData)
            handleOperationResult(result) {
                loadUsers()
            }
        }
    }

    fun updateUser(userData: UserData) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showUserDialog = false) }
            val result = userRepository.updateUser(userData)
            handleOperationResult(result) {
                loadUsers()
            }
        }
    }

    fun deleteUser(userData: UserData) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = userRepository.deleteUser(userData.documentName, userData.clientDocName)
            handleOperationResult(result) {
                loadUsers()
            }
        }
    }

    fun createClient(clientName: String) {
        if (clientName.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showClientDialog = false) }
            val result = userRepository.createClient(Client(name = clientName))
            handleOperationResult(result) {
                loadClients()
            }
        }
    }

    private fun handleOperationResult(result: Result<Unit>, onSuccess: suspend () -> Unit) {
        viewModelScope.launch {
            if (result.isSuccess) {
                onSuccess()
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.exceptionOrNull()?.message
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class UserManagementUiState(
    val isLoading: Boolean = false,
    val users: List<UserData> = emptyList(),
    val clients: List<Client> = emptyList(),
    val error: String? = null,
    val showUserDialog: Boolean = false,
    val showClientDialog: Boolean = false,
    val selectedUser: UserData? = null
)