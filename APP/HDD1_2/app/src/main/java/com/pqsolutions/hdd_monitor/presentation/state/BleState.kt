package com.pqsolutions.hdd_monitor.presentation.state

import android.bluetooth.BluetoothDevice
import com.pqsolutions.hdd_monitor.esp32.ESP32Device

sealed class BleState {
    object Initial : BleState()
    object Scanning : BleState()
    object Connecting : BleState()
    object Connected : BleState()
    object WifiConfigReceived : BleState()
    object WifiConfiguring : BleState()
    data class WifiConfigured(val esp32Device: ESP32Device) : BleState()
    object WaitingForRunningMode : BleState()
    data class SelectingClient(val esp32Device: ESP32Device) : BleState()
    data class CreatingPanel(val esp32Device: ESP32Device, val clientId: String) : BleState()
    data class ConfigurationSuccess(val esp32Device: ESP32Device) : BleState()
    object Disconnected : BleState()
    data class ConfigurationError(val message: String) : BleState()
    data class RequiresPermission(
        val permissions: List<String>,
        val onPermissionGranted: () -> Unit
    ) : BleState()
    data class Error(val message: String) : BleState()

    // Nuevos estados para dispositivos no asignados
    object LoadingUnassignedDevices : BleState()
    object NoUnassignedDevices : BleState()
    data class UnassignedDevicesFound(val devices: List<ESP32Device>) : BleState()

    // Estado para elección de método de configuración
    object ConfigMethodSelection : BleState()

    data class DeviceFound(
        val esp32Device: ESP32Device,
        val clientName: String,
        val panelName: String,
        val location: String
    ) : BleState()

    data class WaitingDeviceConfirmation(
        val esp32Device: ESP32Device,
        val clientName: String,
        val panelName: String,
        val location: String
    ) : BleState()
}