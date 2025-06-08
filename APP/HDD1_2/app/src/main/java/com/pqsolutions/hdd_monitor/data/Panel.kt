package com.pqsolutions.hdd_monitor.data

import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import java.io.Serializable

data class Panel(
    val documentName: String = "",
    val name: String = "",
    val location: String = "",
    val esp32_id: String = "",
    val clientName: String = "",
    val clientDisplayName: String = "",  // NUEVO CAMPO
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
            .joinToString(", ") { it.displayName }

    val hasIssues: Boolean
        get() = !overallStatus

    val activeRelays: List<Relay>
        get() = relays.filter { it.isActive }

    val controllableRelays: List<Relay>
        get() = relays.filter { it.isControllable && it.isActive }

    fun toMap(): Map<String, Any?> = mapOf(
        "documentName" to documentName,
        "name" to name,
        "location" to location,
        "esp32_id" to esp32_id,
        "clientName" to clientName,
        "clientDisplayName" to clientDisplayName,
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

    fun updateRelayConfig(relayName: String, newCustomName: String, isActive: Boolean, isControllable: Boolean): Panel {
        val updatedRelays = relays.map { relay ->
            if (relay.name == relayName) {
                relay.copy(
                    customName = newCustomName.takeIf { it.isNotBlank() },
                    isActive = isActive,
                    isControllable = isControllable
                )
            } else relay
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
            esp32Id: String,
            clientDisplayName: String = ""
        ) = Panel(
            name = name,
            location = location,
            clientName = clientName,
            clientDisplayName = clientDisplayName,
            esp32_id = esp32Id
        )
    }
}