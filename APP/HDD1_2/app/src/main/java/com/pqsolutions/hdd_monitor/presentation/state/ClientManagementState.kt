package com.pqsolutions.hdd_monitor.presentation.state

import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.UserData

sealed class ClientOperation {
    object None : ClientOperation()
    object Loading : ClientOperation()
    data class Success(val message: String? = null) : ClientOperation()
    data class Error(val message: String) : ClientOperation()

    fun isLoading(): Boolean = this is Loading
    fun isError(): Boolean = this is Error
    fun isSuccess(): Boolean = this is Success
}

sealed class ClientUIEvent {
    data class ShowSnackbar(val message: String) : ClientUIEvent()
    data class Navigate(val route: String) : ClientUIEvent()
    object NavigateBack : ClientUIEvent()
    data class ShowDialog(val message: String) : ClientUIEvent()
    object DismissDialog : ClientUIEvent()
    object RefreshData : ClientUIEvent()
}

data class ClientManagementState(
    // Estados de carga y error
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val currentOperation: ClientOperation = ClientOperation.None,

    // Datos principales
    val clients: List<Client> = emptyList(),
    val selectedClient: Client? = null,
    val panels: List<Panel> = emptyList(),
    val users: List<UserData> = emptyList(),

    // Estados de diálogos
    val showClientDialog: Boolean = false,
    val showPanelDialog: Boolean = false,
    val showUserDialog: Boolean = false,
    val selectedPanel: Panel? = null,
    val selectedUser: UserData? = null,

    // Navegación
    val currentScreen: Screen = Screen.ClientList,

    // Estado de última actualización
    val lastUpdate: Long = System.currentTimeMillis()
) {
    enum class Screen {
        ClientList,      // Lista de clientes
        ClientDetail,    // Detalles y opciones del cliente seleccionado
        PanelList,       // Lista de paneles del cliente
        PanelDetail,     // Detalles del panel seleccionado
        UserList,        // Lista de usuarios del cliente
        UserDetail       // Detalles del usuario seleccionado
    }

    // Propiedades computadas
    val hasClients: Boolean
        get() = clients.isNotEmpty()

    val hasError: Boolean
        get() = error != null

    val canCreateClient: Boolean
        get() = !isLoading && error == null

    val isInDetailMode: Boolean
        get() = currentScreen != Screen.ClientList

    val hasSelectedClient: Boolean
        get() = selectedClient != null

    val hasPanels: Boolean
        get() = panels.isNotEmpty()

    val hasUsers: Boolean
        get() = users.isNotEmpty()

    fun isValid(): Boolean {
        return !isLoading &&
                error == null &&
                currentOperation !is ClientOperation.Error
    }

    companion object {
        fun initial() = ClientManagementState()

        fun loading() = ClientManagementState(isLoading = true)

        fun success(clients: List<Client>) = ClientManagementState(
            clients = clients,
            isLoading = false,
            error = null
        )

        fun error(message: String) = ClientManagementState(
            isLoading = false,
            error = message
        )
    }

    override fun toString(): String {
        return "ClientManagementState(" +
                "loading=$isLoading, " +
                "refreshing=$isRefreshing, " +
                "error=$error, " +
                "operation=$currentOperation, " +
                "clients=${clients.size}, " +
                "selectedClient=${selectedClient?.name}, " +
                "panels=${panels.size}, " +
                "users=${users.size}, " +
                "screen=$currentScreen, " +
                "lastUpdate=$lastUpdate" +
                ")"
    }
}