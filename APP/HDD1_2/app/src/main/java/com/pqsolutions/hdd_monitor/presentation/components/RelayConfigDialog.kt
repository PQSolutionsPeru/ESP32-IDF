package com.pqsolutions.hdd_monitor.presentation.components

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.Relay
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback

private const val TAG = "RelayConfigDialog"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayConfigDialog(
    panel: Panel,
    relay: Relay,
    onDismiss: () -> Unit,
    onConfirm: (Relay) -> Unit,
    modifier: Modifier = Modifier
) {
    var customName by remember { mutableStateOf(relay.name) }
    var isActive by remember { mutableStateOf(true) } // Por defecto activo
    var isControllable by remember { mutableStateOf(true) } // Por defecto controlable
    var selectedContactType by remember { mutableStateOf("NO") } // Normalmente Abierto por defecto
    var showAdvancedOptions by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Validaciones
    val isNameValid = customName.isNotBlank() && customName.length <= 50
    val isFormValid = isNameValid

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        ),
        modifier = modifier.fillMaxWidth(0.95f)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(scrollState)
            ) {
                // Título
                Text(
                    text = "Configurar Relay",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

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
                            text = "Estado actual: ${relay.status}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (relay.status) {
                                "OK" -> MaterialTheme.colorScheme.primary
                                "DISC" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Nombre personalizado
                OutlinedTextField(
                    value = customName,
                    onValueChange = {
                        if (it.length <= 50) {
                            customName = it
                        }
                    },
                    label = { Text("Nombre del Relay") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = !isNameValid,
                    supportingText = {
                        if (!isNameValid) {
                            Text(
                                text = "El nombre debe tener entre 1 y 50 caracteres",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text("${customName.length}/50 caracteres")
                        }
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Estado activo/inactivo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Relay Activo",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Si está desactivado, no se mostrará en el dashboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isActive,
                        onCheckedChange = {
                            performHapticFeedback(context)
                            isActive = it
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Control remoto habilitado
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Control Remoto",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Permite controlar el relay desde la aplicación",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isControllable,
                        onCheckedChange = {
                            performHapticFeedback(context)
                            isControllable = it
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Opciones avanzadas (expandibles)
                TextButton(
                    onClick = {
                        performHapticFeedback(context)
                        showAdvancedOptions = !showAdvancedOptions
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (showAdvancedOptions) "Ocultar Opciones Avanzadas" else "Mostrar Opciones Avanzadas",
                        textAlign = TextAlign.Center
                    )
                }

                // Opciones avanzadas
                if (showAdvancedOptions) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Tipo de Contacto",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Radio buttons para tipo de contacto
                            Column(
                                modifier = Modifier.selectableGroup()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = selectedContactType == "NO",
                                            onClick = {
                                                performHapticFeedback(context)
                                                selectedContactType = "NO"
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedContactType == "NO",
                                        onClick = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Normalmente Abierto (NO)",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "OK = Circuito cerrado, DISC = Circuito abierto",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = selectedContactType == "NC",
                                            onClick = {
                                                performHapticFeedback(context)
                                                selectedContactType = "NC"
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedContactType == "NC",
                                        onClick = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Normalmente Cerrado (NC)",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "OK = Circuito abierto, DISC = Circuito cerrado",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Advertencia si el relay no es controlable
                if (!isControllable) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Este relay solo se podrá monitorear, no controlar remotamente",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Información adicional
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Información",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "• Los cambios se aplicarán inmediatamente al ESP32\n" +
                                        "• El estado actual del relay no se modificará\n" +
                                        "• Los relays inactivos no aparecen en notificaciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Botones de acción
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            performHapticFeedback(context)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancelar")
                    }

                    Button(
                        onClick = {
                            performHapticFeedback(context)
                            Log.d(TAG, "Configurando relay: name=$customName, active=$isActive, controllable=$isControllable, contactType=$selectedContactType")

                            // Crear el relay actualizado con la nueva configuración
                            val updatedRelay = relay.copy(
                                name = customName.trim()
                                // Nota: Los campos isActive, isControllable y contactType se agregarán
                                // al modelo Relay en la modificación correspondiente
                            )

                            onConfirm(updatedRelay)
                        },
                        enabled = isFormValid,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Guardar")
                    }
                }
            }
        }
    }
}