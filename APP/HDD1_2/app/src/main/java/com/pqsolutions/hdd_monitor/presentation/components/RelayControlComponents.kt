package com.pqsolutions.hdd_monitor.presentation.components

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.Relay
import com.pqsolutions.hdd_monitor.presentation.theme.PanelColors
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import kotlinx.coroutines.delay

private const val TAG = "RelayControlComponents"

@Composable
fun RelayConfigDialog(
    panel: Panel,
    relay: Relay,
    onDismiss: () -> Unit,
    onConfirm: (Relay) -> Unit
) {
    var customName by remember { mutableStateOf(relay.customName ?: relay.name) }
    var isActive by remember { mutableStateOf(relay.isActive) }
    var contactType by remember { mutableStateOf(relay.contactType) }
    var showContactTypeDropdown by remember { mutableStateOf(false) }

    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Configurar Relay",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Información del panel
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Panel: ${panel.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Ubicación: ${panel.location}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Relay Original: ${relay.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Nombre personalizado
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("Nombre Personalizado") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text("Nombre que se mostrará en la interfaz")
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null
                        )
                    }
                )

                // Switch para activar/desactivar
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = if (isActive) Color.Green else Color.Red,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Relay Activo",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = if (isActive) "El relay está habilitado" else "El relay está deshabilitado",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isActive,
                            onCheckedChange = { isActive = it }
                        )
                    }
                }

                // Selector de tipo de contacto
                ExposedDropdownMenuBox(
                    expanded = showContactTypeDropdown,
                    onExpandedChange = { showContactTypeDropdown = it }
                ) {
                    OutlinedTextField(
                        value = when (contactType) {
                            "NO" -> "Normalmente Abierto (NO)"
                            "NC" -> "Normalmente Cerrado (NC)"
                            else -> contactType
                        },
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Tipo de Contacto") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = showContactTypeDropdown
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ElectricalServices,
                                contentDescription = null
                            )
                        }
                    )

                    ExposedDropdownMenu(
                        expanded = showContactTypeDropdown,
                        onDismissRequest = { showContactTypeDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "Normalmente Abierto (NO)",
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Contacto abierto en reposo",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                contactType = "NO"
                                showContactTypeDropdown = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "Normalmente Cerrado (NC)",
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Contacto cerrado en reposo",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                contactType = "NC"
                                showContactTypeDropdown = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.RadioButtonChecked,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }

                // Estado actual
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (relay.status) {
                            "OK" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            "DISC" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Estado Actual",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            if (relay.date_time != null) {
                                Text(
                                    text = "Última actualización: ${relay.date_time}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        RelayStatusBadge(status = relay.status)
                    }
                }

                // Información adicional si el relay no está activo
                AnimatedVisibility(
                    visible = !isActive,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "El relay deshabilitado no aparecerá en el control remoto",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    performHapticFeedback(context)
                    val updatedRelay = relay.copy(
                        customName = customName.trim().takeIf { it.isNotEmpty() && it != relay.name },
                        isActive = isActive,
                        contactType = contactType
                    )
                    onConfirm(updatedRelay)
                },
                enabled = customName.trim().isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Guardar Cambios")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun AnimatedRelayCard(
    panel: Panel,
    relay: Relay,
    onRelayToggle: (Panel, Relay) -> Unit,
    onRelayConfig: (Panel, Relay) -> Unit,
    isOperationInProgress: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )

    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(enabled = !panel.isESP32Offline() && !isOperationInProgress) {
                performHapticFeedback(context)
                isPressed = true
                onRelayToggle(panel, relay)
            },
        colors = CardDefaults.cardColors(
            containerColor = when {
                panel.isESP32Offline() -> MaterialTheme.colorScheme.surfaceVariant
                relay.status == "OK" -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            }
        ),
        border = if (isOperationInProgress) BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Información del relay
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relay.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RelayStatusBadge(status = relay.status)

                    if (panel.isESP32Offline()) {
                        Text(
                            text = "• ESP32 Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                relay.date_time?.let { dateTime ->
                    Text(
                        text = "Actualizado: $dateTime",
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
                // Indicador de operación en progreso
                AnimatedVisibility(
                    visible = isOperationInProgress,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Botón de configuración
                IconButton(
                    onClick = {
                        performHapticFeedback(context)
                        onRelayConfig(panel, relay)
                    },
                    enabled = !isOperationInProgress
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configurar ${relay.name}",
                        tint = if (panel.isESP32Offline())
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }

                // Switch animado
                AnimatedRelaySwitch(
                    checked = relay.status == "OK",
                    onCheckedChange = {
                        performHapticFeedback(context)
                        onRelayToggle(panel, relay)
                    },
                    enabled = !panel.isESP32Offline() && !isOperationInProgress
                )
            }
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(150)
            isPressed = false
        }
    }
}

@Composable
fun AnimatedRelaySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = EaseInOutCubic)
    )

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.scale(1.1f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
fun RelayStatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, contentColor, icon) = when (status.uppercase()) {
        "OK" -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.CheckCircle
        )
        "DISC" -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Cancel
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Help
        )
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun PanelConnectionStatus(
    panel: Panel,
    modifier: Modifier = Modifier
) {
    val isOnline = !panel.isESP32Offline()
    val animatedColor by animateColorAsState(
        targetValue = if (isOnline)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.error,
        animationSpec = tween(1000)
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Indicador de conexión animado
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(animatedColor)
        ) {
            if (isOnline) {
                // Efecto de pulso para conexión online
                val infiniteTransition = rememberInfiniteTransition()
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000),
                        repeatMode = RepeatMode.Reverse
                    )
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(animatedColor.copy(alpha = alpha))
                )
            }
        }

        Text(
            text = if (isOnline) "Conectado" else "Desconectado",
            style = MaterialTheme.typography.bodySmall,
            color = animatedColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RelayCommandProgress(
    isVisible: Boolean,
    commandType: String = "Enviando comando",
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
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
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Column {
                    Text(
                        text = commandType,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Por favor espere...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun RelayGroupCard(
    title: String,
    panels: List<Panel>,
    onRelayToggle: (Panel, Relay) -> Unit,
    onRelayConfig: (Panel, Relay) -> Unit,
    operationsInProgress: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header del grupo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        performHapticFeedback(context)
                        expanded = !expanded
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${panels.size} panel${if (panels.size != 1) "es" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Contraer" else "Expandir"
                )
            }

            // Contenido expandible
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    panels.forEach { panel ->
                        PanelRelaysGroup(
                            panel = panel,
                            onRelayToggle = onRelayToggle,
                            onRelayConfig = onRelayConfig,
                            operationsInProgress = operationsInProgress
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelRelaysGroup(
    panel: Panel,
    onRelayToggle: (Panel, Relay) -> Unit,
    onRelayConfig: (Panel, Relay) -> Unit,
    operationsInProgress: Set<String>
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (panel.isESP32Offline())
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header del panel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = panel.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = panel.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PanelConnectionStatus(panel = panel)
            }

            // Relays del panel
            panel.relays.forEach { relay ->
                val operationKey = "${panel.documentName}_${relay.name}"
                AnimatedRelayCard(
                    panel = panel,
                    relay = relay,
                    onRelayToggle = onRelayToggle,
                    onRelayConfig = onRelayConfig,
                    isOperationInProgress = operationsInProgress.contains(operationKey)
                )
            }
        }
    }
}

@Composable
fun EmptyRelayState(
    message: String = "No hay relays disponibles",
    icon: ImageVector = Icons.Default.PowerOff,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}