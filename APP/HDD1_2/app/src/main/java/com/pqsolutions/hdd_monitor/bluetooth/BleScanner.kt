package com.pqsolutions.hdd_monitor.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bleScanner = bluetoothAdapter?.bluetoothLeScanner

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _foundDevices = MutableStateFlow<Set<BluetoothDevice>>(emptySet())
    val foundDevices: StateFlow<Set<BluetoothDevice>> = _foundDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (hasBluetoothPermissions()) {
                val deviceName = device.name
                // Agregar log para todos los dispositivos encontrados
                Log.d(TAG, "Dispositivo encontrado - Nombre: $deviceName, MAC: ${device.address}, RSSI: ${result.rssi}")

                // Hacer la verificación más flexible
                if (deviceName != null && (deviceName.contains("ESP32", ignoreCase = true))) {
                    val currentDevices = _foundDevices.value.toMutableSet()
                    currentDevices.add(device)
                    _foundDevices.value = currentDevices
                    Log.d(TAG, "ESP32 encontrado y agregado: $deviceName")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Escaneo ya en progreso"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Error registrando aplicación para escaneo"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                    "Bluetooth LE no soportado"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                    "Error interno de escaneo"
                else -> "Error desconocido: $errorCode"
            }
            Log.e(TAG, "Error de escaneo: $errorMsg")
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value || !hasBluetoothPermissions()) return

        try {
            _foundDevices.value = emptySet()
            _isScanning.value = true

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)  // Menos agresivo pero suficiente
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)  // Mejor para dispositivos estables
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)  // Uno es suficiente
                .setReportDelay(0L)
                .build()

            // Agregar filtro específico para ESP32
            val scanFilter = ScanFilter.Builder()
                .build()

            bleScanner?.startScan(listOf(scanFilter), settings, scanCallback)
            Log.d(TAG, "Escaneo BLE iniciado con configuración actualizada")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escaneo", e)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo escaneo", e)
        } finally {
            _isScanning.value = false
        }
    }

    fun clearDevices() {
        _foundDevices.value = emptySet()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}