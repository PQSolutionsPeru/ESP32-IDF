package com.pqsolutions.hdd_monitor.presentation.screens

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.Relay
import com.pqsolutions.hdd_monitor.presentation.components.AnimatedNotificationBell
import com.pqsolutions.hdd_monitor.presentation.components.ScreenTopBar
import com.pqsolutions.hdd_monitor.presentation.navigation.Screen
import com.pqsolutions.hdd_monitor.presentation.theme.HDD1_2Theme
import com.pqsolutions.hdd_monitor.presentation.theme.PanelColors
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect
import com.pqsolutions.hdd_monitor.presentation.viewmodel.DashboardViewModel
import com.pqsolutions.hdd_monitor.presentation.viewmodel.NotificationViewModel
import kotlinx.coroutines.launch

private const val TAG = "UserDashboardScreen"

@Composable
fun UserDashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    notificationViewModel: NotificationViewModel = hiltViewModel(),
    onLogoutClick: () -> Unit,
    onViewEventsClick: () -> Unit,
    onViewNotificationHistoryClick: () -> Unit,
    onManageESP32Click: () -> Unit,
    hasPendingNotifications: Boolean,
    selectedPanelId: String? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val notificationUiState by notificationViewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadPanels()
        viewModel.startPeriodicRefresh()
        notificationViewModel.restartNotificationCollection()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPeriodicRefresh()
        }
    }

    HDD1_2Theme {
        Scaffold(
            topBar = {
                ScreenTopBar(
                    title = stringResource(R.string.user_dashboard_title),
                    actions = {
                        IconButton(
                            onClick = {
                                performHapticFeedback(context)
                                viewModel.loadPanels()
                            },
                            enabled = !uiState.isLoading
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Actualizar",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        AnimatedNotificationBell(
                            hasNewNotifications = hasPendingNotifications,
                            notificationCount = notificationUiState.pendingCount,
                            onClick = onViewNotificationHistoryClick
                        )
                    }
                )
            },
            bottomBar = {
                Button(
                    onClick = {
                        performHapticFeedback(context)
                        onLogoutClick()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(stringResource(R.string.logout))
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                DashboardActions(
                    onViewEventsClick = onViewEventsClick,
                    onViewNotificationHistoryClick = onViewNotificationHistoryClick,
                    onManageESP32Click = onManageESP32Click,
                    context = context
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    if (uiState.panels.isEmpty()) {
                        item {
                            EmptyPanelsContent()
                        }
                    } else {
                        uiState.panels.forEach { panel ->
                            item(key = panel.documentName) {
                                UserPanelItem(panel)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardActions(
    onViewEventsClick: () -> Unit,
    onViewNotificationHistoryClick: () -> Unit,
    onManageESP32Click: () -> Unit,
    context: android.content.Context
) {
    Log.d(TAG, "Rendering DashboardActions")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        DashboardButton(
            onClick = {
                Log.d(TAG, "View Events button clicked - iniciando navegación")
                performHapticFeedback(context)
                kotlinx.coroutines.MainScope().launch {
                    kotlinx.coroutines.delay(100)
                    onViewEventsClick()
                }
            },
            text = stringResource(R.string.view_events)
        )
        Spacer(modifier = Modifier.height(8.dp))
        DashboardButton(
            onClick = {
                Log.d(TAG, "View Notification History button clicked")
                performHapticFeedback(context)
                onViewNotificationHistoryClick()
            },
            text = stringResource(R.string.view_notification_history)
        )
        Spacer(modifier = Modifier.height(8.dp))
        DashboardButton(
            onClick = {
                Log.d(TAG, "Manage ESP32 button clicked")
                performHapticFeedback(context)
                onManageESP32Click()
            },
            text = "Gestionar ESP32"
        )
    }
}

@Composable
private fun DashboardButton(onClick: () -> Unit, text: String) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

@Composable
private fun EmptyPanelsContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.no_panels_available),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun UserPanelItem(panel: Panel) {
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor = when {
        panel.isESP32Offline() -> PanelColors.PanelBackgroundOffline
        panel.hasIssues -> Color(0xFFFFEBEE)
        else -> Color(0xFFE8F5E9)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = panel.name, style = MaterialTheme.typography.titleMedium)
            Text(text = "Ubicación: ${panel.location}", style = MaterialTheme.typography.bodyMedium)

            if (panel.isESP32Offline()) {
                Text(
                    text = "Estado: ESP32 OFFLINE",
                    color = PanelColors.StatusDisc,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // CAMBIO: Usar activeRelays para contar solo relays activos en DISC
                val activeRelaysInDisc = panel.activeRelays.count { it.status == "DISC" }
                val hasActiveIssues = activeRelaysInDisc > 0

                Text(
                    text = "Estado: ${if (hasActiveIssues) "$activeRelaysInDisc relay(s) en DISC" else "OK"}",
                    color = if (hasActiveIssues) Color.Red else Color.Green,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (panel.isESP32Offline()) {
                    Text(
                        "No hay datos disponibles - ESP32 OFFLINE",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = PanelColors.StatusDisc
                    )
                } else {
                    Text("Detalles de relays activos:", style = MaterialTheme.typography.bodyMedium)
                    // CAMBIO: Mostrar solo relays activos
                    panel.activeRelays.forEach { relay ->
                        UserRelayStatus(relay)
                    }

                    if (panel.activeRelays.isEmpty()) {
                        Text(
                            "No hay relays habilitados para monitoreo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UserPanelItemForRelay(
    panel: Panel,
    relay: Relay,
    isAlarmRelay: Boolean
) {
    Log.d(TAG, "Rendering UserPanelItemForRelay: ${panel.name}, Relay: ${relay.name}, isAlarmRelay: $isAlarmRelay")
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor = if (isAlarmRelay) {
        Color(0xFFFFEBEE)
    } else {
        Color(0xFFFFEE58)
    }

    val textColor = if (!isAlarmRelay) {
        Color(0xFF0D47A1)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = if (!isAlarmRelay) textColor else Color.Unspecified
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = panel.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (!isAlarmRelay) textColor else Color.Unspecified
            )
            Text(
                text = "Ubicación: ${panel.location}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (!isAlarmRelay) textColor else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Estado: ${relay.name} en DISC",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                ),
                color = if (isAlarmRelay) PanelColors.StatusDisc else textColor
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Relay ${relay.name} activado",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isAlarmRelay) PanelColors.StatusDisc else textColor
                )

                if (relay.date_time != null) {
                    Text(
                        text = "Fecha: ${relay.date_time}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!isAlarmRelay) textColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun UserRelayStatus(relay: Relay) {
    Log.d(TAG, "Rendering UserRelayStatus: ${relay.displayName}, Status: ${relay.status}, Type: ${relay.contactType}")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = relay.displayName,
                style = MaterialTheme.typography.bodySmall
            )
            if (relay.contactType != "NO") {
                Text(
                    text = "(${relay.contactType})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = relay.status,
            color = when (relay.status) {
                "OK" -> Color.Green
                "DISC" -> Color.Red
                else -> Color.Yellow
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}