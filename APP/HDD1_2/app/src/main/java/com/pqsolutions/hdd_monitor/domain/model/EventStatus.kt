package com.pqsolutions.hdd_monitor.domain.model

import androidx.compose.ui.graphics.Color
import com.google.firebase.firestore.PropertyName
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.util.Constants.Status
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object EventStatus {
    // Constantes para estados
    const val STATUS_PROGRAMADO = Status.STATUS_PROGRAMADO
    const val STATUS_ACEPTADO = Status.STATUS_ACEPTADO
    const val STATUS_FINALIZADO = "FINALIZADO"  // Nuevo estado

    // Zona horaria de Perú
    private val PERU_ZONE_ID = ZoneId.of("America/Lima")

    // Formatter para fechas
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")

    // Función para validar estados
    fun isValidStatus(status: String): Boolean =
        status.uppercase() in listOf(STATUS_PROGRAMADO, STATUS_ACEPTADO, STATUS_FINALIZADO)

    // Función para normalizar estados
    fun normalize(status: String): String =
        when (status.uppercase()) {
            STATUS_PROGRAMADO -> STATUS_PROGRAMADO
            STATUS_ACEPTADO -> STATUS_ACEPTADO
            STATUS_FINALIZADO -> STATUS_FINALIZADO
            else -> STATUS_PROGRAMADO
        }

    // Función para obtener color según estado
    fun getStatusColor(status: String): StatusColor =
        when (normalize(status)) {
            STATUS_PROGRAMADO -> StatusColor.Programado
            STATUS_ACEPTADO -> StatusColor.Aceptado
            STATUS_FINALIZADO -> StatusColor.Finalizado
            else -> StatusColor.Default
        }

    // Función para validar transición de estados
    fun isValidTransition(currentStatus: String, newStatus: String): Boolean {
        if (!isValidStatus(currentStatus) || !isValidStatus(newStatus)) return false

        return when (normalize(currentStatus)) {
            STATUS_PROGRAMADO -> newStatus == STATUS_ACEPTADO
            STATUS_ACEPTADO -> newStatus == STATUS_FINALIZADO
            STATUS_FINALIZADO -> false
            else -> false
        }
    }

    // Función para obtener mensaje de estado usando string resources
    fun getStatusStringResource(status: String): Int =
        when (normalize(status)) {
            STATUS_PROGRAMADO -> R.string.event_status_programmed
            STATUS_ACEPTADO -> R.string.event_status_accepted
            STATUS_FINALIZADO -> R.string.status_completed
            else -> R.string.error
        }

    // Función para obtener mensaje de estado (fallback si no hay contexto)
    fun getStatusMessage(status: String): String =
        when (normalize(status)) {
            STATUS_PROGRAMADO -> "Programado"
            STATUS_ACEPTADO -> "Aceptado"
            STATUS_FINALIZADO -> "Finalizado"
            else -> "Estado desconocido"
        }

    // Función para verificar si un estado permite edición
    fun isEditable(status: String): Boolean =
        normalize(status) == STATUS_PROGRAMADO

    // Función para obtener siguiente estado válido
    fun getNextStatus(currentStatus: String): String? =
        when (normalize(currentStatus)) {
            STATUS_PROGRAMADO -> STATUS_ACEPTADO
            STATUS_ACEPTADO -> STATUS_FINALIZADO
            else -> null
        }

    // Función para obtener la fecha/hora actual en zona horaria de Perú
    fun getCurrentDateTime(): LocalDateTime = LocalDateTime.now(PERU_ZONE_ID)

    // Clase para manejar transiciones de estado con metadata
    data class StatusTransition(
        val fromStatus: String,
        val toStatus: String,
        @get:PropertyName("timestamp")
        val timestamp: String = getCurrentDateTime().format(DATE_FORMATTER),
        @get:PropertyName("userDocName")
        val userDocName: String? = null,
        @get:PropertyName("userRole")
        val userRole: String? = null,
        @get:PropertyName("isRead")
        val isRead: Boolean = false
    ) {
        fun isValid(): Boolean =
            isValidStatus(fromStatus) &&
                    isValidStatus(toStatus) &&
                    isValidTransition(fromStatus, toStatus)

        override fun toString(): String =
            "Transición de $fromStatus a $toStatus por $userDocName ($userRole) en $timestamp"
    }
}

// Colores para los diferentes estados
enum class StatusColor(val value: Long, val composableColor: Color) {
    Programado(0xFFE53935, Color(0xFFE53935)),  // Rojo para programado
    Aceptado(0xFF2196F3, Color(0xFF2196F3)),    // Azul para aceptado
    Finalizado(0xFF4CAF50, Color(0xFF4CAF50)),  // Verde para finalizado
    Default(0xFF757575, Color(0xFF757575));     // Gris para estado por defecto

    companion object {
        fun fromStatus(status: String): StatusColor =
            EventStatus.getStatusColor(status)
    }
}

// Extensiones de String para estados
fun String.toEventStatus(): String = EventStatus.normalize(this)
fun String.isValidEventStatus(): Boolean = EventStatus.isValidStatus(this)
fun String.getStatusColor(): StatusColor = EventStatus.getStatusColor(this)
fun String.isEditableStatus(): Boolean = EventStatus.isEditable(this)
fun String.getStatusMessage(): String = EventStatus.getStatusMessage(this)
fun String.getStatusStringResource(): Int = EventStatus.getStatusStringResource(this)