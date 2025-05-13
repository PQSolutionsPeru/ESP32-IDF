package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.pqsolutions.hdd_monitor.util.Constants
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class Notification(
    val documentName: String = "",
    val clientDocName: String = "",
    val panelDocName: String = "",
    val relayName: String = "",
    val message: String = "",
    val date_time: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val eventId: String? = null,
    val eventType: String? = null,
    val status: String? = null,
    val panelName: String? = null,
    val readByAdmin: Boolean = false,
    val readByUser: Boolean = false,
    val lastUpdateTimestamp: Long? = null
) {
    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")
            .withZone(Constants.TimeZone.PERU_ZONE)

        fun createNew(
            clientDocName: String,
            panelDocName: String = "",
            relayName: String = "",
            message: String,
            eventId: String? = null,
            eventType: String? = null,
            status: String? = null,
            panelName: String? = null
        ): Notification {
            val now = LocalDateTime.now(Constants.TimeZone.PERU_ZONE)
            return Notification(
                documentName = "",
                clientDocName = clientDocName,
                panelDocName = panelDocName,
                relayName = relayName,
                message = message.trim(),
                date_time = now.format(DATE_FORMATTER),
                timestamp = now.atZone(Constants.TimeZone.PERU_ZONE).toInstant().toEpochMilli(),
                eventId = eventId,
                eventType = eventType,
                status = status,
                panelName = panelName
            )
        }

        fun fromMap(map: Map<String, Any?>): Notification {
            val dateTimeStr = map["date_time"] as? String ?: ""
            val timestamp = when (val ts = map["timestamp"]) {
                is Long -> ts
                is Number -> ts.toLong()
                else -> try {
                    LocalDateTime.parse(dateTimeStr, DATE_FORMATTER)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                } catch (e: Exception) {
                    System.currentTimeMillis()
                }
            }

            // Mapear correctamente los campos con nombres alternativos
            val eventId = map["eventId"] as? String ?: map["event_id"] as? String
            val eventType = map["eventType"] as? String ?: map["event_type"] as? String
            val status = map["status"] as? String
            val relayName = map["relayName"] as? String ?: map["relay"] as? String
            val panelDocName = map["panelDocName"] as? String ?: map["panel_id"] as? String
            val panelName = map["panelName"] as? String ?: map["panel_name"] as? String
            val clientDocName = map["clientDocName"] as? String ?: map["client_id"] as? String ?: ""
            val lastUpdateTimestamp = map["lastUpdate"] as? Long

            // Log para debug
            if (map["documentName"] != null) {
                Log.d("Notification", "Mapeando notificación: ${map["documentName"]}, event_id: $eventId, relay: $relayName")
            }

            return Notification(
                documentName = map["documentName"] as? String ?: "",
                clientDocName = clientDocName,
                panelDocName = panelDocName ?: "",
                relayName = relayName ?: "",
                message = map["message"] as? String ?: "",
                date_time = dateTimeStr,
                timestamp = timestamp,
                isRead = map["isRead"] as? Boolean ?: false,
                eventId = eventId,
                eventType = eventType,
                status = status,
                panelName = panelName,
                readByAdmin = map["readByAdmin"] as? Boolean ?: false,
                readByUser = map["readByUser"] as? Boolean ?: false,
                lastUpdateTimestamp = lastUpdateTimestamp
            )
        }
    }

    val dateTime: LocalDateTime?
        get() = try {
            LocalDateTime.parse(date_time, DATE_FORMATTER)
        } catch (e: DateTimeParseException) {
            null
        }

    fun getDisplayMessage(): String {
        // Si tenemos un nombre de panel y el mensaje contiene el ID del panel, reemplazamos el ID por el nombre
        return if (!panelName.isNullOrEmpty() &&
            panelDocName.isNotEmpty() &&
            message.contains(panelDocName)) {
            message.replace(panelDocName, panelName)
        } else {
            message
        }
    }

    fun isValid(): Boolean {
        // Para notificaciones de sistema, ser más permisivo
        if (documentName.startsWith("notif_")) {
            return true
        }

        // Para notificaciones de tipo evento que empiezan con notification_
        if (documentName.startsWith("notification_")) {
            return true
        }

        // Para notificaciones de tipo relay que empiezan con relay_
        if (documentName.startsWith("relay_")) {
            return true
        }

        // Validación estándar para otros casos
        return validateDocumentNames() &&
                message.isNotBlank() &&
                date_time.isNotBlank() &&
                (isRelayNotification() || isEventNotification())
    }

    private fun validateDocumentNames(): Boolean {
        // Ser más permisivo con los prefijos
        if (documentName.isEmpty()) {
            return false
        }

        if (clientDocName.isEmpty()) {
            return false
        }

        // Si es una notificación de sistema, de evento o de relay, considerarla válida
        if (documentName.startsWith("notif_") ||
            documentName.startsWith("notification_") ||
            documentName.startsWith("relay_")) {
            return true
        }

        // Comprobar prefijos estándar de manera más permisiva
        val validDocument = documentName.isEmpty() ||
                documentName.startsWith(DocumentPrefixes.NOTIFICATION) ||
                documentName.contains("notification")

        val validClient = clientDocName.startsWith(DocumentPrefixes.CLIENT) ||
                clientDocName.contains("client_")

        val validPanel = panelDocName.isEmpty() ||
                panelDocName.startsWith(DocumentPrefixes.PANEL) ||
                panelDocName.contains("panel_")

        return validDocument && validClient && validPanel
    }

    fun isEventNotification(): Boolean {
        // Considerar una notificación como de evento si:
        // 1. Tiene eventId o eventType definido
        // 2. Su nombre de documento contiene "notification_"
        // 3. Tiene un campo type con valor "event"
        return eventId != null ||
                eventType != null ||
                documentName.startsWith("notification_") ||
                documentName.contains("event_")
    }

    fun isRelayNotification(): Boolean {
        // Considerar una notificación como de relay si:
        // 1. Tiene relayName o panelDocName definido
        // 2. Su nombre de documento contiene "relay_"
        return relayName.isNotBlank() ||
                panelDocName.isNotBlank() ||
                documentName.startsWith("relay_") ||
                documentName.contains("relay")
    }

    fun sortByMostRecent(notifications: List<Notification>): List<Notification> {
        return notifications.sortedByDescending {
            // Primero intentar por timestamp, si falla usar dateTime
            try {
                it.timestamp
            } catch (e: Exception) {
                it.dateTime?.atZone(Constants.TimeZone.PERU_ZONE)?.toInstant()?.toEpochMilli()
                    ?: 0L
            }
        }
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "documentName" to documentName,
            "clientDocName" to clientDocName,
            "panelDocName" to panelDocName,
            "relayName" to relayName,
            "message" to message,
            "date_time" to date_time,
            "timestamp" to timestamp,
            "isRead" to isRead,
            "eventId" to eventId,
            "eventType" to eventType,
            "status" to status,
            "panelName" to panelName,
            "readByAdmin" to readByAdmin,
            "readByUser" to readByUser
        ).filterValues { it != null }
    }

    fun toLogString(): String = buildString {
        append("Notification(")
        append("documentName='$documentName', ")
        append("clientDocName='$clientDocName', ")
        if (isEventNotification()) {
            append("eventId='$eventId', ")
            append("eventType='$eventType', ")
            append("status='$status', ")
        } else {
            append("panelDocName='$panelDocName', ")
            append("relayName='$relayName', ")
        }
        append("message='${message.take(30)}${if (message.length > 30) "..." else ""}', ")
        append("date_time='$date_time', ")
        append("timestamp=$timestamp")
        append(")")
    }

    fun isRecent(): Boolean {
        val notificationDateTime = dateTime ?: return false
        val hoursAgo = java.time.Duration.between(notificationDateTime, LocalDateTime.now()).toHours()
        return hoursAgo < 24
    }
}