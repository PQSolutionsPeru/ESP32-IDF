package com.pqsolutions.hdd_monitor.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.UserData
import com.pqsolutions.hdd_monitor.presentation.components.AnimatedNotificationBell
import com.pqsolutions.hdd_monitor.presentation.components.ClientDetailContent
import com.pqsolutions.hdd_monitor.presentation.components.ClientDialog
import com.pqsolutions.hdd_monitor.presentation.components.ClientList
import com.pqsolutions.hdd_monitor.presentation.components.LoadingContent
import com.pqsolutions.hdd_monitor.presentation.components.PanelDialog
import com.pqsolutions.hdd_monitor.presentation.components.PanelList
import com.pqsolutions.hdd_monitor.presentation.components.ScreenTopBar
import com.pqsolutions.hdd_monitor.presentation.components.UserDialog
import com.pqsolutions.hdd_monitor.presentation.components.UserList
import com.pqsolutions.hdd_monitor.presentation.state.ClientManagementState
import com.pqsolutions.hdd_monitor.presentation.state.ClientOperation
import com.pqsolutions.hdd_monitor.presentation.theme.HDD1_2Theme
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect
import com.pqsolutions.hdd_monitor.presentation.viewmodel.ClientManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientManagementScreen(
    viewModel: ClientManagementViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    hasPendingNotifications: Boolean,
    onNotificationClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showDeleteConfirmation by remember { mutableStateOf<Any?>(null) }
    var editingClient by remember { mutableStateOf<Client?>(null) }

    val showEmptyState by remember {
        derivedStateOf {
            when (state.currentScreen) {
                ClientManagementState.Screen.ClientList -> state.clients.isEmpty()
                ClientManagementState.Screen.PanelList -> state.panels.isEmpty()
                ClientManagementState.Screen.UserList -> state.users.isEmpty()
                else -> false
            } && !state.isLoading && state.error == null
        }
    }

    BackHandler {
        onBackClick()
    }

    HDD1_2Theme {
        Scaffold(
            topBar = {
                ScreenTopBar(
                    title = when (state.currentScreen) {
                        ClientManagementState.Screen.ClientList ->
                            stringResource(R.string.client_management)
                        ClientManagementState.Screen.ClientDetail ->
                            state.selectedClient?.name ?: ""
                        ClientManagementState.Screen.PanelList ->
                            stringResource(R.string.manage_panels)
                        ClientManagementState.Screen.UserList ->
                            stringResource(R.string.manage_users)
                        else -> ""
                    },
                    onBackClick = onBackClick,
                    actions = {
                        AnimatedNotificationBell(
                            hasNewNotifications = hasPendingNotifications,
                            onClick = {
                                performHapticFeedback(context)
                                onNotificationClick()
                            },
                            notificationCount = 0
                        )

                        when (state.currentScreen) {
                            ClientManagementState.Screen.ClientList -> {
                                IconButton(
                                    onClick = {
                                        performHapticFeedback(context)
                                        viewModel.showCreateClientDialog()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.new_client)
                                    )
                                }
                            }
                            ClientManagementState.Screen.PanelList -> {
                                IconButton(
                                    onClick = {
                                        performHapticFeedback(context)
                                        viewModel.showCreatePanelDialog()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.new_panel)
                                    )
                                }
                            }
                            ClientManagementState.Screen.UserList -> {
                                IconButton(
                                    onClick = {
                                        performHapticFeedback(context)
                                        viewModel.showCreateUserDialog()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.new_user)
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                AnimatedContent(state.currentScreen) { screen ->
                    when (screen) {
                        ClientManagementState.Screen.ClientList -> {
                            LoadingContent(
                                isLoading = state.isLoading,
                                isEmpty = showEmptyState,
                                error = state.error,
                                onRetry = { viewModel.loadClients() },
                                emptyContent = { EmptyStateMessage(ClientManagementState.Screen.ClientList) }
                            ) {
                                ClientList(
                                    clients = state.clients,
                                    onClientSelect = { viewModel.selectClient(it) },
                                    onEditClient = { client ->
                                        performHapticFeedback(context)
                                        editingClient = client
                                    },
                                    onDeleteClient = { client ->
                                        showDeleteConfirmation = client
                                    }
                                )
                            }
                        }
                        ClientManagementState.Screen.ClientDetail -> {
                            state.selectedClient?.let { client ->
                                ClientDetailContent(
                                    client = client,
                                    panelCount = state.panels.size,
                                    userCount = state.users.size,
                                    onManagePanels = { viewModel.showPanelList() },
                                    onManageUsers = { viewModel.showUserList() }
                                )
                            }
                        }
                        ClientManagementState.Screen.PanelList -> {
                            LoadingContent(
                                isLoading = state.isLoading,
                                isEmpty = showEmptyState,
                                error = state.error,
                                onRetry = { state.selectedClient?.let { viewModel.selectClient(it) } },
                                emptyContent = { EmptyStateMessage(ClientManagementState.Screen.PanelList) }
                            ) {
                                PanelList(
                                    panels = state.panels,
                                    onPanelSelect = { /* TODO: Implementar vista detalle */ },
                                    onEditPanel = { panel ->
                                        performHapticFeedback(context)
                                        viewModel.showEditPanelDialog(panel)
                                    },
                                    onDeletePanel = { panel ->
                                        showDeleteConfirmation = panel
                                    }
                                )
                            }
                        }
                        ClientManagementState.Screen.UserList -> {
                            LoadingContent(
                                isLoading = state.isLoading,
                                isEmpty = showEmptyState,
                                error = state.error,
                                onRetry = { state.selectedClient?.let { viewModel.selectClient(it) } },
                                emptyContent = { EmptyStateMessage(ClientManagementState.Screen.UserList) }
                            ) {
                                UserList(
                                    users = state.users,
                                    onEditClick = { user ->
                                        performHapticFeedback(context)
                                        viewModel.showEditUserDialog(user)
                                    },
                                    onDeleteClick = { user ->
                                        showDeleteConfirmation = user
                                    }
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        // Diálogos
        HandleDialogs(
            state = state,
            showDeleteConfirmation = showDeleteConfirmation,
            onDismissDelete = { showDeleteConfirmation = null },
            onConfirmDelete = { target ->
                when (target) {
                    is Client -> viewModel.deleteClient(target)
                    is Panel -> viewModel.deletePanel(target)
                    is UserData -> viewModel.deleteUser(target)
                }
                showDeleteConfirmation = null
            },
            viewModel = viewModel
        )

        // Diálogo de edición de cliente
        if (editingClient != null) {
            ClientEditDialog(
                client = editingClient!!,
                onDismiss = { editingClient = null },
                onConfirm = { updatedClient ->
                    viewModel.updateClient(updatedClient)
                    editingClient = null
                }
            )
        }
    }
}

@Composable
private fun AnimatedContent(
    screen: ClientManagementState.Screen,
    content: @Composable (ClientManagementState.Screen) -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInHorizontally(),
        exit = fadeOut() + slideOutHorizontally()
    ) {
        content(screen)
    }
}

@Composable
private fun ClientEditDialog(
    client: Client,
    onDismiss: () -> Unit,
    onConfirm: (Client) -> Unit
) {
    var name by remember { mutableStateOf(client.name) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_client)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.client_name)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    performHapticFeedback(context)
                    onConfirm(client.copy(name = name))
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    performHapticFeedback(context)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun HandleDialogs(
    state: ClientManagementState,
    showDeleteConfirmation: Any?,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (Any) -> Unit,
    viewModel: ClientManagementViewModel
) {
    val context = LocalContext.current

    // Diálogo de eliminación
    if (showDeleteConfirmation != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.confirm_deletion)) },
            text = {
                Text(
                    when (showDeleteConfirmation) {
                        is Client -> stringResource(R.string.info_confirm_delete_client)
                        is Panel -> stringResource(R.string.info_confirm_delete_panel)
                        is UserData -> stringResource(R.string.info_confirm_delete_user)
                        else -> stringResource(R.string.delete_confirmation_message)
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        performHapticFeedback(context)
                        onConfirmDelete(showDeleteConfirmation)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        performHapticFeedback(context)
                        onDismissDelete()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Diálogo de panel
    if (state.showPanelDialog) {
        PanelDialog(
            panel = state.selectedPanel,
            onDismiss = { viewModel.dismissPanelDialog() },
            onConfirm = { panel ->
                if (state.selectedPanel == null) {
                    viewModel.createPanel(panel)
                } else {
                    viewModel.updatePanel(panel)
                }
            }
        )
    }

    // Diálogo de usuario
    if (state.showUserDialog) {
        UserDialog(
            user = state.selectedUser,
            clients = state.clients,
            onCreateNewClient = { viewModel.showCreateClientDialog() },
            onDismiss = { viewModel.dismissUserDialog() },
            onConfirm = { userData ->
                if (state.selectedUser == null) {
                    viewModel.createUser(userData)
                } else {
                    viewModel.updateUser(userData)
                }
            }
        )
    }

    // Diálogo de cliente
    if (state.showClientDialog) {
        ClientDialog(
            onDismiss = { viewModel.dismissClientDialog() },
            onConfirm = { clientName ->
                viewModel.createClient(Client(documentName = "", name = clientName))
            }
        )
    }

    // Diálogo de operación en progreso
    if (state.currentOperation is ClientOperation.Loading) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.please_wait)) },
            text = { CircularProgressIndicator() },
            confirmButton = { }
        )
    }
}

@Composable
private fun EmptyStateMessage(screen: ClientManagementState.Screen) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = when (screen) {
                ClientManagementState.Screen.ClientList -> stringResource(R.string.no_clients)
                ClientManagementState.Screen.PanelList -> stringResource(R.string.no_panels)
                ClientManagementState.Screen.UserList -> stringResource(R.string.no_users)
                else -> stringResource(R.string.no_data_available)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}