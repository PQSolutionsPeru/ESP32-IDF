package com.pqsolutions.hdd_monitor.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.presentation.components.AnimatedNotificationBell
import com.pqsolutions.hdd_monitor.presentation.components.ClientDialog
import com.pqsolutions.hdd_monitor.presentation.components.LoadingContent
import com.pqsolutions.hdd_monitor.presentation.components.ScreenTopBar
import com.pqsolutions.hdd_monitor.presentation.components.UserDialog
import com.pqsolutions.hdd_monitor.presentation.components.UserList
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect
import com.pqsolutions.hdd_monitor.presentation.viewmodel.UserManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(
    viewModel: UserManagementViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    hasPendingNotifications: Boolean,
    onNotificationClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = stringResource(R.string.manage_users),
                onBackClick = onBackClick,
                actions = {
                    AnimatedNotificationBell(
                        hasNewNotifications = hasPendingNotifications,
                        onClick = {
                            performHapticFeedback(context)
                            onNotificationClick()
                        },
                        notificationCount = 0  // Usando valor fijo como en ClientManagementScreen
                    )
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
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LoadingContent(
                isLoading = uiState.isLoading,
                isEmpty = uiState.users.isEmpty() && !uiState.isLoading,
                error = uiState.error,
                onRetry = { viewModel.clearError() },
                emptyContent = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_users),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            ) {
                UserList(
                    users = uiState.users,
                    onEditClick = { user ->
                        performHapticFeedback(context)
                        viewModel.showEditUserDialog(user)
                    },
                    onDeleteClick = { user ->
                        performHapticFeedback(context)
                        viewModel.deleteUser(user)
                    }
                )
            }
        }
    }

    // Diálogos
    if (uiState.showUserDialog) {
        UserDialog(
            user = uiState.selectedUser,
            clients = uiState.clients,
            onCreateNewClient = { viewModel.showNewClientDialog() },
            onDismiss = {
                performHapticFeedback(context)
                viewModel.dismissUserDialog()
            },
            onConfirm = { userData ->
                performHapticFeedback(context)
                if (userData.documentName.isEmpty()) {
                    viewModel.createUser(userData)
                } else {
                    viewModel.updateUser(userData)
                }
            }
        )
    }

    if (uiState.showClientDialog) {
        ClientDialog(
            onDismiss = {
                performHapticFeedback(context)
                viewModel.dismissClientDialog()
            },
            onConfirm = { clientName ->
                performHapticFeedback(context)
                viewModel.createClient(clientName)
            }
        )
    }
}