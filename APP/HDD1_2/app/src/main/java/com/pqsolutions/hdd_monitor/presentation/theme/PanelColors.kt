package com.pqsolutions.hdd_monitor.presentation.theme

import androidx.compose.ui.graphics.Color

object PanelColors {
    val StatusOk = Color(0xFF4CAF50)
    val StatusDisc = Color(0xFFE53935)
    val StatusUnknown = Color(0xFFFFB300)
    val StatusOffline = Color(0xFF9E9E9E)

    val PanelBackgroundOk = Color(0xFFE8F5E9)
    val PanelBackgroundDisc = Color(0xFFFFEBEE)
    val PanelBackgroundOffline = Color(0xFFEEEEEE)

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
}