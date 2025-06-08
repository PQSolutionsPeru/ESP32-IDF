package com.pqsolutions.hdd_monitor.presentation.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import com.pqsolutions.hdd_monitor.presentation.components.AnimatedNotificationBell
import com.pqsolutions.hdd_monitor.presentation.components.AppTopBar
import com.pqsolutions.hdd_monitor.presentation.components.CustomTextField
import com.pqsolutions.hdd_monitor.presentation.state.PanelConfigurationState
import com.pqsolutions.hdd_monitor.presentation.theme.HDD1_2Theme
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.viewmodel.PanelConfigurationViewModel

private const val TAG = "PanelConfigurationScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelConfigurationScreen(
    panelId: String? = null,
    viewModel: PanelConfigurationViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onSaveSuccess: () -> Unit,
    hasPendingNotifications: Boolean,
    onNotificationClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var panelName by remember { mutableStateOf("") }
    var panelLocation by remember { mutableStateOf("") }
    var selectedESP32 by remember { mutableStateOf<ESP32Device?>(null) }
    var showESP32Dropdown by remember { mutableStateOf(false) }
    var selectedClient by remember { mutableStateOf<Client?>(null) }
    var showClientDropdown by remember { mutableStateOf(false) }

    val isEditMode = panelId != null
    val title = if (isEditMode) "Editar Panel" else "Crear Panel"

    LaunchedEffect(panelId) {
        Log.d(TAG, "PanelConfigurationScreen iniciado - Modo: ${if (isEditMode) "Editar" else "Crear"}")
        viewModel.initializeScreen(panelId)
    }

    LaunchedEffect(uiState.currentPanel) {
        uiState.currentPanel?.let { panel ->
            panelName = panel.name
            panelLocation = panel.location
            selectedESP32 = uiState.availableESP32s.find { it.documentName == panel.esp32_id }
        }
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            Log.d(TAG, "Panel guardado exitosamente")
            onSaveSuccess()
        }
    }

    HDD1_2Theme {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = title,
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
            bottomBar = {
                PanelConfigurationBottomBar(
                    canSave = viewModel.canSave(panelName, panelLocation, selectedESP32, selectedClient),
                    isSaving = uiState.isSaving,
                    onSave = {
                        performHapticFeedback(context)
                        viewModel.savePanel(
                            panelId = panelId,
                            name = panelName.trim(),
                            location = panelLocation.trim(),
                            esp32Device = selectedESP32,
                            selectedClient = selectedClient
                        )
                    },
                    onCancel = {
                        performHapticFeedback(context)
                        onBackClick()
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                when {
                    uiState.isLoading -> {
                        LoadingSection()
                    }
                    uiState.error != null -> {
                        ErrorSection(
                            error = uiState.error!!,
                            onRetry = { viewModel.initializeScreen(panelId) },
                            onDismiss = { viewModel.clearError() }
                        )
                    }
                    else -> {
                        if (uiState.availableESP32s.isEmpty() && !isEditMode) {
                            NoESP32AvailableSection(
                                onBackClick = onBackClick
                            )
                        } else {
                            PanelConfigurationForm(
                                panelName = panelName,
                                onPanelNameChange = { panelName = it },
                                panelLocation = panelLocation,
                                onPanelLocationChange = { panelLocation = it },
                                selectedESP32 = selectedESP32,
                                availableESP32s = uiState.availableESP32s,
                                showESP32Dropdown = showESP32Dropdown,
                                onShowESP32DropdownChange = { showESP32Dropdown = it },
                                onESP32Select = { esp32 ->
                                    selectedESP32 = esp32
                                    showESP32Dropdown = false
                                },
                                isEditMode = isEditMode,
                                esp32StatusMap = uiState.esp32StatusMap,
                                isAdmin = uiState.isAdmin,
                                selectedClient = selectedClient,
                                availableClients = uiState.availableClients,
                                showClientDropdown = showClientDropdown,
                                onShowClientDropdownChange = { showClientDropdown = it },
                                onClientSelect = { client ->
                                    selectedClient = client
                                    showClientDropdown = false
                                },
                                clientDisplayName = uiState.clientDisplayName
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoESP32AvailableSection(
    onBackClick: () -> Unit
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Sin ESP32 Disponibles",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "No existen ESP32s disponibles para asignar a nuevos paneles. Por favor, registre ESP32s primero o libere algunos de paneles existentes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Volver")
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
                text = "Cargando configuración...",
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
private fun PanelConfigurationForm(
    panelName: String,
    onPanelNameChange: (String) -> Unit,
    panelLocation: String,
    onPanelLocationChange: (String) -> Unit,
    selectedESP32: ESP32Device?,
    availableESP32s: List<ESP32Device>,
    showESP32Dropdown: Boolean,
    onShowESP32DropdownChange: (Boolean) -> Unit,
    onESP32Select: (ESP32Device) -> Unit,
    isEditMode: Boolean,
    esp32StatusMap: Map<String, String>,
    isAdmin: Boolean,
    selectedClient: Client?,
    availableClients: List<Client>,
    showClientDropdown: Boolean,
    onShowClientDropdownChange: (Boolean) -> Unit,
    onClientSelect: (Client) -> Unit,
    clientDisplayName: String = ""
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isEditMode && clientDisplayName.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cliente:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = clientDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Text(
            text = "Información del Panel",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        CustomTextField(
            value = panelName,
            onValueChange = onPanelNameChange,
            label = "Nombre del Panel",
            isError = panelName.isBlank(),
            supportingText = if (panelName.isBlank()) "El nombre es requerido" else null
        )

        CustomTextField(
            value = panelLocation,
            onValueChange = onPanelLocationChange,
            label = "Ubicación",
            isError = panelLocation.isBlank(),
            supportingText = if (panelLocation.isBlank()) "La ubicación es requerida" else null
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Asignación de ESP32",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        ESP32DropdownSelector(
            selectedESP32 = selectedESP32,
            availableESP32s = availableESP32s,
            showDropdown = showESP32Dropdown,
            onShowDropdownChange = onShowESP32DropdownChange,
            onESP32Select = onESP32Select,
            isEditMode = isEditMode,
            esp32StatusMap = esp32StatusMap
        )

        selectedESP32?.let { esp32 ->
            ESP32InfoCard(
                esp32 = esp32,
                status = esp32StatusMap[esp32.documentName] ?: ESP32Device.STATUS_OFFLINE
            )
        }

        if (isAdmin && !isEditMode) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Asignación de Cliente",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            ClientDropdownSelector(
                selectedClient = selectedClient,
                availableClients = availableClients,
                showDropdown = showClientDropdown,
                onShowDropdownChange = onShowClientDropdownChange,
                onClientSelect = onClientSelect
            )
        }

        if (isEditMode) {
            Spacer(modifier = Modifier.height(16.dp))
            EditModeInfo()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientDropdownSelector(
    selectedClient: Client?,
    availableClients: List<Client>,
    showDropdown: Boolean,
    onShowDropdownChange: (Boolean) -> Unit,
    onClientSelect: (Client) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = showDropdown,
        onExpandedChange = onShowDropdownChange
    ) {
        OutlinedTextField(
            value = selectedClient?.name ?: "",
            onValueChange = { },
            readOnly = true,
            label = { Text("Cliente") },
            placeholder = { Text("Seleccionar Cliente") },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = "Expandir"
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            isError = selectedClient == null,
            supportingText = {
                if (selectedClient == null) {
                    Text(
                        text = "Debe seleccionar un cliente",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )

        ExposedDropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { onShowDropdownChange(false) }
        ) {
            if (availableClients.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "No hay clientes disponibles",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = { }
                )
            } else {
                availableClients.forEach { client ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = client.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "ID: ${client.documentName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { onClientSelect(client) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ESP32DropdownSelector(
    selectedESP32: ESP32Device?,
    availableESP32s: List<ESP32Device>,
    showDropdown: Boolean,
    onShowDropdownChange: (Boolean) -> Unit,
    onESP32Select: (ESP32Device) -> Unit,
    isEditMode: Boolean,
    esp32StatusMap: Map<String, String>
) {
    ExposedDropdownMenuBox(
        expanded = showDropdown,
        onExpandedChange = onShowDropdownChange
    ) {
        OutlinedTextField(
            value = selectedESP32?.let { "ESP32 #${it.documentName}" } ?: "",
            onValueChange = { },
            readOnly = true,
            label = { Text("ESP32 Asignado") },
            placeholder = { Text("Seleccionar ESP32") },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = "Expandir"
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            isError = selectedESP32 == null && !isEditMode,
            supportingText = {
                if (selectedESP32 == null && !isEditMode) {
                    Text(
                        text = "Debe seleccionar un ESP32",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )

        ExposedDropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { onShowDropdownChange(false) }
        ) {
            if (availableESP32s.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "No hay ESP32s disponibles",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = { }
                )
            } else {
                availableESP32s.forEach { esp32 ->
                    val status = esp32StatusMap[esp32.documentName] ?: ESP32Device.STATUS_OFFLINE
                    val isOnline = status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "ESP32 #${esp32.documentName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "MAC: ${esp32.MAC}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    StatusIndicator(isOnline = isOnline)
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isOnline) Color.Green else Color.Red
                                    )
                                }
                            }
                        },
                        onClick = { onESP32Select(esp32) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ESP32InfoCard(
    esp32: ESP32Device,
    status: String
) {
    val isOnline = status == ESP32Device.STATUS_ONLINE || status == ESP32Device.STATUS_RUNNING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOnline)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ESP32 Seleccionado",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StatusIndicator(isOnline = isOnline)
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOnline) Color.Green else Color.Red,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Divider()

            InfoRow(label = "ID", value = esp32.documentName)
            InfoRow(label = "MAC", value = esp32.MAC)
            if (esp32.IP.isNotEmpty()) {
                InfoRow(label = "IP", value = esp32.IP)
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatusIndicator(
    isOnline: Boolean,
    size: Int = 8
) {
    Box(
        modifier = Modifier.size(size.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(1.dp)
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                drawCircle(
                    color = if (isOnline) Color.Green else Color.Red
                )
            }
        }
    }
}

@Composable
private fun EditModeInfo() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Modo Edición",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "• Cambiar el ESP32 liberará el ESP32 actual para otros paneles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "• Los relays mantendrán su configuración personalizada",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "• Los cambios se aplicarán inmediatamente",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PanelConfigurationBottomBar(
    canSave: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !isSaving
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancelar")
            }

            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                enabled = canSave && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSaving) "Guardando..." else "Guardar")
            }
        }
    }
}

private fun canSavePanel(
    panelName: String,
    panelLocation: String,
    selectedESP32: ESP32Device?,
    isEditMode: Boolean
): Boolean {
    return panelName.isNotBlank() &&
            panelLocation.isNotBlank() &&
            (selectedESP32 != null || isEditMode)
}