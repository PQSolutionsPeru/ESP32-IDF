package com.pqsolutions.hdd_monitor.presentation.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Event
import com.pqsolutions.hdd_monitor.domain.model.EventStatus
import com.pqsolutions.hdd_monitor.presentation.components.*
import com.pqsolutions.hdd_monitor.presentation.state.EventDialogEvent
import com.pqsolutions.hdd_monitor.presentation.state.EventFilter
import com.pqsolutions.hdd_monitor.presentation.state.EventSortOption
import com.pqsolutions.hdd_monitor.presentation.theme.HDD1_2Theme
import com.pqsolutions.hdd_monitor.presentation.util.HandleKeyboardFocus
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect
import com.pqsolutions.hdd_monitor.presentation.viewmodel.EventViewModel
import com.pqsolutions.hdd_monitor.presentation.viewmodel.NotificationViewModel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(
    viewModel: EventViewModel = hiltViewModel(),
    notificationViewModel: NotificationViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    isAdmin: Boolean,
    hasPendingNotifications: Boolean,
    eventId: String? = null,
    onNavigateToEvent: (String) -> Unit = {},
    onNavigateToPanel: (String) -> Unit = {},
    onViewNotificationHistoryClick: () -> Unit
) {
    HandleKeyboardFocus()
    val state by viewModel.state.collectAsState()
    val notificationState by notificationViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showFilterMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val dialogState = rememberDialogState()

    // LaunchedEffect corregido con manejo de excepciones
    LaunchedEffect(Unit) {
        Log.d("EventScreen", "LaunchedEffect iniciado en EventScreen")

        try {
            if (eventId != null && eventId.isNotBlank()) {
                Log.d("EventScreen", "Cargando evento específico: $eventId")
                viewModel.loadSpecificEvent(eventId)
            } else {
                Log.d("EventScreen", "Cargando todos los eventos disponibles")
                viewModel.loadEvents()
            }

            if (isAdmin) {
                viewModel.loadClients()
            }
        } catch (e: Exception) {
            Log.e("EventScreen", "Error en LaunchedEffect", e)
        }
    }

    LaunchedEffect(state.events, eventId) {
        if (eventId != null && state.events.isNotEmpty()) {
            val eventIndex = state.events.indexOfFirst {
                it.documentName == eventId || it.documentName.contains(eventId)
            }
            if (eventIndex >= 0) {
                listState.animateScrollToItem(eventIndex)
            }
        }
    }

    // DisposableEffect para limpiar recursos al desmontar la pantalla
    DisposableEffect(Unit) {
        onDispose {
            Log.d("EventScreen", "DisposableEffect: Limpiando recursos")
            viewModel.cancelCurrentJob()
        }
    }

    // BackHandler actualizado para reiniciar notificaciones al volver
    BackHandler {
        Log.d("EventScreen", "BackHandler: Navegando hacia atrás")
        viewModel.cancelCurrentJob()
        notificationViewModel.restartNotificationCollection()
        onBackClick()
    }

    HDD1_2Theme {
        Scaffold(
            topBar = {
                ScreenTopBar(
                    title = if (eventId != null)
                        stringResource(R.string.event_details)
                    else
                        stringResource(R.string.events),
                    onBackClick = {
                        // También reiniciar notificaciones cuando se presiona el botón de atrás
                        viewModel.cancelCurrentJob()
                        notificationViewModel.restartNotificationCollection()
                        onBackClick()
                    },
                    actions = {
                        if (eventId == null) {
                            IconButton(onClick = { showFilterMenu = true }) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filtrar eventos"
                                )
                            }

                            DropdownMenu(
                                expanded = showFilterMenu,
                                onDismissRequest = { showFilterMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Todos") },
                                    onClick = {
                                        viewModel.setFilter(EventFilter.All)
                                        showFilterMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.status_programmed)) },
                                    onClick = {
                                        viewModel.setFilter(EventFilter.Programmed)
                                        showFilterMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.event_status_accepted)) },
                                    onClick = {
                                        viewModel.setFilter(EventFilter.Accepted)
                                        showFilterMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.status_completed)) },
                                    onClick = {
                                        viewModel.setFilter(EventFilter.Completed)
                                        showFilterMenu = false
                                    }
                                )
                            }

                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.Default.Sort,
                                    contentDescription = "Ordenar eventos"
                                )
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                EventSortOption.SortField.values().forEach { field ->
                                    DropdownMenuItem(
                                        text = { Text(field.toString()) },
                                        onClick = {
                                            viewModel.setSortOption(
                                                EventSortOption(
                                                    field = field,
                                                    direction = EventSortOption.SortDirection.DESC
                                                )
                                            )
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        AnimatedNotificationBell(
                            hasNewNotifications = hasPendingNotifications,
                            notificationCount = notificationState.pendingCount,
                            onClick = {
                                performHapticFeedback(context)
                                onViewNotificationHistoryClick()
                            }
                        )
                    }
                )
            },
            floatingActionButton = {
                if (eventId == null) {
                    FloatingActionButton(
                        onClick = {
                            performHapticFeedback(context)
                            viewModel.showCreateDialog()
                        }
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.create_new_event)
                        )
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LoadingContent(
                    isLoading = state.isLoading,
                    isEmpty = state.events.isEmpty() && !state.isLoading,
                    error = state.error,
                    onRetry = {
                        if (eventId != null) {
                            viewModel.loadSpecificEvent(eventId)
                        } else {
                            viewModel.loadEvents()
                        }
                    },
                    emptyContent = { EmptyEventsContent() }
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = state.events,
                            key = { event -> "${event.clientDocName}_${event.documentName}" }
                        ) { event ->
                            val client = state.clients.find { it.documentName == event.clientDocName }
                            val user = event.createdByAccountId?.let { accountId ->
                                state.users.find { it.documentName == accountId }
                            }

                            EventCard(
                                event = event,
                                client = client,
                                user = user,
                                isAdmin = isAdmin,
                                onEditClick = {
                                    performHapticFeedback(context)
                                    viewModel.showEditDialog(event)
                                },
                                onDeleteClick = {
                                    performHapticFeedback(context)
                                    dialogState.show {
                                        DeleteConfirmationDialog(
                                            itemType = "evento",
                                            onConfirmDelete = {
                                                viewModel.deleteEvent(event.clientDocName, event.documentName)
                                                dialogState.dismiss()
                                            },
                                            onDismiss = {
                                                dialogState.dismiss()
                                            }
                                        )
                                    }
                                },
                                onAcceptClick = {
                                    performHapticFeedback(context)
                                    viewModel.updateEventStatus(
                                        event.clientDocName,
                                        event.documentName,
                                        EventStatus.STATUS_ACEPTADO
                                    )
                                },
                                onFinalizeClick = {
                                    performHapticFeedback(context)
                                    dialogState.show {
                                        ConfirmationDialog(
                                            title = "Confirmar finalización",
                                            message = "¿Está seguro que desea finalizar este evento? Esta acción no se puede deshacer.",
                                            onConfirm = {
                                                viewModel.updateEventStatus(
                                                    event.clientDocName,
                                                    event.documentName,
                                                    EventStatus.STATUS_FINALIZADO
                                                )
                                                dialogState.dismiss()
                                            },
                                            onDismiss = {
                                                dialogState.dismiss()
                                            },
                                            confirmText = "Finalizar",
                                            isDestructive = true
                                        )
                                    }
                                },
                                onReopenClick = {
                                    performHapticFeedback(context)
                                    viewModel.reopenEvent(
                                        event.clientDocName,
                                        event.documentName
                                    )
                                },
                                onContactWhatsApp = { phone, name, eventToShare ->
                                    launchWhatsApp(context, phone, name, eventToShare)
                                }
                            )
                        }
                    }
                }
            }

            if (state.showDialog) {
                EventDialog(
                    event = state.selectedEvent,
                    clients = state.clients,
                    availablePanels = state.availablePanels,
                    selectedDate = state.currentDate,
                    selectedTime = state.currentTime,
                    selectedClientForPanels = state.selectedClientForPanels,
                    selectedPanelDocName = state.selectedPanelDocName,
                    newEventTitle = state.newEventTitle,
                    newEventDescription = state.newEventDescription,
                    eventTypes = state.eventTypes,
                    selectedEventType = state.selectedEventType,
                    isAdmin = isAdmin,
                    onEvent = viewModel::onDialogEvent,
                    onDismiss = { viewModel.onDialogEvent(EventDialogEvent.Dismiss) }
                )
            }

            DialogHost(dialogState)
        }
    }
}

@Composable
private fun EmptyEventsContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.no_events),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun launchWhatsApp(context: android.content.Context, phone: String, name: String, event: Event) {
    val message = context.getString(R.string.whatsapp_event_message, event.title)
    val encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.toString())
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("https://wa.me/$phone?text=$encodedMessage")
    }
    context.startActivity(intent)
}