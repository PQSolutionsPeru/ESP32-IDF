package com.pqsolutions.hdd_monitor.presentation.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.Relay
import com.pqsolutions.hdd_monitor.presentation.components.AnimatedNotificationBell
import com.pqsolutions.hdd_monitor.presentation.components.AppTopBar
import com.pqsolutions.hdd_monitor.presentation.state.RelayControlState
import com.pqsolutions.hdd_monitor.presentation.theme.HDD1_2Theme
import com.pqsolutions.hdd_monitor.presentation.theme.PanelColors
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.viewmodel.RelayControlViewModel
import com.pqsolutions.hdd_monitor.presentation.components.RelayConfigDialog

private const val TAG = "RelayControlScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayControlScreen(
    viewModel: RelayControlViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    hasPendingNotifications: Boolean,
    onNotificationClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Estados para diálogos
    var showRelayConfigDialog by remember { mutableStateOf(false) }
    var selectedPanel by remember { mutableStateOf<Panel?>(null) }
    var selectedRelay by remember { mutableStateOf<Relay?>(null) }

    // CAMBIO: Usar DisposableEffect en lugar de LaunchedEffect
    DisposableEffect(Unit) {
        Log.d(TAG, "RelayControlScreen iniciado - Cargando paneles")
        viewModel.loadPanels()

        onDispose {
            Log.d(TAG, "RelayControlScreen disposed")
            viewModel.stopPeriodicRefresh()
        }
    }

    // Limpiar error cuando el usuario toca algo
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            // Auto-limpiar error después de 5 segundos
            kotlinx.coroutines.delay(5000)
            viewModel.clearError()
        }
    }

    HDD1_2Theme {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.relay_control_title),
                    onBackClick = onBackClick,
                    actions = {
                        // Botón de refresh
                        IconButton(
                            onClick = {
                                performHapticFeedback(context)
                                viewModel.refreshPanels()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Actualizar"
                            )
                        }

                        // Campana de notificaciones
                        AnimatedNotificationBell(
                            hasNewNotifications = hasPendingNotifications,
                            onClick = {
                                performHapticFeedback(context)
                                onNotificationClick()
                            }
                        )
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                when {
                    uiState.isLoading -> {
                        LoadingSection()
                    }
                    uiState.error != null -> {
                        ErrorSection(
                            error = uiState.error!!,
                            onRetry = { viewModel.loadPanels() },
                            onDismiss = { viewModel.clearError() }
                        )
                    }
                    uiState.groupedPanels.isEmpty() -> {
                        EmptyStateSection()
                    }
                    else -> {
                        PanelsWithRelaysSection(
                            groupedPanels = uiState.groupedPanels,
                            onRelayToggle = { panel, relay ->
                                Log.d(TAG, "Toggle relay ${relay.name} del panel ${panel.name}")
                                viewModel.toggleRelay(panel, relay)
                            },
                            onRelayConfig = { panel, relay ->
                                Log.d(TAG, "Configurar relay ${relay.name} del panel ${panel.name}")
                                selectedPanel = panel
                                selectedRelay = relay
                                showRelayConfigDialog = true
                            }
                        )
                    }
                }

                // Mostrar estado de operación si hay alguna en progreso
                AnimatedVisibility(visible = uiState.operationInProgress) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Enviando comando...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    // Diálogo de configuración de relay
    if (showRelayConfigDialog && selectedPanel != null && selectedRelay != null) {
        RelayConfigDialog(
            panel = selectedPanel!!,
            relay = selectedRelay!!,
            onDismiss = {
                showRelayConfigDialog = false
                selectedPanel = null
                selectedRelay = null
            },
            onConfirm = { updatedRelay ->
                viewModel.updateRelayConfig(selectedPanel!!, updatedRelay)
                showRelayConfigDialog = false
                selectedPanel = null
                selectedRelay = null
            }
        )
    }
}

@Composable
private fun LoadingSection() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Cargando paneles...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun ErrorSection(
    error: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onRetry) {
                    Text("Reintentar")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            }
        }
    }
}

@Composable
private fun EmptyStateSection() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "No hay paneles disponibles",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Los paneles se mostrarán aquí cuando estén disponibles",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PanelsWithRelaysSection(
    groupedPanels: Map<String, List<Panel>>,
    onRelayToggle: (Panel, Relay) -> Unit,
    onRelayConfig: (Panel, Relay) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        groupedPanels.forEach { (clientName, panels) ->
            item(key = "client_header_$clientName") {
                Text(
                    text = "Cliente: $clientName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(
                items = panels,
                key = { panel -> "panel_${panel.documentName}" }
            ) { panel ->
                PanelRelayCard(
                    panel = panel,
                    onRelayToggle = onRelayToggle,
                    onRelayConfig = onRelayConfig
                )
            }
        }
    }
}

@Composable
private fun PanelRelayCard(
    panel: Panel,
    onRelayToggle: (Panel, Relay) -> Unit,
    onRelayConfig: (Panel, Relay) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Determinar color de fondo basado en estado del ESP32
    val backgroundColor = when {
        panel.isESP32Offline() -> PanelColors.PanelBackgroundOffline
        panel.hasIssues -> PanelColors.PanelBackgroundDisc
        else -> PanelColors.PanelBackgroundOk
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header del panel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = panel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ubicación: ${panel.location}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "ESP32: ${panel.esp32_id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Estado del ESP32
                StatusChip(
                    status = if (panel.isESP32Offline()) "OFFLINE" else "ONLINE",
                    isOnline = !panel.isESP32Offline()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Botón para expandir/contraer
            TextButton(
                onClick = {
                    performHapticFeedback(context)
                    expanded = !expanded
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (expanded) "Ocultar Relays" else "Mostrar Relays (${panel.relays.size})"
                )
            }

            // Lista de relays (expandible)
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (panel.isESP32Offline()) {
                        Text(
                            text = "ESP32 desconectado - Control no disponible",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp)
                        )
                    } else {
                        panel.relays.forEach { relay ->
                            RelayControlItem(
                                panel = panel,
                                relay = relay,
                                onToggle = onRelayToggle,
                                onConfig = onRelayConfig,
                                enabled = !panel.isESP32Offline()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayControlItem(
    panel: Panel,
    relay: Relay,
    onToggle: (Panel, Relay) -> Unit,
    onConfig: (Panel, Relay) -> Unit,
    enabled: Boolean
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Información del relay
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relay.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Estado: ${relay.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (relay.status) {
                        "OK" -> MaterialTheme.colorScheme.primary
                        "DISC" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                relay.date_time?.let { dateTime ->
                    Text(
                        text = "Última actualización: $dateTime",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Controles
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botón de configuración
                IconButton(
                    onClick = {
                        performHapticFeedback(context)
                        onConfig(panel, relay)
                    },
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configurar ${relay.name}",
                        tint = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Switch para toggle del relay
                Switch(
                    checked = relay.status == "OK",
                    onCheckedChange = { _ ->
                        performHapticFeedback(context)
                        onToggle(panel, relay)
                    },
                    enabled = enabled
                )
            }
        }
    }
}

@Composable
private fun StatusChip(
    status: String,
    isOnline: Boolean
) {
    Surface(
        color = if (isOnline)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = status,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isOnline)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onErrorContainer
        )
    }
}