package com.pqsolutions.hdd_monitor.presentation.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.presentation.components.AnimatedNotificationBell
import com.pqsolutions.hdd_monitor.presentation.components.AppTopBar
import com.pqsolutions.hdd_monitor.presentation.state.ESP32ManagementState
import com.pqsolutions.hdd_monitor.presentation.theme.PanelColors
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.viewmodel.ESP32ManagementViewModel

private const val TAG = "ESP32ManagementScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ESP32ManagementScreen(
    viewModel: ESP32ManagementViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onCreatePanel: () -> Unit,
    onEditPanel: (String) -> Unit,
    onCustomizeRelays: (String) -> Unit,
    hasPendingNotifications: Boolean,
    onNotificationClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        Log.d(TAG, "ESP32ManagementScreen iniciado")
        viewModel.initializeIfNeeded()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Gestión ESP32",
                onBackClick = onBackClick,
                actions = {
                    AnimatedNotificationBell(
                        hasNewNotifications = hasPendingNotifications,
                        onClick = {
                            performHapticFeedback(context)
                            onNotificationClick()
                        }
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    performHapticFeedback(context)
                    onCreatePanel()
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Crear Panel"
                )
            }
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
                        onRetry = { viewModel.refreshData() },
                        onDismiss = { viewModel.clearError() }
                    )
                }
                else -> {
                    ESP32Content(
                        uiState = uiState,
                        onEditPanel = onEditPanel,
                        onCustomizeRelays = onCustomizeRelays,
                        onCreatePanel = onCreatePanel,
                        onRefresh = { viewModel.refreshData() }
                    )
                }
            }
        }
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
                text = "Cargando ESP32s...",
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
private fun ESP32Content(
    uiState: ESP32ManagementState,
    onEditPanel: (String) -> Unit,
    onCustomizeRelays: (String) -> Unit,
    onCreatePanel: () -> Unit,
    onRefresh: () -> Unit
) {
    Column {
        SummarySection(uiState = uiState)

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.panels.isEmpty() && uiState.availableESP32s.isEmpty()) {
            EmptyStateSection(onCreatePanel = onCreatePanel)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiState.panels.isNotEmpty()) {
                    item {
                        Text(
                            text = "Paneles Configurados (${uiState.panels.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    items(
                        items = uiState.panels,
                        key = { panel -> panel.documentName }
                    ) { panel ->
                        PanelCard(
                            panel = panel,
                            esp32Status = uiState.esp32StatusMap[panel.esp32_id],
                            onEditPanel = onEditPanel,
                            onCustomizeRelays = onCustomizeRelays
                        )
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }

                if (uiState.availableESP32s.isNotEmpty()) {
                    item {
                        Text(
                            text = "ESP32s Disponibles (${uiState.availableESP32s.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    items(
                        items = uiState.availableESP32s,
                        key = { esp32 -> esp32.documentName }
                    ) { esp32 ->
                        AvailableESP32Card(
                            esp32 = esp32,
                            onCreatePanel = onCreatePanel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummarySection(uiState: ESP32ManagementState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryItem(
                title = "Paneles",
                value = uiState.panels.size.toString(),
                color = MaterialTheme.colorScheme.primary
            )
            SummaryItem(
                title = "ESP32s Online",
                value = uiState.onlineESP32Count.toString(),
                color = Color.Green
            )
            SummaryItem(
                title = "Disponibles",
                value = uiState.availableESP32s.size.toString(),
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun SummaryItem(
    title: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PanelCard(
    panel: Panel,
    esp32Status: String?,
    onEditPanel: (String) -> Unit,
    onCustomizeRelays: (String) -> Unit
) {
    val context = LocalContext.current
    val isOnline = esp32Status == ESP32Device.STATUS_ONLINE || esp32Status == ESP32Device.STATUS_RUNNING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                panel.isESP32Offline() -> PanelColors.PanelBackgroundOffline
                panel.hasIssues -> Color(0xFFFFEBEE)
                else -> Color(0xFFE8F5E9)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
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
                    status = if (isOnline) "ONLINE" else "OFFLINE",
                    isOnline = isOnline
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (panel.hasIssues) "Relays con problemas: ${panel.relaysInDisc}" else "Todos los relays OK",
                style = MaterialTheme.typography.bodySmall,
                color = if (panel.hasIssues) Color.Red else Color.Green
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        performHapticFeedback(context)
                        onEditPanel(panel.documentName)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Editar")
                }

                Button(
                    onClick = {
                        performHapticFeedback(context)
                        onCustomizeRelays(panel.documentName)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Relays")
                }
            }
        }
    }
}

@Composable
private fun AvailableESP32Card(
    esp32: ESP32Device,
    onCreatePanel: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ESP32 #${esp32.documentName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "MAC: ${esp32.MAC}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (esp32.IP.isNotEmpty()) {
                        Text(
                            text = "IP: ${esp32.IP}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                StatusChip(
                    status = esp32.status,
                    isOnline = esp32.status == ESP32Device.STATUS_ONLINE
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    performHapticFeedback(context)
                    onCreatePanel()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Crear Panel con este ESP32")
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

@Composable
private fun EmptyStateSection(
    onCreatePanel: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "No hay paneles configurados",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Crea tu primer panel para comenzar",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    performHapticFeedback(context)
                    onCreatePanel()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Crear Primer Panel")
            }
        }
    }
}