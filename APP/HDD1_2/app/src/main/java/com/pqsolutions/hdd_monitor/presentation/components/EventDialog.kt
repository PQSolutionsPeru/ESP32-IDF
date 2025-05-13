package com.pqsolutions.hdd_monitor.presentation.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.Event
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.presentation.state.EventDialogEvent
import com.pqsolutions.hdd_monitor.presentation.util.HandleKeyboardFocus
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EventDialog(
    event: Event?,
    clients: List<Client>,
    availablePanels: List<Panel>,
    selectedDate: LocalDate?,
    selectedTime: LocalTime?,
    selectedClientForPanels: String?,
    selectedPanelDocName: String?,
    newEventTitle: String,
    newEventDescription: String,
    eventTypes: List<String>,
    selectedEventType: String?,
    isAdmin: Boolean,
    onEvent: (EventDialogEvent) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Manejar el foco del teclado globalmente
    HandleKeyboardFocus()

    val isEditing = event != null
    val canEdit = event == null || event.isProgramado

    val title = if (isEditing) event?.title ?: "" else newEventTitle
    val description = if (isEditing) event?.text ?: "" else newEventDescription
    var selectedClients by remember { mutableStateOf(setOf<String>()) }
    var showNewTypeDialog by remember { mutableStateOf(false) }

    val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // Validación del máximo de caracteres
    val maxTitleLength = 50
    val maxDescriptionLength = 500

    // Validación de fechas
    val isDateValid = if (isEditing) {
        selectedDate != null
    } else {
        selectedDate != null &&
                (selectedDate.isAfter(LocalDate.now()) || selectedDate.isEqual(LocalDate.now()))
    }

    val isTimeValid = if (isEditing) {
        selectedTime != null
    } else {
        selectedTime != null &&
                (selectedDate?.isAfter(LocalDate.now()) == true ||
                        selectedDate?.isEqual(LocalDate.now()) == true)
    }

    // Validación del formulario
    val isFormValid = canEdit &&
            title.isNotBlank() &&
            description.isNotBlank() &&
            isDateValid &&
            isTimeValid &&
            title.length <= maxTitleLength &&
            description.length <= maxDescriptionLength &&
            selectedEventType != null &&
            (isEditing || isAdmin && selectedClients.isNotEmpty() || !isAdmin)

    // Date Picker
    val datePickerDialog = remember {
        val calendar = Calendar.getInstance()
        selectedDate?.let {
            calendar.set(it.year, it.monthValue - 1, it.dayOfMonth)
        }

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                onEvent(EventDialogEvent.DateSelected(LocalDate.of(year, month + 1, dayOfMonth)))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = System.currentTimeMillis()
        }
    }

    // Time Picker
    val timePickerDialog = remember {
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                try {
                    // Log para depuración
                    Log.d("EventDialog", "Hora seleccionada: $hourOfDay:$minute")

                    // Crear LocalTime directamente, sin validaciones adicionales
                    val selectedLocalTime = LocalTime.of(hourOfDay, minute)

                    // Log adicional para confirmar la creación
                    Log.d("EventDialog", "LocalTime creado: $selectedLocalTime")

                    // Enviar el evento con la hora seleccionada
                    onEvent(EventDialogEvent.TimeSelected(selectedLocalTime))
                } catch (e: Exception) {
                    // Log detallado del error
                    Log.e("EventDialog", "Error seleccionando hora: ${e.message}", e)
                }
            },
            selectedTime?.hour ?: LocalTime.now().hour,
            selectedTime?.minute ?: LocalTime.now().minute,
            true // formato 24 horas
        )
    }

    AlertDialog(
        onDismissRequest = {
            keyboardController?.hide()
            focusManager.clearFocus()
            onDismiss()
        },
        title = {
            Text(
                text = stringResource(
                    if (event == null) R.string.create_new_event else R.string.edit_event
                )
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    if (!canEdit) {
                        Text(
                            text = stringResource(R.string.event_already_accepted),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    EventTypeSelector(
                        selectedEventType = selectedEventType,
                        eventTypes = eventTypes,
                        isAdmin = isAdmin,
                        onTypeSelected = { eventType ->
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onEvent(EventDialogEvent.EventTypeSelected(eventType))
                        },
                        onCreateNewType = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            showNewTypeDialog = true
                        },
                        enabled = canEdit
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            if (canEdit && it.length <= maxTitleLength) {
                                onEvent(EventDialogEvent.TitleChanged(it))
                            }
                        },
                        label = { Text(stringResource(R.string.event_title)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused && title.length >= maxTitleLength) {
                                    keyboardController?.hide()
                                }
                            },
                        enabled = canEdit,
                        singleLine = true,
                        isError = title.isBlank(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next,
                            keyboardType = KeyboardType.Text
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        supportingText = {
                            Text(
                                "${title.length}/$maxTitleLength" +
                                        if (title.isBlank()) " - ${stringResource(R.string.error_required_field)}" else ""
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = {
                            if (canEdit && it.length <= maxDescriptionLength) {
                                onEvent(EventDialogEvent.DescriptionChanged(it))
                            }
                        },
                        label = { Text(stringResource(R.string.event_description)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused && description.length >= maxDescriptionLength) {
                                    keyboardController?.hide()
                                }
                            },
                        enabled = canEdit,
                        minLines = 3,
                        maxLines = 5,
                        isError = description.isBlank(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = KeyboardType.Text
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        ),
                        supportingText = {
                            Text(
                                "${description.length}/$maxDescriptionLength" +
                                        if (description.isBlank()) " - ${stringResource(R.string.error_required_field)}" else ""
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = selectedDate?.format(dateFormatter) ?: "",
                            onValueChange = { },
                            label = { Text(stringResource(R.string.date)) },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        keyboardController?.hide()
                                        datePickerDialog.show()
                                    }
                                },
                            readOnly = true,
                            enabled = canEdit,
                            isError = !isDateValid,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (canEdit) {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            datePickerDialog.show()
                                        }
                                    },
                                    enabled = canEdit
                                ) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = null)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedTextField(
                            value = selectedTime?.format(timeFormatter) ?: "",
                            onValueChange = { },
                            label = { Text(stringResource(R.string.time)) },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        keyboardController?.hide()
                                        timePickerDialog.show()
                                    }
                                },
                            readOnly = true,
                            enabled = canEdit,
                            isError = !isTimeValid,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (canEdit) {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            timePickerDialog.show()
                                        }
                                    },
                                    enabled = canEdit
                                ) {
                                    Icon(Icons.Default.Schedule, contentDescription = null)
                                }
                            }
                        )
                    }
                }

                // Solo mostrar selección de cliente para administradores en modo creación
                if (!isEditing && isAdmin) {
                    item {
                        Text(
                            text = stringResource(R.string.select_clients),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )

                        Text(
                            text = stringResource(R.string.single_client_panel_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        clients.sortedBy { it.name }.forEach { client ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = client.documentName in selectedClients,
                                    onCheckedChange = { checked ->
                                        if (canEdit) {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            selectedClients = if (checked) {
                                                setOf(client.documentName)  // Solo permitir uno
                                            } else {
                                                emptySet()
                                            }
                                            onEvent(EventDialogEvent.MultipleClientsSelected(selectedClients.toList()))
                                        }
                                    },
                                    enabled = canEdit
                                )
                                Text(
                                    text = client.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Mostrar selector de panel si hay cliente seleccionado o es un usuario normal
                if ((isAdmin && selectedClients.size == 1) || (!isAdmin && selectedClientForPanels != null)) {
                    item {
                        if (availablePanels.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }

                            Text(
                                text = stringResource(R.string.select_panel),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = {
                                    if (canEdit) {
                                        expanded = it
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    }
                                }
                            ) {
                                OutlinedTextField(
                                    value = availablePanels.find { it.documentName == selectedPanelDocName }?.name
                                        ?: stringResource(R.string.select_panel),
                                    onValueChange = { },
                                    label = { Text(stringResource(R.string.panel)) },
                                    readOnly = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    enabled = canEdit
                                )

                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    availablePanels
                                        .sortedBy { it.name }
                                        .forEach { panel ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = panel.name,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                onClick = {
                                                    keyboardController?.hide()
                                                    focusManager.clearFocus()
                                                    onEvent(EventDialogEvent.PanelSelected(panel.documentName))
                                                    expanded = false
                                                }
                                            )
                                        }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onEvent(EventDialogEvent.Confirm)
                },
                enabled = isFormValid
            ) {
                Text(
                    stringResource(
                        if (isEditing) R.string.update else R.string.create
                    )
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    if (showNewTypeDialog && isAdmin) {
        NewEventTypeDialog(
            onDismiss = {
                keyboardController?.hide()
                focusManager.clearFocus()
                showNewTypeDialog = false
            },
            onConfirm = { newType ->
                keyboardController?.hide()
                focusManager.clearFocus()
                onEvent(EventDialogEvent.CreateNewEventType(newType))
                showNewTypeDialog = false
            }
        )
    }
}