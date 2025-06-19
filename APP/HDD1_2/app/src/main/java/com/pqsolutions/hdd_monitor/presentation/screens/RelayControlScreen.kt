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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Block
import com.pqsolutions.hdd_monitor.presentation.theme.HDD1_2Theme
import com.pqsolutions.hdd_monitor.presentation.theme.PanelColors
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.viewmodel.RelayControlViewModel
import com.pqsolutions.hdd_monitor.presentation.components.RelayConfigDialog

private const val TAG = "RelayControlScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayControlScreen(
    panelId: String? = null,
    viewModel: RelayControlViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    hasPendingNotifications: Boolean,
    onNotificationClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showRelayConfigDialog by remember { mutableStateOf(false) }
    var selectedPanel by remember { mutableStateOf<Panel?>(null) }
    var selectedRelay by remember { mutableStateOf<Relay?>(null) }

    LaunchedEffect(panelId) {
        Log.d(TAG, "RelayControlScreen iniciado - Panel específico: $panelId")
        if (panelId != null) {
            viewModel.loadSpecificPanel(panelId)
        } else {
            viewModel.loadPanels()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "RelayControlScreen disposed")
            try {
                viewModel.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            kotlinx.coroutines.delay(5000)
            viewModel.clearError()
        }
    }

    HDD1_2Theme {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = if (panelId != null && uiState.panels.isNotEmpty()) {
                        "Relays - ${uiState.panels.first().name}"
                    } else {
                        stringResource(R.string.relay_control_title)
                    },
                    onBackClick = {
                        showRelayConfigDialog = false
                        onBackClick()
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                performHapticFeedback(context)
                                if (panelId != null) {
                                    viewModel.refreshSpecificPanel(panelId)
                                } else {
                                    viewModel.refreshPanels()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Actualizar"
                            )
                        }

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
                            onRetry = {
                                if (panelId != null) {
                                    viewModel.loadSpecificPanel(panelId)
                                } else {
                                    viewModel.loadPanels()
                                }
                            },
                            onDismiss = { viewModel.clearError() }
                        )
                    }
                    uiState.groupedPanels.isEmpty() -> {
                        EmptyStateSection()
                    }
                    else -> {
                        PanelsWithRelaysSection(
                            groupedPanels = uiState.groupedPanels,
                            onRelayConfig = { panel, relay ->
                                Log.d(TAG, "Configurar relay ${relay.name} del panel ${panel.name}")
                                selectedPanel = panel
                                selectedRelay = relay
                                showRelayConfigDialog = true
                            }
                        )
                    }
                }

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
                                text = "Actualizando configuración...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

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
                    onRelayConfig = onRelayConfig
                )
            }
        }
    }
}

@Composable
private fun PanelRelayCard(
    panel: Panel,
    onRelayConfig: (Panel, Relay) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

                StatusChip(
                    status = if (panel.isESP32Offline()) "OFFLINE" else "ONLINE",
                    isOnline = !panel.isESP32Offline()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (panel.isESP32Offline()) {
                        Text(
                            text = "ESP32 desconectado - Monitoreo no disponible",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp)
                        )
                    } else {
                        panel.relays.forEach { relay ->
                            RelayControlItem(
                                panel = panel,
                                relay = relay,
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
    onConfig: (Panel, Relay) -> Unit,
    enabled: Boolean
) {
    val context = LocalContext.current

    @Composable
    fun getRelayDisplayColor(relay: Relay): Pair<Color, String> {
        if (!relay.isActive || !enabled) {
            return Pair(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), "INACTIVO")
        }

        return when (relay.contactType) {
            "NC" -> {
                when (relay.status) {
                    "OK" -> Pair(MaterialTheme.colorScheme.primary, "OK")
                    "DISC" -> Pair(MaterialTheme.colorScheme.error, "DISC")
                    else -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, relay.status)
                }
            }
            "NO" -> {
                when (relay.status) {
                    "OK" -> Pair(MaterialTheme.colorScheme.primary, "OK")
                    "DISC" -> Pair(MaterialTheme.colorScheme.error, "DISC")
                    else -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, relay.status)
                }
            }
            else -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, relay.status)
        }
    }

    val (statusColor, displayStatus) = getRelayDisplayColor(relay)

    val backgroundColor = when {
        !relay.isActive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        !enabled -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = if (!relay.isActive) BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = relay.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (relay.isActive)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!relay.customName.isNullOrBlank() && relay.customName != relay.name) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "PERSONALIZADO",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        if (relay.contactType != "NO") {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = relay.contactType,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = statusColor,
                                    shape = CircleShape
                                )
                        )

                        Text(
                            text = "Estado: $displayStatus",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = if (!relay.isActive) "Monitoreo deshabilitado" else "Monitoreo activo",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!relay.isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (!relay.customName.isNullOrBlank() && relay.customName != relay.name) {
                    Text(
                        text = "Original: ${relay.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                relay.date_time?.let { dateTime ->
                    Text(
                        text = "Actualizado: $dateTime",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        performHapticFeedback(context)
                        onConfig(panel, relay)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configurar ${relay.displayName}",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (!relay.isActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Deshabilitado",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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