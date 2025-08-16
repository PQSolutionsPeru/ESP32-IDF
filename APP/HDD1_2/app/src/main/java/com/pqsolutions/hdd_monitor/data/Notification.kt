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
    val lastUpdateTimestamp: Long? = null,
    val type: String? = null,
    val connectivityType: String? = null,
    val ssid: String? = null,
    val timeRange: String? = null,
    val title: String? = null
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
            panelName: String? = null,
            type: String? = null,
            connectivityType: String? = null,
            ssid: String? = null,
            timeRange: String? = null,
            title: String? = null
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
                panelName = panelName,
                type = type,
                connectivityType = connectivityType,
                ssid = ssid,
                timeRange = timeRange,
                title = title
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

            val eventId = map["eventId"] as? String ?: map["event_id"] as? String
            val eventType = map["eventType"] as? String ?: map["event_type"] as? String
            val status = map["status"] as? String
            val relayName = map["relayName"] as? String ?: map["relay"] as? String
            val panelDocName = map["panelDocName"] as? String ?: map["panel_id"] as? String
            val panelName = map["panelName"] as? String ?: map["panel_name"] as? String
            val clientDocName = map["clientDocName"] as? String ?: map["client_id"] as? String ?: ""
            val lastUpdateTimestamp = map["lastUpdate"] as? Long

            val type = map["type"] as? String
            val connectivityType = map["connectivity_type"] as? String ?: map["connectivityType"] as? String
            val ssid = map["ssid"] as? String
            val timeRange = map["time_range"] as? String ?: map["timeRange"] as? String
            val title = map["title"] as? String

            if (map["documentName"] != null) {
                Log.d("Notification", "Mapeando notificación: ${map["documentName"]}, type: $type, connectivity_type: $connectivityType")
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
                lastUpdateTimestamp = lastUpdateTimestamp,
                type = type,
                connectivityType = connectivityType,
                ssid = ssid,
                timeRange = timeRange,
                title = title
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
        return if (!panelName.isNullOrEmpty() &&
            panelDocName.isNotEmpty() &&
            message.contains(panelDocName)) {
            message.replace(panelDocName, panelName)
        } else {
            message
        }
    }

    fun isConnectivityNotification(): Boolean {
        return type == "connectivity" ||
                connectivityType != null ||
                documentName.contains("wifi_") ||
                documentName.contains("inet_") ||
                message.contains("desconectó de la red") ||
                message.contains("sin internet") ||
                message.contains("reconectó") ||
                message.contains("recuperó conectividad")
    }

    fun isValid(): Boolean {
        if (documentName.startsWith("notif_")) {
            return true
        }

        if (documentName.startsWith("notification_")) {
            return true
        }

        if (documentName.startsWith("relay_")) {
            return true
        }

        if (isConnectivityNotification()) {
            return message.isNotBlank() && date_time.isNotBlank()
        }

        return validateDocumentNames() &&
                message.isNotBlank() &&
                date_time.isNotBlank() &&
                (isRelayNotification() || isEventNotification() || isConnectivityNotification())
    }

    private fun validateDocumentNames(): Boolean {
        if (documentName.isEmpty()) {
            return false
        }

        if (clientDocName.isEmpty()) {
            return false
        }

        if (documentName.startsWith("notif_") ||
            documentName.startsWith("notification_") ||
            documentName.startsWith("relay_") ||
            documentName.startsWith("wifi_") ||
            documentName.startsWith("inet_")) {
            return true
        }

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
        return eventId != null ||
                eventType != null ||
                documentName.startsWith("notification_") ||
                documentName.contains("event_") ||
                type == "event"
    }

    fun isRelayNotification(): Boolean {
        return relayName.isNotBlank() ||
                panelDocName.isNotBlank() ||
                documentName.startsWith("relay_") ||
                documentName.contains("relay") ||
                type == "relay"
    }

    fun getDisplayTitle(): String {
        return when {
            isConnectivityNotification() -> {
                title ?: when {
                    connectivityType?.contains("disconnection") == true ||
                            connectivityType?.contains("lost") == true -> "Pérdida de Conectividad"
                    connectivityType?.contains("reconnected") == true ||
                            connectivityType?.contains("recovered") == true -> "Conectividad Recuperada"
                    else -> "Conectividad"
                }
            }
            isEventNotification() -> "Evento ${eventType ?: "desconocido"}"
            isRelayNotification() -> "Actualización de Panel"
            else -> "Notificación del Sistema"
        }
    }

    fun sortByMostRecent(notifications: List<Notification>): List<Notification> {
        return notifications.sortedByDescending {
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
            "readByUser" to readByUser,
            "type" to type,
            "connectivity_type" to connectivityType,
            "ssid" to ssid,
            "time_range" to timeRange,
            "title" to title
        ).filterValues { it != null }
    }

    fun toLogString(): String = buildString {
        append("Notification(")
        append("documentName='$documentName', ")
        append("clientDocName='$clientDocName', ")
        when {
            isConnectivityNotification() -> {
                append("type='connectivity', ")
                append("connectivity_type='$connectivityType', ")
                append("ssid='$ssid', ")
                append("time_range='$timeRange', ")
            }
            isEventNotification() -> {
                append("eventId='$eventId', ")
                append("eventType='$eventType', ")
                append("status='$status', ")
            }
            else -> {
                append("panelDocName='$panelDocName', ")
                append("relayName='$relayName', ")
            }
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