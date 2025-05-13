package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.Event
import com.pqsolutions.hdd_monitor.data.UserData
import com.pqsolutions.hdd_monitor.domain.model.EventStatus

@Composable
fun EventCard(
    event: Event,
    client: Client?,
    user: UserData?,
    isAdmin: Boolean,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onAcceptClick: () -> Unit,
    onFinalizeClick: () -> Unit,
    onReopenClick: () -> Unit,
    onContactWhatsApp: (String, String, Event) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Tipo de evento
            event.type?.let { type ->
                if (type.isNotEmpty()) {
                    Text(
                        text = type.uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )
                }
            }

            // Indicador de no leído
            if (!event.isRead) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "NUEVO",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            // Título del evento
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Descripción del evento
            Text(
                text = event.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Fecha y hora del evento
            Column {
                Text(
                    text = "Fecha/Hora del Evento:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = event.getFormattedDateTime(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Última actualización
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Última actualización: ${event.lastUpdate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Panel asociado
            event.panelName?.let { panelName ->
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    Text(
                        text = "Panel:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = panelName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Cliente (solo modo admin)
            if (isAdmin && client != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    Text(
                        text = "Cliente:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = client.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Información de creación y estados
            Spacer(modifier = Modifier.height(8.dp))
            Column {
                // Creador
                user?.let { userData ->
                    Text(
                        text = "Creado por: ${userData.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Fecha de aceptación
                if (event.isAceptado || event.isFinalizado) {
                    event.acceptedAt?.let { acceptedAt ->
                        Text(
                            text = "Aceptado el: $acceptedAt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Fecha de finalización
                if (event.isFinalizado) {
                    event.finishedAt?.let { finishedAt ->
                        Text(
                            text = "Finalizado el: $finishedAt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Estado del evento
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Estado:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = when (event.status) {
                        EventStatus.STATUS_PROGRAMADO -> MaterialTheme.colorScheme.primary
                        EventStatus.STATUS_ACEPTADO -> MaterialTheme.colorScheme.tertiary
                        EventStatus.STATUS_FINALIZADO -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = event.status,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Botones de acción
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isAdmin) Arrangement.End else Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isAdmin) {
                    // Botón de contacto al usuario
                    if (user?.phone?.isNotEmpty() == true) {
                        Button(
                            onClick = { onContactWhatsApp(user.phone, user.name, event) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("Contactar Usuario")
                        }
                        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            // Estado PROGRAMADO
                            event.isProgramado -> {
                                if (event.needAdminAcceptance) {
                                    Button(
                                        onClick = onAcceptClick,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text("Aceptar")
                                    }
                                }
                                IconButton(onClick = onEditClick) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Editar evento",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            // Estado ACEPTADO
                            event.isAceptado -> {
                                Button(
                                    onClick = onFinalizeClick,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Text("Finalizar")
                                }
                                IconButton(onClick = onEditClick) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Editar evento",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            // Estado FINALIZADO
                            event.isFinalizado -> {
                                Button(
                                    onClick = onReopenClick,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Text("Reabrir")
                                }
                            }
                        }
                        // Botón eliminar siempre visible para admin
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Eliminar evento",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    // Botones de usuario
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (event.needUserAcceptance) {
                            Button(
                                onClick = onAcceptClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("Aceptar")
                            }
                        }

                        // Agregar botón de finalizar cuando el evento está aceptado
                        if (event.isAceptado) {
                            Button(
                                onClick = onFinalizeClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Text("Finalizar")
                            }
                        }

                        // Agregar botón de editar cuando el evento está programado y fue creado por este usuario
                        if (event.isProgramado && event.createdByAccountRole == "user") {
                            IconButton(onClick = onEditClick) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Editar evento",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Button(
                            onClick = { onContactWhatsApp("+51993533004", "Administrador", event) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("Contactar Admin")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EventList(
    events: List<Event>,
    clients: List<Client>,
    users: Map<String, UserData>,
    isAdmin: Boolean,
    onEditClick: (Event) -> Unit,
    onDeleteClick: (Event) -> Unit,
    onAcceptClick: (Event) -> Unit,
    onFinalizeClick: (Event) -> Unit,
    onReopenClick: (Event) -> Unit,
    onContactWhatsApp: (String, String, Event) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        events.forEach { event ->
            val client = clients.find { it.documentName == event.clientDocName }
            val user = event.createdByAccountId?.let { accountId ->
                users[accountId]
            }

            EventCard(
                event = event,
                client = client,
                user = user,
                isAdmin = isAdmin,
                onEditClick = { onEditClick(event) },
                onDeleteClick = { onDeleteClick(event) },
                onAcceptClick = { onAcceptClick(event) },
                onFinalizeClick = { onFinalizeClick(event) },
                onReopenClick = { onReopenClick(event) },
                onContactWhatsApp = onContactWhatsApp
            )
        }
    }
}