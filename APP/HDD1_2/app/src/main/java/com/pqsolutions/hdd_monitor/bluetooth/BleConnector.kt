package com.pqsolutions.hdd_monitor.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pqsolutions.hdd_monitor.presentation.state.BleState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleConnector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var currentConnectedDevice: BluetoothDevice? = null
    private var connectRetryCount = 0
    private val MAX_CONNECT_RETRIES = 3

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val responseBuffer = StringBuilder()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    // Error en la conexión
                    Log.e(TAG, "Error en la conexión GATT. Estado: $status")
                    if (status == 62) {  // Código específico para timeout
                        Log.e(TAG, "Timeout de conexión GATT detectado")
                        handleConnectionError("Timeout en la conexión", currentConnectedDevice)
                    } else {
                        disconnect()
                        _connectionState.value = BleConnectionState.Error("Error de conexión GATT: $status")
                    }
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    // Conexión exitosa
                    Log.d(TAG, "Conectado al dispositivo GATT")
                    _connectionState.value = BleConnectionState.Connected
                    connectRetryCount = 0 // Resetear contador de intentos al conectar exitosamente

                    if (checkBluetoothPermission()) {
                        try {
                            // Añadir un pequeño retraso antes de descubrir servicios
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    gatt.discoverServices()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error al descubrir servicios", e)
                                    _connectionState.value = BleConnectionState.Error(
                                        "Error al descubrir servicios: ${e.message}"
                                    )
                                    disconnect()
                                }
                            }, 600) // 600ms para estabilizar la conexión
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Error de permisos al descubrir servicios", e)
                            _connectionState.value = BleConnectionState.Error(
                                "Se requieren permisos de Bluetooth"
                            )
                            disconnect()
                        }
                    } else {
                        Log.e(TAG, "Permisos de Bluetooth no disponibles")
                        _connectionState.value = BleConnectionState.Error(
                            "Se requieren permisos de Bluetooth"
                        )
                        disconnect()
                    }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Desconectado del dispositivo GATT")
                    disconnect()
                    _connectionState.value = BleConnectionState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Servicios descubiertos exitosamente")
                findCharacteristics(gatt)
            } else {
                Log.e(TAG, "Error descubriendo servicios: $status")
                _connectionState.value = BleConnectionState.Error("Error descubriendo servicios: $status")
                disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            Log.d(TAG, if (success) "Escritura exitosa" else "Escritura fallida: $status")

            if (!success) {
                _connectionState.value = BleConnectionState.Error("Error enviando configuración: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            try {
                val message = characteristic.value.toString(Charsets.UTF_8)
                Log.d(TAG, "Respuesta BLE recibida: $message")

                when {
                    message.startsWith("state:") -> {
                        val state = message.substringAfter("state:").trim()
                        Log.d(TAG, "Estado del ESP32: $state")
                        when (state) {
                            "RUNNING" -> {
                                _connectionState.value = BleConnectionState.ESP32Ready
                                onESP32StateChange?.invoke(ESP32State.RUNNING)
                            }
                            "CONFIG" -> {
                                _connectionState.value = BleConnectionState.ESP32Configuring
                                onESP32StateChange?.invoke(ESP32State.CONFIG)
                            }
                        }
                    }
                    message.startsWith("status:wifi_con") -> {
                        // Primero intentar el formato esperado con información adicional
                        val parts = message.split(",")
                        val ip = parts.find { it.startsWith("ip:") }?.substringAfter("ip:") ?: ""
                        val esp32Id = parts.find { it.startsWith("id:") }?.substringAfter("id:") ?: ""

                        // Si no tenemos información suficiente, usar la MAC del dispositivo
                        val effectiveIp = if (ip.isBlank()) "desconocida" else ip
                        val effectiveId = if (esp32Id.isBlank()) {
                            // Extraer ID del nombre del dispositivo
                            currentConnectedDevice?.let { device ->
                                try {
                                    device.name?.substringAfter("ESP32-") ?: ""
                                } catch (e: Exception) {
                                    ""
                                }
                            } ?: ""
                        } else {
                            esp32Id
                        }

                        Log.d(TAG, "WiFi configurado exitosamente. IP: $effectiveIp, ESP32 ID: $effectiveId")
                        onWifiConfigSuccess?.invoke(effectiveIp, effectiveId)
                    }
                    message.startsWith("error:wifi_failed") -> {
                        val errorMsg = message.substringAfter("error:wifi_failed:")
                            .takeIf { it.isNotBlank() } ?: "Error en configuración WiFi"
                        Log.e(TAG, "Error en configuración WiFi: $errorMsg")
                        onWifiConfigError?.invoke(errorMsg)
                    }
                    message.startsWith("bye:closing_con") -> {
                        Log.d(TAG, "ESP32 cerrando conexión")
                        disconnect()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando mensaje BLE", e)
                onWifiConfigError?.invoke("Error procesando respuesta: ${e.message}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor escrito exitosamente - Notificaciones habilitadas")
                _connectionState.value = BleConnectionState.Connected
            } else {
                Log.e(TAG, "Error al escribir descriptor: $status")
                _connectionState.value = BleConnectionState.Error("Error al habilitar notificaciones: $status")
            }
        }
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun findCharacteristics(gatt: BluetoothGatt) {
        try {
            Log.d(TAG, "Buscando características necesarias...")
            val service = gatt.getService(UART_SERVICE_UUID)

            if (service == null) {
                Log.e(TAG, "No se encontró el servicio UART: $UART_SERVICE_UUID")
                _connectionState.value = BleConnectionState.Error("Servicio UART no encontrado")
                disconnect()
                return
            }

            writeCharacteristic = service.getCharacteristic(UART_RX_CHAR_UUID)
            notifyCharacteristic = service.getCharacteristic(UART_TX_CHAR_UUID)

            if (writeCharacteristic == null) {
                Log.e(TAG, "No se encontró característica de escritura: $UART_RX_CHAR_UUID")
                _connectionState.value = BleConnectionState.Error("Característica de escritura no encontrada")
                disconnect()
                return
            }

            if (notifyCharacteristic == null) {
                Log.e(TAG, "No se encontró característica de notificación: $UART_TX_CHAR_UUID")
                _connectionState.value = BleConnectionState.Error("Característica de notificación no encontrada")
                disconnect()
                return
            }

            Log.d(TAG, "Características encontradas, habilitando notificaciones...")

            // Habilitar notificaciones con manejo de errores
            val notifyResult = gatt.setCharacteristicNotification(notifyCharacteristic!!, true)
            if (!notifyResult) {
                Log.e(TAG, "Error al habilitar notificaciones para característica")
                _connectionState.value = BleConnectionState.Error("Error al habilitar notificaciones")
                disconnect()
                return
            }

            val descriptor = notifyCharacteristic!!.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                Log.e(TAG, "No se encontró descriptor CCCD")
                _connectionState.value = BleConnectionState.Error("Descriptor CCCD no encontrado")
                disconnect()
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeResult = gatt.writeDescriptor(descriptor)

            if (!writeResult) {
                Log.e(TAG, "Error al escribir descriptor")
                _connectionState.value = BleConnectionState.Error("Error al configurar notificaciones")
                disconnect()
            } else {
                Log.d(TAG, "Configuración BLE completada correctamente")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando características BLE", e)
            _connectionState.value = BleConnectionState.Error("Error configurando características: ${e.message}")
            disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    fun sendWifiConfig(config: WifiConfig): Boolean {
        try {
            Log.d(TAG, "Preparando para enviar configuración WiFi")

            if (_connectionState.value !is BleConnectionState.Connected) {
                Log.e(TAG, "Intento de enviar configuración sin estar conectado")
                onWifiConfigError?.invoke("Dispositivo no conectado")
                return false
            }

            if (writeCharacteristic == null) {
                Log.e(TAG, "Característica de escritura no disponible")
                onWifiConfigError?.invoke("Error interno: característica de escritura no disponible")
                return false
            }

            // Formato con separadores claros para facilitar el parsing
            val message = """
                {START}
                {"ssid":"${config.ssid}","password":"${config.password}"}
                {END}
            """.trimIndent()

            Log.d(TAG, "Enviando configuración: SSID=${config.ssid}, Password=****")

            responseBuffer.clear()
            writeCharacteristic?.value = message.toByteArray()

            val result = bluetoothGatt?.writeCharacteristic(writeCharacteristic!!) == true
            if (result) {
                Log.d(TAG, "Configuración WiFi enviada exitosamente")
            } else {
                Log.e(TAG, "Error enviando configuración WiFi")
                onWifiConfigError?.invoke("Error enviando configuración")
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando configuración WiFi", e)
            onWifiConfigError?.invoke(e.message ?: "Error desconocido")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        try {
            // Resetear contador de intentos si estamos conectando a un nuevo dispositivo
            if (currentConnectedDevice?.address != device.address) {
                connectRetryCount = 0
                currentConnectedDevice = device
            }

            // Incrementar contador de intentos
            connectRetryCount++

            // No cambiamos el estado si ya estamos conectando
            if (_connectionState.value !is BleConnectionState.Connecting) {
                _connectionState.value = BleConnectionState.Connecting
            }

            // Limpiar conexiones anteriores
            bluetoothGatt?.close()
            bluetoothGatt = null

            // Añadir retraso que aumenta con cada intento
            val delayMs = 1000L * connectRetryCount  // Backoff exponencial
            Thread.sleep(delayMs)

            Log.d(TAG, "Intento $connectRetryCount de $MAX_CONNECT_RETRIES con dispositivo: ${device.address}")

            // Usar el método con parámetros adicionales
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK
            )

            Log.d(TAG, "Conexión GATT iniciada")
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando: ${e.message}", e)
            handleConnectionError(e.message ?: "Error de conexión", device)
        }
    }

    private fun handleConnectionError(errorMessage: String, device: BluetoothDevice?) {
        if (device != null && connectRetryCount < MAX_CONNECT_RETRIES) {
            Log.d(TAG, "Reintentando conexión automáticamente en 2 segundos (intento $connectRetryCount de $MAX_CONNECT_RETRIES)")
            // Reintentar después de un breve retraso
            Handler(Looper.getMainLooper()).postDelayed({
                connect(device)
            }, 2000)  // 2 segundos entre reintentos
        } else {
            // Simplemente reportar el error a través del estado
            _connectionState.value = BleConnectionState.Error("$errorMessage después de $MAX_CONNECT_RETRIES intentos")
            connectRetryCount = 0
            disconnect()
        }
    }

    enum class ESP32State {
        CONFIG, RUNNING
    }

    var onESP32StateChange: ((ESP32State) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            Log.d(TAG, "Desconectando dispositivo BLE...")

            // Antes de cerrar el GATT, verificar si hay conexión activa
            if (bluetoothGatt != null) {
                try {
                    // Intento explícito de desconexión antes de cerrar
                    bluetoothGatt?.disconnect()
                    // Esperar brevemente a que la desconexión se procese
                    Thread.sleep(300L)
                } catch (e: Exception) {
                    Log.e(TAG, "Error al desconectar GATT", e)
                }

                bluetoothGatt?.close()
                bluetoothGatt = null
            }

            writeCharacteristic = null
            notifyCharacteristic = null

            // Cambiar estado y limpiar el buffer
            _connectionState.value = BleConnectionState.Disconnected
            synchronized(responseBuffer) {
                responseBuffer.clear()
            }

            // Importante: resetear el contador de intentos
            connectRetryCount = 0

            Log.d(TAG, "Desconexión completada")
        } catch (e: Exception) {
            Log.e(TAG, "Error desconectando", e)
        }
    }

    // Callbacks para notificar eventos WiFi
    var onWifiConfigSuccess: ((String, String) -> Unit)? = null
    var onWifiConfigError: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "BleConnector"
        val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val UART_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val UART_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}