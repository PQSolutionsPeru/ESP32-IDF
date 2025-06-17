package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.pqsolutions.hdd_monitor.data.Event
import com.pqsolutions.hdd_monitor.data.EventRepository
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.model.EventStatus
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.presentation.state.EventDialogEvent
import com.pqsolutions.hdd_monitor.presentation.state.EventFilter
import com.pqsolutions.hdd_monitor.presentation.state.EventOperation
import com.pqsolutions.hdd_monitor.presentation.state.EventSortOption
import com.pqsolutions.hdd_monitor.presentation.state.EventUIEvent
import com.pqsolutions.hdd_monitor.presentation.state.EventViewState
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import com.pqsolutions.hdd_monitor.util.toUserFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import java.time.ZoneId

@HiltViewModel
class EventViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val panelRepository: PanelRepository,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    companion object {
        private const val TAG = "EventViewModel"
        private const val EVENT_TYPES_PATH = "hdd-monitor/event_types"
        val DEFAULT = EventSortOption(EventSortOption.SortField.LAST_UPDATE, EventSortOption.SortDirection.DESC)
    }

    private val _state = MutableStateFlow(EventViewState.initial())
    val state: StateFlow<EventViewState> = _state.asStateFlow()

    private val _uiEvent = Channel<EventUIEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private var currentFilter: EventFilter = EventFilter.All
    private var currentSort = EventSortOption.DEFAULT

    private var eventsJob: Job? = null

    init {
        currentSort = EventSortOption(
            field = EventSortOption.SortField.LAST_UPDATE,
            direction = EventSortOption.SortDirection.DESC
        )

        loadEvents()
        loadEventTypes()
        loadUsers()
    }

    fun loadEvents() {
        eventsJob?.cancel()

        eventsJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Iniciando carga de eventos")
                _state.update { it.copy(isLoading = true, error = null) }
                val currentUser = userRepository.getCurrentUser()

                if (currentUser != null) {
                    Log.d(TAG, "Usuario actual: ${currentUser.documentName}, rol: ${currentUser.role}")
                    if (currentUser.role == UserRole.ADMIN) {
                        loadUsers()
                    }

                    val eventsFlow = if (currentUser.role == UserRole.ADMIN) {
                        Log.d(TAG, "Cargando todos los eventos (vista de admin)")
                        eventRepository.getAllEventsFlow()
                    } else {
                        Log.d(TAG, "Cargando eventos del cliente: ${currentUser.clientDocName}")
                        eventRepository.getEventsFlow(currentUser.clientDocName)
                    }

                    eventsFlow
                        .catch { e ->
                            Log.e(TAG, "Error loading events", e)
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    error = e.message ?: "Error desconocido"
                                )
                            }
                        }
                        .collect { events ->
                            val validEvents = events.filter { event ->
                                val isValid = event.documentName.startsWith(DocumentPrefixes.EVENT) &&
                                        event.clientDocName.startsWith(DocumentPrefixes.CLIENT) &&
                                        (event.panelDocName?.startsWith(DocumentPrefixes.PANEL) ?: true)
                                if (!isValid) {
                                    Log.w(TAG, "Invalid event document found: ${event.documentName}")
                                }
                                isValid
                            }

                            Log.d(TAG, "Eventos válidos encontrados: ${validEvents.size}")
                            validEvents.forEach {
                                Log.d(TAG, "Evento: ${it.documentName}, título: ${it.title}, cliente: ${it.clientDocName}")
                            }

                            val filteredAndSortedEvents = applyFilterAndSort(validEvents)
                            Log.d(TAG, "Eventos después de filtro y ordenamiento: ${filteredAndSortedEvents.size}")

                            _state.update {
                                it.copy(
                                    events = filteredAndSortedEvents,
                                    isLoading = false,
                                    error = null,
                                    isRefreshing = false,
                                    lastUpdate = System.currentTimeMillis()
                                )
                            }

                            validEvents.filter { it.panelDocName != null }.forEach { event ->
                                startPanelObservation(event)
                            }
                        }
                } else {
                    Log.e(TAG, "No se encontró usuario actual")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "No se encontró usuario actual"
                        )
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in loadEvents", e)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.toUserFriendlyMessage()
                        )
                    }
                }
            }
        }
    }

    fun loadSpecificEvent(eventId: String?) {
        if (eventId.isNullOrBlank()) {
            Log.d(TAG, "EventId es null o vacío, cargando todos los eventos")
            loadEvents()
            return
        }

        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Cargando evento específico: $eventId")
                _state.update { it.copy(isLoading = true, error = null) }
                val currentUser = userRepository.getCurrentUser()

                if (currentUser != null) {
                    val eventsFlow = if (currentUser.role == UserRole.ADMIN) {
                        eventRepository.getAllEventsFlow()
                    } else {
                        eventRepository.getEventsFlow(currentUser.clientDocName)
                    }

                    eventsFlow
                        .catch { e ->
                            Log.e(TAG, "Error loading specific event", e)
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    error = e.message ?: "Error desconocido"
                                )
                            }
                        }
                        .collect { events ->
                            val event = events.find {
                                it.documentName == eventId ||
                                        it.documentName.contains(eventId)
                            }

                            if (event != null) {
                                Log.d(TAG, "Evento específico encontrado: ${event.documentName}")
                                _state.update {
                                    it.copy(
                                        events = listOf(event),
                                        isLoading = false,
                                        error = null,
                                        lastUpdate = System.currentTimeMillis()
                                    )
                                }
                            } else {
                                Log.e(TAG, "Evento no encontrado: $eventId")
                                _state.update {
                                    it.copy(
                                        isLoading = false,
                                        error = "Evento no encontrado"
                                    )
                                }
                            }
                        }
                } else {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "No se encontró usuario actual"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading specific event", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Error desconocido"
                    )
                }
            }
        }
    }

    fun cancelCurrentJob() {
        try {
            Log.d(TAG, "Cancelando trabajo actual de eventos y observaciones de paneles")
            eventsJob?.cancel()
            eventsJob = null

            Log.d(TAG, "Se han cancelado todos los trabajos y observaciones")
        } catch (e: Exception) {
            Log.e(TAG, "Error al cancelar trabajos", e)
        }
    }

    private fun loadEventTypes() {
        viewModelScope.launch {
            try {
                val documentSnapshot = firestore.document(EVENT_TYPES_PATH).get().await()
                val typesString = documentSnapshot.getString("types")
                val types = typesString?.split(",")?.map { it.trim() }?.sorted() ?: emptyList()
                _state.update { it.copy(eventTypes = types) }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading event types", e)
                _uiEvent.send(EventUIEvent.ShowSnackbar("Error al cargar tipos de eventos"))
            }
        }
    }

    private suspend fun initializeEventCreation() {
        try {
            val currentUser = userRepository.getCurrentUser()
                ?: throw IllegalStateException("No hay usuario autenticado")

            if (currentUser.role == UserRole.ADMIN) {
                val clients = eventRepository.getClients()
                val validClients = clients.filter { it.documentName.startsWith(DocumentPrefixes.CLIENT) }
                _state.update { it.copy(clients = validClients) }
            } else {
                _state.update {
                    it.copy(
                        selectedClients = listOf(currentUser.clientDocName),
                        selectedClientForPanels = currentUser.clientDocName
                    )
                }
                loadPanelsForClient(currentUser.clientDocName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en initializeEventCreation", e)
            _uiEvent.send(EventUIEvent.ShowSnackbar(e.toUserFriendlyMessage()))
        }
    }

    private fun loadUsers() {
        viewModelScope.launch {
            try {
                val usersResult = userRepository.getAllUsers()
                usersResult.onSuccess { usersList ->
                    _state.update { it.copy(users = usersList) }
                }.onFailure { error ->
                    Log.e(TAG, "Error loading users", error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadUsers", e)
            }
        }
    }

    private fun loadPanelsForClient(clientDocName: String) {
        viewModelScope.launch {
            try {
                _state.update {
                    it.copy(selectedClientForPanels = clientDocName)
                }

                val panels = eventRepository.getPanelsByClient(clientDocName)
                _state.update {
                    it.copy(availablePanels = panels)
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun reopenEvent(clientDocName: String, eventDocName: String) {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser?.role != UserRole.ADMIN) {
                    _uiEvent.send(EventUIEvent.ShowSnackbar("Solo los administradores pueden reabrir eventos"))
                    return@launch
                }

                eventRepository.updateEventStatus(
                    clientDocName = clientDocName,
                    eventDocName = eventDocName,
                    newStatus = EventStatus.STATUS_ACEPTADO,
                    updatedByAccountId = currentUser.documentName,
                    isAdmin = true
                ).onSuccess {
                    _uiEvent.send(EventUIEvent.ShowSnackbar("Evento reabierto exitosamente"))
                    loadEvents()
                }.onFailure { error ->
                    handleError(error)
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun showCreateDialog() {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                    ?: throw IllegalStateException("No hay usuario autenticado")

                _state.update {
                    it.copy(
                        showDialog = true,
                        currentUserRole = currentUser.role,
                        currentUserClientDocName = currentUser.clientDocName,
                        selectedEvent = null,
                        currentDate = null,
                        currentTime = null,
                        newEventTitle = "",
                        newEventDescription = "",
                        selectedEventType = null,
                        selectedPanelDocName = null,
                        selectedPanelName = null,
                        availablePanels = emptyList()
                    )
                }

                when (currentUser.role) {
                    UserRole.ADMIN -> {
                        val clients = eventRepository.getClients()
                        val validClients = clients.filter {
                            it.documentName.startsWith(DocumentPrefixes.CLIENT)
                        }
                        _state.update { it.copy(clients = validClients) }
                    }
                    UserRole.USER -> {
                        _state.update {
                            it.copy(
                                selectedClients = listOf(currentUser.clientDocName),
                                selectedClientForPanels = currentUser.clientDocName
                            )
                        }
                        loadPanelsForClient(currentUser.clientDocName)
                    }
                }

                loadEventTypes()

            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun showEditDialog(event: Event) {
        if (!event.isProgramado) {
            viewModelScope.launch {
                _uiEvent.send(EventUIEvent.ShowSnackbar("Solo se pueden editar eventos en estado PROGRAMADO"))
            }
            return
        }

        Log.d(TAG, "Opening edit dialog for event: ${event.toLogString()}")
        viewModelScope.launch {
            _state.update {
                it.copy(
                    showDialog = true,
                    selectedEvent = event,
                    currentDate = event.dateTime?.toLocalDate(),
                    currentTime = event.dateTime?.toLocalTime(),
                    selectedClientForPanels = event.clientDocName,
                    selectedPanelDocName = event.panelDocName,
                    selectedPanelName = event.panelName,
                    selectedEventType = event.type,
                    newEventTitle = event.title,
                    newEventDescription = event.text
                )
            }
            loadPanelsForClient(event.clientDocName)
        }
    }

    fun createNewEventType(newType: String) {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser?.role != UserRole.ADMIN) {
                    _uiEvent.send(EventUIEvent.ShowSnackbar("Solo los administradores pueden crear nuevos tipos de eventos"))
                    return@launch
                }

                _state.update { it.copy(isCreatingNewType = true, newTypeError = null) }

                val docRef = firestore.document(EVENT_TYPES_PATH)

                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(docRef)
                    val currentTypes = snapshot.getString("types")?.split(",")?.map { it.trim() } ?: emptyList()

                    if (currentTypes.contains(newType)) {
                        throw IllegalArgumentException("Este tipo de evento ya existe")
                    }

                    val updatedTypes = (currentTypes + newType).sorted().joinToString(",")
                    transaction.update(docRef, "types", updatedTypes)
                }.await()

                loadEventTypes()
                _uiEvent.send(EventUIEvent.ShowSnackbar("Nuevo tipo de evento creado: $newType"))

            } catch (e: Exception) {
                Log.e(TAG, "Error creating new event type", e)
                _state.update { it.copy(newTypeError = e.message) }
                _uiEvent.send(EventUIEvent.ShowSnackbar(e.message ?: "Error al crear nuevo tipo de evento"))
            } finally {
                _state.update { it.copy(isCreatingNewType = false) }
            }
        }
    }

    fun onDialogEvent(event: EventDialogEvent) {
        when (event) {
            is EventDialogEvent.TitleChanged -> {
                _state.update { currentState ->
                    if (currentState.selectedEvent != null) {
                        currentState.copy(
                            selectedEvent = currentState.selectedEvent.copy(title = event.title)
                        )
                    } else {
                        currentState.copy(newEventTitle = event.title)
                    }
                }
            }
            is EventDialogEvent.DescriptionChanged -> {
                _state.update { currentState ->
                    if (currentState.selectedEvent != null) {
                        currentState.copy(
                            selectedEvent = currentState.selectedEvent.copy(text = event.description)
                        )
                    } else {
                        currentState.copy(newEventDescription = event.description)
                    }
                }
            }
            is EventDialogEvent.Dismiss -> {
                clearDialogState()
            }
            else -> {
                viewModelScope.launch {
                    try {
                        when (event) {
                            is EventDialogEvent.DateSelected -> {
                                _state.update { it.copy(currentDate = event.date) }
                            }
                            is EventDialogEvent.TimeSelected -> {
                                _state.update { it.copy(currentTime = event.time) }
                            }
                            is EventDialogEvent.EventTypeSelected -> {
                                Log.d(TAG, "Tipo de evento seleccionado: ${event.eventType}")
                                _state.update { currentState ->
                                    if (currentState.selectedEvent != null) {
                                        currentState.copy(
                                            selectedEvent = currentState.selectedEvent.copy(type = event.eventType),
                                            selectedEventType = event.eventType
                                        )
                                    } else {
                                        currentState.copy(selectedEventType = event.eventType)
                                    }
                                }
                            }
                            is EventDialogEvent.MultipleClientsSelected -> {
                                if (_state.value.currentUserRole == UserRole.ADMIN) {
                                    _state.update { it.copy(selectedClients = event.clientDocNames) }
                                    if (event.clientDocNames.size == 1) {
                                        loadPanelsForClient(event.clientDocNames.first())
                                    } else {
                                        _state.update {
                                            it.copy(
                                                availablePanels = emptyList(),
                                                selectedClientForPanels = null,
                                                selectedPanelDocName = null,
                                                selectedPanelName = null
                                            )
                                        }
                                    }
                                }
                            }
                            is EventDialogEvent.PanelSelected -> {
                                _state.update {
                                    val selectedPanel = it.availablePanels.find { panel ->
                                        panel.documentName == event.panelDocName
                                    }
                                    if (it.selectedEvent != null) {
                                        it.copy(
                                            selectedEvent = it.selectedEvent.copy(
                                                panelDocName = event.panelDocName,
                                                panelName = selectedPanel?.name
                                            ),
                                            selectedPanelDocName = event.panelDocName,
                                            selectedPanelName = selectedPanel?.name
                                        )
                                    } else {
                                        it.copy(
                                            selectedPanelDocName = event.panelDocName,
                                            selectedPanelName = selectedPanel?.name
                                        )
                                    }
                                }
                            }
                            is EventDialogEvent.CreateNewEventType -> {
                                if (_state.value.currentUserRole == UserRole.ADMIN) {
                                    createNewEventType(event.type)
                                } else {
                                    _uiEvent.send(EventUIEvent.ShowSnackbar("Solo los administradores pueden crear tipos de eventos"))
                                }
                            }
                            is EventDialogEvent.Confirm -> handleConfirmDialog()
                            else -> {}
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en onDialogEvent", e)
                        handleError(e)
                    }
                }
            }
        }
    }

    private suspend fun handleConfirmDialog() {
        val currentState = _state.value
        if (currentState.hasDateTime) {
            try {
                val peruZoneId = ZoneId.of("America/Lima")

                val selectedDate = currentState.currentDate!!
                val selectedTime = currentState.currentTime!!
                val dateTime = LocalDateTime.of(selectedDate, selectedTime)

                Log.d(TAG, "Fecha/hora seleccionada: ${dateTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"))}")

                val now: LocalDateTime = LocalDateTime.now(peruZoneId)
                Log.d(TAG, "Fecha/hora actual (Perú): ${now.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"))}")

                if (dateTime.isBefore(now)) {
                    _uiEvent.send(EventUIEvent.ShowSnackbar("La fecha y hora seleccionadas deben ser posteriores a la hora actual"))
                    return
                }

                val currentUser = userRepository.getCurrentUser()
                    ?: throw IllegalStateException("No hay usuario autenticado")

                if (currentState.selectedEvent != null) {
                    val updatedEvent = currentState.selectedEvent.update(
                        title = currentState.selectedEvent.title,
                        text = currentState.selectedEvent.text,
                        dateTime = dateTime,
                        panelDocName = currentState.selectedEvent.panelDocName,
                        panelName = currentState.selectedEvent.panelName,
                        type = currentState.selectedEventType
                    )

                    if (updatedEvent == null) {
                        _uiEvent.send(EventUIEvent.ShowSnackbar("No se puede actualizar un evento ya aceptado"))
                        return
                    }

                    if (updatedEvent.title.isBlank() || updatedEvent.text.isBlank() || updatedEvent.type.isNullOrBlank()) {
                        _uiEvent.send(EventUIEvent.ShowSnackbar("Evento inválido. Título, descripción y tipo son obligatorios"))
                        return
                    }

                    updateEvent(updatedEvent)
                } else {
                    val clientDocName = if (currentUser.role == UserRole.ADMIN) {
                        currentState.selectedClients.firstOrNull()
                            ?: throw IllegalStateException("Debe seleccionar un cliente")
                    } else {
                        currentUser.clientDocName
                    }

                    val eventType = currentState.selectedEventType
                        ?: throw IllegalStateException("Debe seleccionar un tipo de evento")

                    Log.d(TAG, "Creando evento: tipo=$eventType, fecha=${dateTime.toLocalDate()}, hora=${dateTime.toLocalTime()}")

                    val newEvent = Event.createNew(
                        clientDocName = clientDocName,
                        panelDocName = currentState.selectedPanelDocName,
                        panelName = currentState.selectedPanelName,
                        title = currentState.newEventTitle,
                        text = currentState.newEventDescription,
                        dateTime = dateTime,
                        type = eventType,
                        createdByAccountId = currentUser.documentName,
                        createdByAccountRole = currentUser.role.toString()
                    )

                    if (newEvent.title.isBlank() || newEvent.text.isBlank()) {
                        _uiEvent.send(EventUIEvent.ShowSnackbar("Evento inválido. Título y descripción son obligatorios"))
                        return
                    }

                    Log.d(TAG, "Evento a crear: ${newEvent.title}, tipo: ${newEvent.type}, fecha/hora: ${newEvent.date_time}, panel: ${newEvent.panelDocName ?: "Sin panel"}")

                    if (currentUser.role == UserRole.ADMIN) {
                        createEvent(newEvent, currentState.selectedClients)
                    } else {
                        createEvent(newEvent, listOf(clientDocName))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en handleConfirmDialog", e)
                _uiEvent.send(EventUIEvent.ShowSnackbar(e.message ?: "Error al procesar el evento"))
            }
        } else {
            _uiEvent.send(EventUIEvent.ShowSnackbar("Fecha y hora son requeridas"))
        }
    }

    private fun startPanelObservation(event: Event) {
        if (event.panelDocName != null && event.isProgramado) {
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Starting panel observation for event: ${event.documentName}, panel: ${event.panelDocName}")

                    panelRepository.observePanelUpdates(event.clientDocName, event.panelDocName)
                        .take(1)
                        .collect { panel: Panel? ->
                            if (panel != null) {
                                Log.d(TAG, "Panel data retrieved: ${panel.name} for event: ${event.documentName}")
                                Log.d(TAG, "Current event panel name: ${event.panelName}")

                                if (panel.name != event.panelName) {
                                    Log.d(TAG, "Panel name differs, updating only UI for event: ${event.documentName}")

                                    _state.update { currentState ->
                                        val updatedEvents = currentState.events.map {
                                            if (it.documentName == event.documentName)
                                                it.copy(panelName = panel.name)
                                            else
                                                it
                                        }
                                        currentState.copy(events = updatedEvents)
                                    }
                                } else {
                                    Log.d(TAG, "Panel name unchanged for event: ${event.documentName}, no update needed")
                                }
                            } else {
                                handleDeletedPanel(event)
                            }
                        }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error observing panel updates", e)
                    }
                }
            }
        }
    }

    private fun updateEventPanelInfo(event: Event) {
        if (_state.value.events.none { it.documentName == event.documentName }) {
            Log.d(TAG, "Ignorando actualización para evento ya no cargado: ${event.documentName}")
            return
        }

        viewModelScope.launch {
            try {
                val existingEvent = state.value.events.find { it.documentName == event.documentName }
                if (existingEvent?.panelName != event.panelName && existingEvent?.isProgramado == true) {
                    Log.d(TAG, "Actualizando información de panel para evento: ${event.documentName}")

                    val eventWithoutLastUpdateChange = event.copy(lastUpdate = existingEvent.lastUpdate)

                    eventRepository.updateEventWithoutLastUpdate(event.clientDocName, eventWithoutLastUpdateChange)
                        .onSuccess {
                            _state.update { currentState ->
                                val updatedEvents = currentState.events.map {
                                    if (it.documentName == event.documentName) event else it
                                }
                                currentState.copy(events = updatedEvents)
                            }
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Error actualizando información de panel", error)
                        }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error en updateEventPanelInfo", e)
                }
            }
        }
    }

    private fun handleDeletedPanel(event: Event) {
        viewModelScope.launch {
            try {
                val updatedEvent = event.copy(panelDocName = null, panelName = null)
                eventRepository.updateEvent(event.clientDocName, updatedEvent, updateLastUpdate = false)
                    .onSuccess {
                        _uiEvent.send(EventUIEvent.ShowSnackbar("Panel eliminado: se actualizó el evento"))

                        _state.update { currentState ->
                            val updatedEvents = currentState.events.map {
                                if (it.documentName == event.documentName)
                                    it.copy(panelDocName = null, panelName = null)
                                else
                                    it
                            }
                            currentState.copy(events = updatedEvents)
                        }
                    }
                    .onFailure { error ->
                        handleError(error)
                    }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun createEvent(event: Event, selectedClients: List<String>) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(currentOperation = EventOperation.Loading) }

                if (event.panelDocName != null) {
                    val panelExists = verifyPanelExists(event.clientDocName, event.panelDocName)
                    if (!panelExists) {
                        throw IllegalStateException("El panel seleccionado ya no existe")
                    }
                }

                if (!event.isValid()) {
                    throw IllegalArgumentException("Evento inválido")
                }

                val validClients = selectedClients.all { it.startsWith(DocumentPrefixes.CLIENT) }
                if (!validClients) {
                    throw IllegalArgumentException("Nombres de documentos de clientes inválidos")
                }

                eventRepository.createEvent(selectedClients, event)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = EventOperation.Success("Evento creado exitosamente"),
                                showDialog = false
                            )
                        }
                        _uiEvent.send(EventUIEvent.ShowSnackbar("Evento creado exitosamente"))
                        clearDialogState()
                        loadEvents()
                    }
                    .onFailure { error ->
                        handleError(error)
                    }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun updateEvent(event: Event) {
        viewModelScope.launch {
            try {
                if (!event.isProgramado) {
                    throw IllegalStateException("Solo se pueden actualizar eventos en estado PROGRAMADO")
                }

                if (!event.isValid()) {
                    throw IllegalArgumentException("Evento inválido")
                }

                if (event.panelDocName != null) {
                    val panelExists = verifyPanelExists(event.clientDocName, event.panelDocName)
                    if (!panelExists) {
                        throw IllegalStateException("El panel seleccionado ya no existe")
                    }
                }

                if (!event.documentName.startsWith(DocumentPrefixes.EVENT) ||
                    !event.clientDocName.startsWith(DocumentPrefixes.CLIENT) ||
                    (event.panelDocName != null && !event.panelDocName.startsWith(DocumentPrefixes.PANEL))) {
                    throw IllegalArgumentException("Nombres de documentos inválidos")
                }

                _state.update { it.copy(currentOperation = EventOperation.Loading) }

                eventRepository.updateEvent(event.clientDocName, event)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = EventOperation.Success("Evento actualizado exitosamente"),
                                showDialog = false
                            )
                        }
                        _uiEvent.send(EventUIEvent.ShowSnackbar("Evento actualizado exitosamente"))
                        clearDialogState()
                        loadEvents()
                        startPanelObservation(event)
                    }
                    .onFailure { error ->
                        handleError(error)
                    }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun updateEventStatus(clientDocName: String, eventDocName: String, newStatus: String) {
        viewModelScope.launch {
            try {
                if (!clientDocName.startsWith(DocumentPrefixes.CLIENT) ||
                    !eventDocName.startsWith(DocumentPrefixes.EVENT)) {
                    throw IllegalArgumentException("Nombres de documentos inválidos")
                }

                _state.update { it.copy(currentOperation = EventOperation.Loading) }
                val currentUser = userRepository.getCurrentUser() ?:
                throw IllegalStateException("No hay usuario autenticado")

                val isAdmin = currentUser.role == UserRole.ADMIN

                eventRepository.updateEventStatus(
                    clientDocName = clientDocName,
                    eventDocName = eventDocName,
                    newStatus = newStatus,
                    updatedByAccountId = currentUser.documentName,
                    isAdmin = isAdmin
                ).onSuccess {
                    _state.update {
                        it.copy(currentOperation = EventOperation.Success("Estado actualizado exitosamente"))
                    }
                    _uiEvent.send(EventUIEvent.ShowSnackbar("Estado actualizado exitosamente"))
                    loadEvents()
                }.onFailure { error ->
                    handleError(error)
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun deleteEvent(clientDocName: String, eventDocName: String) {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                    ?: throw IllegalStateException("No hay usuario autenticado")

                if (currentUser.role != UserRole.ADMIN) {
                    _uiEvent.send(EventUIEvent.ShowSnackbar("Solo los administradores pueden eliminar eventos"))
                    return@launch
                }

                _state.update { it.copy(currentOperation = EventOperation.Loading) }

                eventRepository.deleteEvent(clientDocName, eventDocName, isAdmin = true)
                    .onSuccess {
                        _state.update {
                            it.copy(
                                currentOperation = EventOperation.Success("Evento eliminado exitosamente")
                            )
                        }
                        _uiEvent.send(EventUIEvent.ShowSnackbar("Evento eliminado exitosamente"))
                        loadEvents()
                    }
                    .onFailure { error ->
                        handleError(error)
                    }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    private suspend fun verifyPanelExists(clientDocName: String, panelDocName: String): Boolean {
        return panelRepository.verifyPanelExists(clientDocName, panelDocName)
    }

    fun setFilter(filter: EventFilter) {
        currentFilter = filter
        loadEvents()
    }

    fun setSortOption(sortOption: EventSortOption) {
        currentSort = sortOption
        loadEvents()
    }

    fun filterByType(eventType: String) {
        currentFilter = EventFilter.ByType(eventType)
        loadEvents()
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true) }
        loadEvents()
        loadEventTypes()
        loadUsers()
    }

    fun loadClients() {
        viewModelScope.launch {
            try {
                val clients = eventRepository.getClients()
                val validClients = clients.filter { it.documentName.startsWith(DocumentPrefixes.CLIENT) }
                _state.update { it.copy(clients = validClients) }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading clients", e)
                _uiEvent.send(EventUIEvent.ShowSnackbar(e.toUserFriendlyMessage()))
            }
        }
    }

    private fun handleError(error: Throwable) {
        Log.e(TAG, "Error in operation", error)
        val errorMessage = error.toUserFriendlyMessage()
        _state.update {
            it.copy(
                currentOperation = EventOperation.Error(errorMessage),
                showDialog = false
            )
        }
        viewModelScope.launch {
            _uiEvent.send(EventUIEvent.ShowSnackbar(errorMessage))
        }
    }

    private fun clearListeners() {
        viewModelScope.launch {
            try {
                viewModelScope.coroutineContext.cancelChildren()
                _state.update { EventViewState.initial() }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing listeners", e)
            }
        }
    }

    private fun clearDialogState() {
        _state.update {
            it.copy(
                showDialog = false,
                showCreateTypeDialog = false,
                selectedEvent = null,
                currentDate = null,
                currentTime = null,
                selectedClients = emptyList(),
                availablePanels = emptyList(),
                selectedClientForPanels = null,
                newEventTitle = "",
                newEventDescription = "",
                selectedPanelDocName = null,
                selectedEventType = null,
                newTypeError = null
            )
        }
    }

    private fun applyFilterAndSort(events: List<Event>): List<Event> {
        val filteredEvents = events.filter { event ->
            when (currentFilter) {
                is EventFilter.All -> true
                is EventFilter.Programmed -> event.isProgramado
                is EventFilter.Accepted -> event.isAceptado
                is EventFilter.ByClient -> event.clientDocName == (currentFilter as EventFilter.ByClient).clientDocName
                is EventFilter.ByType -> event.type == (currentFilter as EventFilter.ByType).eventType
                is EventFilter.Completed -> event.isFinalizado
            }
        }

        return when (currentSort.field) {
            EventSortOption.SortField.DATE -> {
                if (currentSort.direction == EventSortOption.SortDirection.DESC) {
                    filteredEvents.sortedByDescending { it.dateTime ?: LocalDateTime.MIN }
                } else {
                    filteredEvents.sortedBy { it.dateTime ?: LocalDateTime.MIN }
                }
            }
            EventSortOption.SortField.STATUS -> {
                if (currentSort.direction == EventSortOption.SortDirection.DESC) {
                    filteredEvents.sortedByDescending { it.status }
                } else {
                    filteredEvents.sortedBy { it.status }
                }
            }
            EventSortOption.SortField.TITLE -> {
                if (currentSort.direction == EventSortOption.SortDirection.DESC) {
                    filteredEvents.sortedByDescending { it.title }
                } else {
                    filteredEvents.sortedBy { it.title }
                }
            }
            EventSortOption.SortField.TYPE -> {
                if (currentSort.direction == EventSortOption.SortDirection.DESC) {
                    filteredEvents.sortedByDescending { it.type ?: "" }
                } else {
                    filteredEvents.sortedBy { it.type ?: "" }
                }
            }
            EventSortOption.SortField.LAST_UPDATE -> {
                if (currentSort.direction == EventSortOption.SortDirection.DESC) {
                    filteredEvents.sortedByDescending { it.lastUpdate }
                } else {
                    filteredEvents.sortedBy { it.lastUpdate }
                }
            }
        }
    }

    override fun onCleared() {
        try {
            Log.d(TAG, "onCleared: Limpiando recursos")
            cancelCurrentJob()

        } catch (e: Exception) {
            Log.e(TAG, "Error durante onCleared", e)
        } finally {
            super.onCleared()
            Log.d(TAG, "ViewModel eliminado")
        }
    }
}