package com.pqsolutions.hdd_monitor.data

import com.google.firebase.firestore.PropertyName
import com.pqsolutions.hdd_monitor.domain.model.EventStatus
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class Event(
    val documentName: String = "",
    val clientDocName: String = "",
    val title: String = "",
    val text: String = "",
    @get:PropertyName("status")
    @set:PropertyName("status")
    var status: String = EventStatus.STATUS_PROGRAMADO,
    val date_time: String = "",
    val lastUpdate: String = "",
    val type: String? = null,
    val createdByAccountId: String? = null,
    val createdByAccountRole: String? = null,
    val panelDocName: String? = null,
    val panelName: String? = null,
    val acceptedByAccountId: String? = null,
    val acceptedAt: String? = null,
    val finishedByAccountId: String? = null,
    val finishedAt: String? = null,
    val reopenedByAccountId: String? = null,
    val reopenedAt: String? = null,
    val isRead: Boolean = false,
    val needsApproval: Boolean = true
) {
    companion object {
        private val PERU_ZONE_ID = ZoneId.of("America/Lima")
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")

        fun createNew(
            clientDocName: String,
            panelDocName: String?,
            panelName: String?,
            title: String,
            text: String,
            dateTime: LocalDateTime,
            type: String,
            createdByAccountId: String,
            createdByAccountRole: String
        ): Event {
            val normalizedRole = if (createdByAccountRole.equals("admin", ignoreCase = true)) "admin" else "user"
            // Usar zona horaria de Perú para la fecha actual
            val now = LocalDateTime.now(PERU_ZONE_ID).format(DATE_FORMATTER)

            return Event(
                documentName = "",
                clientDocName = clientDocName,
                panelDocName = panelDocName,
                panelName = panelName,
                title = title.trim(),
                text = text.trim(),
                date_time = dateTime.format(DATE_FORMATTER),
                lastUpdate = now,
                status = EventStatus.STATUS_PROGRAMADO,
                type = type,
                createdByAccountId = createdByAccountId,
                createdByAccountRole = normalizedRole,
                isRead = false,
                needsApproval = true
            )
        }

        fun fromMap(map: Map<String, Any?>): Event {
            return Event(
                documentName = map["documentName"] as? String ?: "",
                clientDocName = map["clientDocName"] as? String ?: "",
                title = map["title"] as? String ?: "",
                text = map["text"] as? String ?: "",
                status = map["status"] as? String ?: EventStatus.STATUS_PROGRAMADO,
                date_time = map["date_time"] as? String ?: "",
                lastUpdate = map["lastUpdate"] as? String ?: "",
                type = map["type"] as? String,
                createdByAccountId = map["createdByAccountId"] as? String,
                createdByAccountRole = map["createdByAccountRole"] as? String,
                panelDocName = map["panelDocName"] as? String,
                panelName = map["panelName"] as? String,
                acceptedByAccountId = map["acceptedByAccountId"] as? String,
                acceptedAt = map["acceptedAt"] as? String,
                finishedByAccountId = map["finishedByAccountId"] as? String,
                finishedAt = map["finishedAt"] as? String,
                reopenedByAccountId = map["reopenedByAccountId"] as? String,
                reopenedAt = map["reopenedAt"] as? String,
                isRead = map["isRead"] as? Boolean ?: false,
                needsApproval = map["needsApproval"] as? Boolean ?: true
            )
        }
    }

    val isProgramado: Boolean
        get() = status == EventStatus.STATUS_PROGRAMADO

    val needAdminAcceptance: Boolean
        get() = createdByAccountRole == "user" && acceptedByAccountId == null && needsApproval

    val needUserAcceptance: Boolean
        get() = createdByAccountRole == "admin" && acceptedByAccountId == null && needsApproval

    val isAceptado: Boolean
        get() = status == EventStatus.STATUS_ACEPTADO && acceptedByAccountId != null

    val isFinalizado: Boolean
        get() = status == EventStatus.STATUS_FINALIZADO

    val isEditable: Boolean
        get() = isProgramado

    val isFinishable: Boolean
        get() = isAceptado // Cualquiera puede finalizar si está aceptado

    val dateTime: LocalDateTime?
        get() = try {
            LocalDateTime.parse(date_time, DATE_FORMATTER)
        } catch (e: DateTimeParseException) {
            null
        }

    fun isValid(): Boolean {
        return title.isNotBlank() &&
                text.isNotBlank() &&
                date_time.isNotBlank() &&
                type != null &&
                type.isNotBlank() &&
                EventStatus.isValidStatus(status) &&
                validateDocumentNames()
    }

    fun validateDocumentNames(): Boolean {
        return (documentName.isEmpty() || documentName.startsWith(DocumentPrefixes.EVENT)) &&
                clientDocName.startsWith(DocumentPrefixes.CLIENT) &&
                (panelDocName == null || panelDocName.startsWith(DocumentPrefixes.PANEL))
    }

    fun getFormattedDateTime(): String = date_time

    fun accept(accountId: String, isAdmin: Boolean, timestamp: String = LocalDateTime.now().format(DATE_FORMATTER)): Event {
        val shouldAccept = when {
            isAdmin && createdByAccountRole == UserRole.USER.toString() && needAdminAcceptance -> true
            !isAdmin && createdByAccountRole == UserRole.ADMIN.toString() && needUserAcceptance -> true
            else -> false
        }

        return if (shouldAccept) {
            copy(
                status = EventStatus.STATUS_ACEPTADO,
                lastUpdate = timestamp,
                acceptedAt = timestamp,
                acceptedByAccountId = accountId,
                isRead = false
            )
        } else this
    }

    fun finalize(accountId: String, timestamp: String = LocalDateTime.now().format(DATE_FORMATTER)): Event {
        return if (isAceptado) {
            copy(
                status = EventStatus.STATUS_FINALIZADO,
                lastUpdate = timestamp,
                finishedAt = timestamp,
                finishedByAccountId = accountId,
                isRead = false
            )
        } else this
    }

    fun reopen(accountId: String, timestamp: String = LocalDateTime.now().format(DATE_FORMATTER)): Event {
        return if (isFinalizado) {
            copy(
                status = EventStatus.STATUS_ACEPTADO,
                lastUpdate = timestamp,
                reopenedAt = timestamp,
                reopenedByAccountId = accountId,
                isRead = false
            )
        } else this
    }

    fun markAsRead(): Event {
        return copy(isRead = true)
    }

    fun update(
        title: String,
        text: String,
        dateTime: LocalDateTime,
        panelDocName: String? = this.panelDocName,
        panelName: String? = this.panelName,
        type: String? = this.type
    ): Event? {
        if (!isProgramado) return null

        val now = LocalDateTime.now().format(DATE_FORMATTER)
        return copy(
            title = title.trim(),
            text = text.trim(),
            date_time = dateTime.format(DATE_FORMATTER),
            lastUpdate = now,
            panelDocName = panelDocName,
            panelName = panelName,
            type = type,
            isRead = false
        )
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "documentName" to documentName,
            "clientDocName" to clientDocName,
            "title" to title,
            "text" to text,
            "status" to status,
            "date_time" to date_time,
            "lastUpdate" to lastUpdate,
            "type" to type,
            "createdByAccountId" to createdByAccountId,
            "createdByAccountRole" to createdByAccountRole,
            "panelDocName" to panelDocName,
            "panelName" to panelName,
            "acceptedByAccountId" to acceptedByAccountId,
            "acceptedAt" to acceptedAt,
            "finishedByAccountId" to finishedByAccountId,
            "finishedAt" to finishedAt,
            "reopenedByAccountId" to reopenedByAccountId,
            "reopenedAt" to reopenedAt,
            "isRead" to isRead,
            "needsApproval" to needsApproval
        ).filterValues { it != null }
    }

    fun toLogString(): String = buildString {
        append("Event(")
        append("documentName='$documentName', ")
        append("clientDocName='$clientDocName', ")
        append("title='$title', ")
        append("status='$status', ")
        append("isProgramado=$isProgramado, ")
        append("date_time='$date_time', ")
        append("lastUpdate='$lastUpdate', ")
        append("type='$type', ")
        if (panelDocName != null) append("panelDocName='$panelDocName', ")
        if (panelName != null) append("panelName='$panelName', ")
        if (acceptedByAccountId != null) append("acceptedByAccountId='$acceptedByAccountId', ")
        if (acceptedAt != null) append("acceptedAt='$acceptedAt', ")
        if (finishedByAccountId != null) append("finishedByAccountId='$finishedByAccountId', ")
        if (finishedAt != null) append("finishedAt='$finishedAt', ")
        if (reopenedByAccountId != null) append("reopenedByAccountId='$reopenedByAccountId', ")
        if (reopenedAt != null) append("reopenedAt='$reopenedAt', ")
        append("isRead=$isRead, ")
        append("needsApproval=$needsApproval, ")
        if (createdByAccountId != null) append("createdByAccountId='$createdByAccountId', ")
        if (createdByAccountRole != null) append("createdByAccountRole='$createdByAccountRole', ")
        append(")")
    }
}