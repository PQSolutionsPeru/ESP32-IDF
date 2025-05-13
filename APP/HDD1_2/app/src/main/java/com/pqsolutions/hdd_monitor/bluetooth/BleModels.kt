package com.pqsolutions.hdd_monitor.bluetooth

data class WifiConfig(
    val ssid: String,
    val password: String
)

data class BleDevice(
    val address: String,
    val name: String
)

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Connecting : BleConnectionState()
    object Connected : BleConnectionState()
    object ESP32Configuring : BleConnectionState()
    object ESP32Ready : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}