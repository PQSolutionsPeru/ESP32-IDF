package com.pqsolutions.hdd_monitor.util

import com.pqsolutions.hdd_monitor.data.Event
import com.pqsolutions.hdd_monitor.domain.model.EventStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Extensiones para String
fun String.toEventStatus(): String = EventStatus.normalize(this)

fun String.isValidDateTime(): Boolean {
    return try {
        SimpleDateFormat(Constants.Patterns.DATE_TIME_PATTERN, Locale.getDefault())
            .parse(this) != null
    } catch (e: Exception) {
        false
    }
}

// Extensiones para Event
fun Event.canBeModified(): Boolean =
    this.status == EventStatus.STATUS_PROGRAMADO

fun Event.formatDateTime(): String {
    return try {
        val inputFormat = SimpleDateFormat(
            Constants.Patterns.DATE_TIME_PATTERN,
            Locale.getDefault()
        )
        val date = inputFormat.parse(this.date_time) ?: return this.date_time
        inputFormat.format(date)
    } catch (e: Exception) {
        this.date_time
    }
}

// Extensiones para Date
fun Date.toFormattedString(): String {
    return SimpleDateFormat(
        Constants.Patterns.DATE_TIME_PATTERN,
        Locale.getDefault()
    ).format(this)
}

// Extensiones para Long (timestamps)
fun Long.toFormattedDateTime(): String {
    return SimpleDateFormat(
        Constants.Patterns.DATE_TIME_PATTERN,
        Locale.getDefault()
    ).format(Date(this))
}

// Extensiones para validaciones
fun String?.isValidTitle(): Boolean =
    !this.isNullOrBlank() &&
            this.length <= Constants.Defaults.MAX_TITLE_LENGTH

fun String?.isValidDescription(): Boolean =
    !this.isNullOrBlank() &&
            this.length <= Constants.Defaults.MAX_DESCRIPTION_LENGTH

// Extensiones para WhatsApp
fun String.toWhatsAppUrl(): String {
    return "${Constants.WHATSAPP_BASE_URL}${Constants.ADMIN_WHATSAPP_NUMBER}" +
            "?text=${this.encodeForWhatsApp()}"
}

fun String.encodeForWhatsApp(): String {
    return java.net.URLEncoder.encode(this, "UTF-8")
}

// Extensiones para manejo de errores
fun Throwable.toUserFriendlyMessage(): String {
    return when (this) {
        is java.net.UnknownHostException -> "No hay conexión a Internet"
        is java.net.SocketTimeoutException -> "La conexión ha tardado demasiado"
        is java.io.IOException -> "Error de conexión"
        is IllegalArgumentException -> message ?: "Datos inválidos"
        is IllegalStateException -> message ?: "Operación no permitida"
        else -> message ?: "Error desconocido"
    }
}

// Extensiones para logging
fun Any.className(): String = this::class.java.simpleName

// Extensiones para UI
fun Boolean.toVisibility(): Float = if (this) 1f else 0f

// Extensiones para estados
fun String.isProgramado(): Boolean =
    this == EventStatus.STATUS_PROGRAMADO

fun String.isAceptado(): Boolean =
    this == EventStatus.STATUS_ACEPTADO

// Extensiones para rutas de Firestore
fun String.toEventPath(clientDocName: String): String =
    "${Constants.FirestorePaths.getClientEventsPath(clientDocName)}/$this"

fun String.toNotificationPath(clientDocName: String): String =
    "${Constants.FirestorePaths.getClientNotificationsPath(clientDocName)}/$this"

// Extensiones para colecciones
fun <T> List<T>.toEventuallyConsistentList(): List<T> {
    return this.distinctBy {
        when (it) {
            is Event -> it.documentName
            else -> it.hashCode()
        }
    }
}

// Extensiones para validación de documentos
fun String.isValidDocumentName(): Boolean {
    return isNotBlank() &&
            matches(Regex("^[a-zA-Z0-9_-]+$")) &&
            length <= 100
}

// Extensiones para paths de Firestore
fun String.extractClientDocNameFromPath(): String? {
    return split("/")
        .find { it.startsWith("client_") }
}

fun String.extractEventDocNameFromPath(): String? {
    return split("/")
        .find { it.startsWith("event_") }
}

fun String.extractPanelDocNameFromPath(): String? {
    return split("/")
        .find { it.startsWith("panel_") }
}

// Extensiones para fechas y horas formateadas
fun String.toFirestoreDateTime(): String {
    return try {
        val inputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val outputFormat = SimpleDateFormat(Constants.Patterns.DATE_TIME_PATTERN, Locale.getDefault())
        val date = inputFormat.parse(this)
        if (date != null) {
            outputFormat.format(date)
        } else {
            this
        }
    } catch (e: Exception) {
        this
    }
}