package com.pqsolutions.hdd_monitor.data

import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import java.io.Serializable

data class Panel(
    val documentName: String = "",
    val name: String = "",
    val location: String = "",
    val esp32_id: String = "",
    val clientName: String = "",
    val lastUpdate: Long = System.currentTimeMillis(),
    var relays: List<Relay> = listOf(
        Relay(RELAY_ALARM, STATUS_DISC),
        Relay(RELAY_PROBLEM, STATUS_DISC),
        Relay(RELAY_SUPERVISION, STATUS_DISC)
    ),
    var esp32Status: String = ESP32Device.STATUS_OFFLINE


) : Serializable {

    val overallStatus: Boolean
        get() = !isESP32Offline() && relays.none { it.status == STATUS_DISC }

    val relaysInDisc: String
        get() = relays.filter { it.status == STATUS_DISC }
            .joinToString(", ") { it.name }

    val hasIssues: Boolean
        get() = !overallStatus

    fun toMap(): Map<String, Any?> = mapOf(
        "documentName" to documentName,
        "name" to name,
        "location" to location,
        "esp32_id" to esp32_id,
        "clientName" to clientName,
        "lastUpdate" to lastUpdate
    )

    fun isESP32Offline(): Boolean = esp32Status == ESP32Device.STATUS_OFFLINE

    fun updateRelay(relayName: String, newStatus: String): Panel {
        val updatedRelays = relays.map { relay ->
            if (relay.name == relayName) relay.copy(status = newStatus)
            else relay
        }
        return copy(relays = updatedRelays)
    }

    fun isValid(): Boolean = name.isNotBlank() && location.isNotBlank()

    fun hasValidESP32(): Boolean = esp32_id.isNotEmpty()

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_DISC = "DISC"

        const val RELAY_ALARM = "Alarma"
        const val RELAY_PROBLEM = "Problema"
        const val RELAY_SUPERVISION = "Supervision"

        fun createNew(
            name: String,
            location: String,
            clientName: String,
            esp32Id: String
        ) = Panel(
            name = name,
            location = location,
            clientName = clientName,
            esp32_id = esp32Id
        )
    }
}

data class Relay(
    val name: String,
    val status: String,
    val date_time: String? = null
) : Serializable {

    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name,
        "status" to status,
        "date_time" to date_time
    )

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_DISC = "DISC"

        fun fromMap(map: Map<String, Any?>): Relay = Relay(
            name = map["name"]?.toString() ?: "",
            status = map["status"]?.toString() ?: STATUS_DISC,
            date_time = map["date_time"]?.toString()
        )
    }
}