package com.pqsolutions.hdd_monitor.presentation.state

import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.Event
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.UserData
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

sealed class EventOperation {
    object None : EventOperation()
    object Loading : EventOperation()
    data class Success(val message: String? = null) : EventOperation()
    data class Error(val message: String) : EventOperation()

    fun isLoading(): Boolean = this is Loading
    fun isError(): Boolean = this is Error
    fun isSuccess(): Boolean = this is Success
}

sealed class EventFilter {
    object All : EventFilter()
    object Programmed : EventFilter()
    object Accepted : EventFilter()
    object Completed : EventFilter()
    data class ByClient(val clientDocName: String) : EventFilter()
    data class ByType(val eventType: String) : EventFilter()

    override fun toString(): String = when (this) {
        is All -> "Todos"
        is Programmed -> "Programados"
        is Accepted -> "Aceptados"
        is Completed -> "Finalizados"
        is ByClient -> "Cliente: $clientDocName"
        is ByType -> "Tipo: $eventType"
    }
}

data class EventSortOption(
    val field: SortField,
    val direction: SortDirection
) {
    enum class SortField {
        DATE,
        STATUS,
        TITLE,
        TYPE,
        LAST_UPDATE
    }

    enum class SortDirection {
        ASC,
        DESC
    }

    companion object {
        val DEFAULT = EventSortOption(SortField.DATE, SortDirection.DESC)
    }

    override fun toString(): String {
        val fieldStr = when (field) {
            SortField.DATE -> "Fecha"
            SortField.STATUS -> "Estado"
            SortField.TITLE -> "Título"
            SortField.TYPE -> "Tipo"
            SortField.LAST_UPDATE -> "Última actualización"
        }
        val dirStr = when (direction) {
            SortDirection.ASC -> "↑"
            SortDirection.DESC -> "↓"
        }
        return "$fieldStr $dirStr"
    }
}

sealed class EventUIEvent {
    data class ShowSnackbar(val message: String) : EventUIEvent()
    data class Navigate(val route: String) : EventUIEvent()
    object NavigateBack : EventUIEvent()
    data class ShowDialog(val message: String) : EventUIEvent()
    object DismissDialog : EventUIEvent()
    object RefreshData : EventUIEvent()
    data class ShowDatePicker(val currentDate: LocalDate?) : EventUIEvent()
    data class ShowTimePicker(val currentTime: LocalTime?) : EventUIEvent()
    data class ShowCreateTypeDialog(val currentTypes: List<String>) : EventUIEvent()
}

sealed class EventDialogEvent {
    data class TitleChanged(val title: String) : EventDialogEvent()
    data class DescriptionChanged(val description: String) : EventDialogEvent()
    data class DateSelected(val date: LocalDate) : EventDialogEvent()
    data class TimeSelected(val time: LocalTime) : EventDialogEvent()
    data class ClientSelected(val clientDocName: String) : EventDialogEvent()
    data class PanelSelected(val panelDocName: String?) : EventDialogEvent()
    data class MultipleClientsSelected(val clientDocNames: List<String>) : EventDialogEvent()
    data class EventTypeSelected(val eventType: String) : EventDialogEvent()
    data class CreateNewEventType(val type: String) : EventDialogEvent()
    object ShowDatePicker : EventDialogEvent()
    object ShowTimePicker : EventDialogEvent()
    object ShowCreateTypeDialog : EventDialogEvent()
    object Confirm : EventDialogEvent()
    object Dismiss : EventDialogEvent()
}

data class EventViewState(
    // Estado del usuario actual
    val currentUserRole: UserRole? = null,
    val currentUserClientDocName: String? = null,

    // Lista principal de eventos y datos relacionados
    val events: List<Event> = emptyList(),
    val clients: List<Client> = emptyList(),
    val availablePanels: List<Panel> = emptyList(),
    val eventTypes: List<String> = emptyList(),
    val users: List<UserData> = emptyList(),

    // Estados de carga y error
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val currentOperation: EventOperation = EventOperation.None,

    // Estados del diálogo
    val showDialog: Boolean = false,
    val showCreateTypeDialog: Boolean = false,
    val selectedEvent: Event? = null,
    val selectedClients: List<String> = emptyList(),
    val selectedClientForPanels: String? = null,
    val selectedPanelDocName: String? = null,
    val selectedEventType: String? = null,

    // Estados de fecha y hora
    val currentDate: LocalDate? = null,
    val currentTime: LocalTime? = null,

    // Campos para nuevos eventos
    val newEventTitle: String = "",
    val newEventDescription: String = "",

    // Información del panel seleccionado
    val selectedPanelName: String? = null,

    // Estado de última actualización
    val lastUpdate: Long = System.currentTimeMillis(),

    // Estado de manejo de tipos de evento
    val isCreatingNewType: Boolean = false,
    val newTypeError: String? = null
) {
    // Propiedades computadas
    val hasEvents: Boolean
        get() = events.isNotEmpty()

    val hasError: Boolean
        get() = error != null

    val canCreateEvent: Boolean
        get() = when (currentUserRole) {
            UserRole.ADMIN -> selectedClients.isNotEmpty()
            UserRole.USER -> currentUserClientDocName != null
            null -> false
        }

    val hasSelectedClients: Boolean
        get() = selectedClients.isNotEmpty()

    val hasDateTime: Boolean
        get() = currentDate != null && currentTime != null

    val currentDateTime: LocalDateTime?
        get() = if (hasDateTime) {
            LocalDateTime.of(currentDate, currentTime)
        } else null

    val hasValidSelection: Boolean
        get() = hasSelectedClients || selectedEvent != null

    val selectedClientNames: List<String>
        get() = clients
            .filter { it.documentName in selectedClients }
            .map { it.name }

    val hasValidEventType: Boolean
        get() = selectedEventType != null

    val hasValidPanelSelection: Boolean
        get() = selectedPanelDocName != null || selectedPanelName != null

    fun isValid(): Boolean {
        return !isLoading &&
                error == null &&
                currentOperation !is EventOperation.Error &&
                hasValidEventType &&
                (currentUserRole == UserRole.USER || hasSelectedClients)
    }

    companion object {
        fun initial() = EventViewState()

        fun loading() = EventViewState(isLoading = true)

        fun success(events: List<Event>) = EventViewState(
            events = events,
            isLoading = false,
            error = null
        )

        fun error(message: String) = EventViewState(
            isLoading = false,
            error = message
        )
    }

    override fun toString(): String {
        return "EventViewState(" +
                "currentUserRole=$currentUserRole, " +
                "currentUserClientDocName=$currentUserClientDocName, " +
                "events=${events.size}, " +
                "clients=${clients.size}, " +
                "panels=${availablePanels.size}, " +
                "eventTypes=${eventTypes.size}, " +
                "users=${users.size}, " +
                "loading=$isLoading, " +
                "refreshing=$isRefreshing, " +
                "error=$error, " +
                "operation=$currentOperation, " +
                "dialog=$showDialog, " +
                "selectedClients=${selectedClients.size}, " +
                "selectedClient=$selectedClientForPanels, " +
                "selectedEventType=$selectedEventType, " +
                "hasDateTime=$hasDateTime, " +
                "lastUpdate=$lastUpdate, " +
                "isCreatingNewType=$isCreatingNewType" +
                ")"
    }
}