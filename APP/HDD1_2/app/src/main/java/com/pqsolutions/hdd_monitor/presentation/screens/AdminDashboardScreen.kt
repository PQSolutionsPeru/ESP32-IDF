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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.Relay
import com.pqsolutions.hdd_monitor.presentation.components.AnimatedNotificationBell
import com.pqsolutions.hdd_monitor.presentation.components.ScreenTopBar
import com.pqsolutions.hdd_monitor.presentation.theme.HDD1_2Theme
import com.pqsolutions.hdd_monitor.presentation.theme.PanelColors
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.viewmodel.DashboardViewModel
import com.pqsolutions.hdd_monitor.presentation.viewmodel.NotificationViewModel

private const val TAG = "AdminDashboardScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    notificationViewModel: NotificationViewModel = hiltViewModel(),
    onLogoutClick: () -> Unit,
    onManageUsersClick: () -> Unit,
    onViewEventsClick: () -> Unit,
    onViewNotificationHistoryClick: () -> Unit,
    onManageESP32Click: () -> Unit,
    hasPendingNotifications: Boolean,
    selectedPanelId: String? = null
) {
    Log.d(TAG, "AdminDashboardScreen composition started")

    val uiState by viewModel.uiState.collectAsState()
    Log.d(TAG, "Current UI State: $uiState")
    val notificationUiState by notificationViewModel.uiState.collectAsState()
    val context = LocalContext.current

    val lifecycleKey = remember { "dashboard_lifecycle" }

    LaunchedEffect(lifecycleKey) {
        Log.d(TAG, "LaunchedEffect: Loading panels for admin dashboard")
        viewModel.loadPanels()

        // Reiniciar la recolección de notificaciones
        Log.d(TAG, "AdminDashboardScreen: Reiniciando recolección de notificaciones")
        notificationViewModel.restartNotificationCollection()
    }

    // DisposableEffect vinculado a la misma key que LaunchedEffect
    DisposableEffect(lifecycleKey) {
        // Al entrar en la pantalla: iniciar actualización periódica
        viewModel.startPeriodicRefresh()

        onDispose {
            Log.d(TAG, "DisposableEffect: Cleaning up admin dashboard")
            viewModel.stopPeriodicRefresh()
        }
    }

    HDD1_2Theme {
        Scaffold(
            topBar = {
                ScreenTopBar(
                    title = stringResource(R.string.admin_dashboard_title),
                    actions = {
                        AnimatedNotificationBell(
                            hasNewNotifications = hasPendingNotifications,
                            notificationCount = notificationUiState.pendingCount,
                            onClick = onViewNotificationHistoryClick,
                            modifier = Modifier.size(48.dp)
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
                    onManageUsersClick = onManageUsersClick,
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
                    // Mostrar mensaje cuando no hay paneles o están cargando
                    if (uiState.isLoading) {
                        item {
                            Text(
                                "Cargando paneles...",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    } else if (uiState.groupedPanels.isEmpty()) {
                        item {
                            Text(
                                "No hay paneles disponibles",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }

                    uiState.groupedPanels.forEach { (clientName, clientPanels) ->
                        item {
                            Text(
                                text = "Cliente: $clientName",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        // Para cada panel, procesar según el estado de sus relays
                        clientPanels.forEach { panel ->
                            // Si el ESP32 está OFFLINE o todos los relays están OK, mostrar un solo panel
                            if (panel.isESP32Offline() || panel.relays.none { it.status == Panel.STATUS_DISC }) {
                                item(key = "${panel.documentName}_single") {
                                    AdminPanelItem(panel)
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            } else {
                                // Para los paneles con relays en DISC, mostrar un PanelItem por cada relay en DISC
                                // dentro de un único item para mantenerlos agrupados
                                val relaysInDisc = panel.relays.filter { it.status == Panel.STATUS_DISC }

                                item(key = "${panel.documentName}_disc_group") {
                                    Column {
                                        relaysInDisc.forEach { relay ->
                                            val isAlarmRelay = relay.name == Panel.RELAY_ALARM

                                            AdminPanelItemForRelay(
                                                panel = panel,
                                                relay = relay,
                                                isAlarmRelay = isAlarmRelay
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Mostrar error si existe
                    if (uiState.error != null) {
                        item {
                            Text(
                                "Error: ${uiState.error}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Red,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    Log.d(TAG, "AdminDashboardScreen composition finished")
}

@Composable
private fun DashboardActions(
    onManageUsersClick: () -> Unit,
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
                Log.d(TAG, "Manage Users button clicked")
                performHapticFeedback(context)
                onManageUsersClick()
            },
            text = stringResource(R.string.manage_clients)
        )
        Spacer(modifier = Modifier.height(8.dp))
        DashboardButton(
            onClick = {
                Log.d(TAG, "View Events button clicked")
                performHapticFeedback(context)
                onViewEventsClick()
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
fun AdminPanelItem(panel: Panel) {
    Log.d(TAG, "Rendering AdminPanelItem: ${panel.name}, Status: ${panel.hasIssues}")
    var expanded by remember { mutableStateOf(false) }

    // Determinar color basado en estado del ESP32 primero, luego en relays
    val backgroundColor = when {
        panel.isESP32Offline() -> PanelColors.PanelBackgroundOffline
        panel.hasIssues -> Color(0xFFFFEBEE) // Rojo claro
        else -> Color(0xFFE8F5E9) // Verde claro
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
            Text(text = "ID ESP32: ${panel.esp32_id}", style = MaterialTheme.typography.bodyMedium)

            // Estado especial para ESP32 OFFLINE
            if (panel.isESP32Offline()) {
                Text(
                    text = "Estado: ESP32 OFFLINE",
                    color = PanelColors.StatusDisc,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "Estado: ${if (panel.hasIssues) panel.relaysInDisc else "OK"}",
                    color = if (panel.hasIssues) Color.Red else Color.Green,
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
                    Text("Detalles de relays:", style = MaterialTheme.typography.bodyMedium)
                    panel.relays.forEach { relay ->
                        AdminRelayStatus(relay)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminPanelItemForRelay(
    panel: Panel,
    relay: Relay,
    isAlarmRelay: Boolean
) {
    Log.d(TAG, "Rendering AdminPanelItemForRelay: ${panel.name}, Relay: ${relay.name}, isAlarmRelay: $isAlarmRelay")
    var expanded by remember { mutableStateOf(false) }

    // Determinar color basado en el tipo de relay
    val backgroundColor = if (isAlarmRelay) {
        Color(0xFFFFEBEE) // Rojo claro para relay Alarma
    } else {
        Color(0xFFFFEE58) // Amarillo más intenso para otros relays
    }

    // Color del texto para paneles amarillos
    val textColor = if (!isAlarmRelay) {
        Color(0xFF0D47A1) // Azul oscuro para texto en paneles amarillos
    } else {
        MaterialTheme.colorScheme.onSurface // Color normal para otros casos
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
                text = "ID ESP32: ${panel.esp32_id}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (!isAlarmRelay) textColor else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Mostrar mensaje específico del relay en DISC
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

                // Mostrar información adicional si está disponible
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
fun AdminRelayStatus(relay: Relay) {
    Log.d(TAG, "Rendering AdminRelayStatus: ${relay.name}, Status: ${relay.status}")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = relay.name, style = MaterialTheme.typography.bodySmall)
        Text(
            text = relay.status,
            color = when (relay.status) {
                "OK" -> Color.Green
                "DISC" -> Color.Red
                else -> Color.Yellow
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}