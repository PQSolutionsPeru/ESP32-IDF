package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.Relay
import com.pqsolutions.hdd_monitor.presentation.theme.HddGreen
import com.pqsolutions.hdd_monitor.presentation.theme.HddRed
import com.pqsolutions.hdd_monitor.presentation.theme.HddYellow
import com.pqsolutions.hdd_monitor.presentation.theme.PanelColors
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect

private const val TAG = "PanelListComponents"

@Composable
fun PanelList(
    panels: List<Panel>,
    onPanelSelect: (Panel) -> Unit,
    onEditPanel: (Panel) -> Unit,
    onDeletePanel: (Panel) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Para cada panel, generar sus elementos según el estado de los relays
        panels.forEach { panel ->
            // Si el ESP32 está OFFLINE o todos los relays están OK, mostrar un solo panel
            if (panel.isESP32Offline() || panel.relays.none { it.status == Panel.STATUS_DISC }) {
                item(key = "${panel.documentName}_single") {
                    PanelCard(
                        panel = panel,
                        onPanelSelect = { onPanelSelect(panel) },
                        onEditPanel = { onEditPanel(panel) },
                        onDeletePanel = { onDeletePanel(panel) }
                    )
                }
            } else {
                // Para los paneles con relays en DISC, mostrar un PanelItem por cada relay en DISC
                // dentro de un único item para mantenerlos agrupados
                val relaysInDisc = panel.relays.filter { it.status == Panel.STATUS_DISC }

                item(key = "${panel.documentName}_disc_group") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        relaysInDisc.forEach { relay ->
                            val isAlarmRelay = relay.name == Panel.RELAY_ALARM

                            PanelCardForRelay(
                                panel = panel,
                                relay = relay,
                                isAlarmRelay = isAlarmRelay,
                                onPanelSelect = { onPanelSelect(panel) },
                                onEditPanel = { onEditPanel(panel) },
                                onDeletePanel = { onDeletePanel(panel) }
                            )

                            if (relay != relaysInDisc.last()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelCard(
    panel: Panel,
    onPanelSelect: () -> Unit,
    onEditPanel: () -> Unit,
    onDeletePanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor = when {
        panel.isESP32Offline() -> PanelColors.PanelBackgroundOffline
        panel.hasIssues -> PanelColors.PanelBackgroundDisc
        else -> PanelColors.PanelBackgroundOk
    }

    Card(
        onClick = {
            performHapticFeedback(context)
            onPanelSelect()
        },
        modifier = modifier
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (panel.clientDisplayName.isNotEmpty()) {
                        Text(
                            text = panel.clientDisplayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.field_location, panel.location),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onEditPanel) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDeletePanel) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = { expanded = !expanded }
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Mostrar menos" else "Mostrar más"
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (panel.isESP32Offline()) PanelColors.StatusOffline
                    else if (panel.hasValidESP32()) PanelColors.StatusOk
                    else PanelColors.StatusDisc,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (panel.isESP32Offline())
                        "ESP32 OFFLINE"
                    else if (panel.hasValidESP32())
                        stringResource(R.string.panel_esp32_connected)
                    else
                        stringResource(R.string.panel_esp32_disconnected),
                    style = if (panel.isESP32Offline())
                        MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    else
                        MaterialTheme.typography.bodySmall,
                    color = if (panel.isESP32Offline())
                        PanelColors.StatusDisc
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                if (panel.isESP32Offline()) {
                    Text(
                        text = "No hay datos disponibles - ESP32 OFFLINE",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        color = PanelColors.StatusDisc
                    )
                } else {
                    Text(
                        text = stringResource(R.string.panel_relay_status),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    panel.relays.forEach { relay ->
                        RelayStatusRow(relay = relay)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelCardForRelay(
    panel: Panel,
    relay: Relay,
    isAlarmRelay: Boolean,
    onPanelSelect: () -> Unit,
    onEditPanel: () -> Unit,
    onDeletePanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor = if (isAlarmRelay) {
        Color(0xFFFFEBEE)
    } else {
        Color(0xFFFFFDE7)
    }

    val textColor = if (!isAlarmRelay) {
        Color(0xFF0D47A1)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick = {
            performHapticFeedback(context)
            onPanelSelect()
        },
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = if (!isAlarmRelay) textColor else Color.Unspecified
        )
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (!isAlarmRelay) textColor else Color.Unspecified
                    )

                    if (panel.clientDisplayName.isNotEmpty()) {
                        Text(
                            text = panel.clientDisplayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!isAlarmRelay) textColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.field_location, panel.location),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (!isAlarmRelay) textColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onEditPanel) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDeletePanel) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = { expanded = !expanded }
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Mostrar menos" else "Mostrar más"
                        )
                    }
                }
            }

            Text(
                text = "Estado: ${relay.name} en DISC",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                ),
                color = if (isAlarmRelay) PanelColors.StatusDisc else textColor
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
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
private fun RelayStatusRow(
    relay: Relay,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = relay.name,
            style = MaterialTheme.typography.bodyMedium
        )
        RelayStatusChip(status = relay.status)
    }
}

@Composable
private fun RelayStatusChip(
    status: String,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, textColor) = when (status) {
        "OK" -> HddGreen to Color.White
        "WARNING" -> HddYellow to Color.Black
        else -> HddRed to Color.White
    }

    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Text(
            text = status,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
    }
}

@Composable
fun PanelEmptyState(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Dashboard,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.no_panels),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}