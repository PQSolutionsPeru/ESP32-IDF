package com.pqsolutions.hdd_monitor.presentation.theme

import androidx.compose.ui.graphics.Color

object PanelColors {
    val StatusOk = Color(0xFF4CAF50)      // Verde para OK
    val StatusDisc = Color(0xFFE53935)    // Rojo para DISC
    val StatusUnknown = Color(0xFFFFB300) // Amarillo para estados desconocidos
    val StatusOffline = Color(0xFF9E9E9E) // Gris para estado OFFLINE

    val PanelBackgroundOk = Color(0xFFE8F5E9)    // Verde claro para fondo OK
    val PanelBackgroundDisc = Color(0xFFFFEBEE)  // Rojo claro para fondo DISC
    val PanelBackgroundOffline = Color(0xFFEEEEEE)  // Gris claro para fondo OFFLINE

    // Nuevos colores para relays específicos
    val PanelBackgroundAlarm = Color(0xFFFFEBEE)       // Rojo claro para fondo relay Alarma
    val PanelBackgroundWarning = Color(0xFFFFEE58)     // Amarillo más intenso para fondo otros relays
    val PanelBackgroundWarningText = Color(0xFF0D47A1) // Azul oscuro para texto en paneles amarillos

    fun getStatusColor(status: String): Color = when (status) {
        com.pqsolutions.hdd_monitor.util.Constants.Status.OK -> StatusOk
        com.pqsolutions.hdd_monitor.util.Constants.Status.DISC -> StatusDisc
        com.pqsolutions.hdd_monitor.esp32.ESP32Device.STATUS_OFFLINE -> StatusOffline
        else -> StatusUnknown
    }

    fun getPanelBackgroundColor(status: String): Color = when (status) {
        com.pqsolutions.hdd_monitor.util.Constants.Status.OK -> PanelBackgroundOk
        com.pqsolutions.hdd_monitor.util.Constants.Status.DISC -> PanelBackgroundDisc
        com.pqsolutions.hdd_monitor.esp32.ESP32Device.STATUS_OFFLINE -> PanelBackgroundOffline
        else -> PanelBackgroundDisc
    }

    // Nueva función para determinar el color de fondo basado en el tipo de relay
    fun getRelayBackgroundColor(relayName: String): Color = when (relayName) {
        com.pqsolutions.hdd_monitor.data.Panel.RELAY_ALARM -> PanelBackgroundAlarm
        else -> PanelBackgroundWarning
    }

    // Nueva función para determinar el color de texto basado en el tipo de relay
    fun getRelayTextColor(relayName: String): Color = when (relayName) {
        com.pqsolutions.hdd_monitor.data.Panel.RELAY_ALARM -> Color.Unspecified
        else -> PanelBackgroundWarningText
    }
}