package com.pqsolutions.hdd_monitor.esp32

import com.google.firebase.firestore.PropertyName


data class ESP32Device(
    val documentName: String = "",
    val MAC: String = "",
    val IP: String = "",
    val status: String = "WIFI_CONFIG",
    @get:PropertyName("client_id")
    @set:PropertyName("client_id")
    var clientId: String = "",
    @get:PropertyName("panel_id")
    @set:PropertyName("panel_id")
    var panelId: String = "",
    @get:PropertyName("lastUpdate")
    @set:PropertyName("lastUpdate")
    var lastUpdate: com.google.firebase.Timestamp = com.google.firebase.Timestamp.now(),
    // Campos adicionales para enriquecer la información
    val clientName: String = "",
    val panelName: String = ""
) {
    companion object {
        const val STATUS_WIFI_CONFIG = "WIFI_CONFIG"
        const val STATUS_AWAITING_CONFIG = "AWAITING_CONFIG"
        const val STATUS_PENDING_ASSIGNMENT = "PENDING_ASSIGNMENT"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_OFFLINE = "OFFLINE"
        const val STATUS_DISC = "DISC"
        const val STATUS_ONLINE = "ONLINE"
        const val STATUS_CONFIGURED = "ONLINE"
        const val STATUS_ERROR = "ERROR"
    }

    fun toMap(): Map<String, Any> = mapOf(
        "MAC" to MAC,
        "IP" to IP,
        "status" to status,
        "client_id" to clientId,
        "panel_id" to panelId,
        "lastUpdate" to lastUpdate,
        "clientName" to clientName,
        "panelName" to panelName
    )
}