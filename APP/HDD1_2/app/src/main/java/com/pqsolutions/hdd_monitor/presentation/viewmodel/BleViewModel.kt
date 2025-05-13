package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.os.Build.VERSION_CODES
import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.bluetooth.BleConnector
import com.pqsolutions.hdd_monitor.bluetooth.BleScanner
import com.pqsolutions.hdd_monitor.bluetooth.BleConnectionState
import com.pqsolutions.hdd_monitor.bluetooth.WifiConfig
import com.pqsolutions.hdd_monitor.data.Client
import com.pqsolutions.hdd_monitor.data.ClientRepository
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import com.pqsolutions.hdd_monitor.presentation.state.BleState
import com.pqsolutions.hdd_monitor.util.BlePermissionHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID
import javax.inject.Inject
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.data.UserRepository

private const val TAG = "BleViewModel"
private const val SCAN_TIMEOUT = 30000L // 30 segundos
private const val WIFI_CONFIG_TIMEOUT = 30000L // 30 segundos

@HiltViewModel
class BleViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleScanner: BleScanner,
    private val bleConnector: BleConnector,
    private val clientRepository: ClientRepository,
    private val panelRepository: PanelRepository,
    private val esp32Repository: ESP32Repository,
    private val userRepository: UserRepository
) : ViewModel() {

    val _state = MutableStateFlow<BleState>(BleState.Initial)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private var currentMac: String? = null

    private val _devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val devices: StateFlow<List<BluetoothDevice>> = _devices.asStateFlow()

    private val _clients = MutableStateFlow<List<Client>>(emptyList())
    val clients: StateFlow<List<Client>> = _clients.asStateFlow()

    private val _esp32s = MutableStateFlow<List<ESP32Device>>(emptyList())
    val esp32s: StateFlow<List<ESP32Device>> = _esp32s.asStateFlow()

    private var currentESP32: ESP32Device? = null

    private var scanJob: Job? = null
    private var timeoutJob: Job? = null

    private val _unassignedEsp32s = MutableStateFlow<List<ESP32Device>>(emptyList())
    val unassignedEsp32s: StateFlow<List<ESP32Device>> = _unassignedEsp32s.asStateFlow()

    init {
        viewModelScope.launch {
            Log.d(TAG, "Iniciando BleViewModel")
            _state.value = BleState.ConfigMethodSelection
            observeDevices()
            observeConnectionState()
            observeWifiConfigState()
            loadClients()
            observeESP32s()

            if (_state.value is BleState.Initial) {
                Log.d(TAG, "Verificando condiciones iniciales")
                when {
                    !hasRequiredPermissions() -> {
                        Log.d(TAG, "Se requieren permisos")
                        requestPermissions { startScan() }
                    }
                    !isBluetoothEnabled() -> {
                        Log.d(TAG, "Bluetooth desactivado")
                        _state.value = BleState.Error("Bluetooth no está activado")
                    }
                    else -> {
                        Log.d(TAG, "Iniciando escaneo automático")
                        startScan()
                    }
                }
            }
        }
    }

    fun loadUnassignedEsp32s() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Cargando ESP32 no asignados...")
                _state.value = BleState.LoadingUnassignedDevices

                // Observar ESP32 no asignados desde el repositorio
                esp32Repository.observeUnassignedESP32s().collect { unassignedDevices ->
                    Log.d(TAG, "ESP32 no asignados encontrados: ${unassignedDevices.size}")
                    unassignedDevices.forEach { device ->
                        Log.d(TAG, "ESP32 no asignado: ${device.documentName}, Estado: ${device.status}")
                    }

                    // Actualizar el state flow con los dispositivos encontrados
                    _unassignedEsp32s.value = unassignedDevices

                    // Actualizar el estado de la UI
                    if (unassignedDevices.isEmpty()) {
                        _state.value = BleState.NoUnassignedDevices
                    } else {
                        _state.value = BleState.UnassignedDevicesFound(unassignedDevices)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando ESP32 no asignados", e)
                _state.value = BleState.Error("Error cargando ESP32 no asignados: ${e.message}")
            }
        }
    }

    fun selectUnassignedESP32(esp32Device: ESP32Device) {
        Log.d(TAG, "Seleccionando ESP32 no asignado: ${esp32Device.documentName}")
        currentESP32 = esp32Device
        timeoutJob?.cancel()

        // Verificar si el usuario es admin o normal para mostrar la pantalla adecuada
        viewModelScope.launch {
            try {
                val isAdmin = isUserAdmin()
                if (isAdmin) {
                    _state.value = BleState.SelectingClient(esp32Device)
                } else {
                    _state.value = BleState.CreatingPanel(esp32Device, userRepository.getCurrentUser()?.clientDocName ?: "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en selectUnassignedESP32", e)
                _state.value = BleState.Error("Error al seleccionar dispositivo: ${e.message}")
            }
        }
    }

    private fun loadClients() {
        viewModelScope.launch {
            clientRepository.getClients()
                .onSuccess { clientList ->
                    _clients.value = clientList
                    Log.d(TAG, "Clientes cargados: ${clientList.size}")
                }
                .onFailure { error ->
                    Log.e(TAG, "Error cargando clientes", error)
                    _state.value = BleState.Error("Error cargando lista de clientes")
                }
        }
    }

    fun createNewPanelForNormalUser(
        panelName: String,
        location: String
    ) {
        viewModelScope.launch {
            try {
                if (currentESP32 == null) {
                    _state.value = BleState.Error("No hay ESP32 seleccionado")
                    return@launch
                }

                // Obtener el usuario actual y su clientId
                val currentUser = userRepository.getCurrentUser()
                if (currentUser == null) {
                    _state.value = BleState.Error("No se encontró información del usuario")
                    return@launch
                }

                val clientId = currentUser.clientDocName
                if (clientId.isEmpty()) {
                    _state.value = BleState.Error("No se encontró información del cliente")
                    return@launch
                }

                // Cambiar estado a CreatingPanel para mostrar progreso
                _state.value = BleState.CreatingPanel(currentESP32!!, clientId)

                // Usar el clientId del usuario actual para crear el panel
                createNewPanel(
                    clientId = clientId,
                    panelName = panelName,
                    location = location
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error creating panel for normal user", e)
                _state.value = BleState.Error("Error creando panel: ${e.message}")
            }
        }
    }

    fun hasRequiredPermissions(): Boolean =
        BlePermissionHandler.allPermissionsGranted(context)

    fun isBluetoothEnabled(): Boolean =
        BlePermissionHandler.checkBluetoothEnabled(context)

    private fun observeDevices() {
        viewModelScope.launch {
            bleScanner.foundDevices.collect { devices ->
                _devices.value = devices.toList()
            }
        }
    }

    private fun observeESP32s() {
        viewModelScope.launch {
            esp32Repository.observeESP32s().collect { devices ->
                _esp32s.value = devices
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            bleConnector.connectionState.collect { connectionState ->
                Log.d(TAG, "Nuevo estado de conexión: $connectionState")
                when (connectionState) {
                    is BleConnectionState.Connected -> {
                        Log.d(TAG, "Dispositivo conectado")
                        _state.value = BleState.Connected
                        timeoutJob?.cancel()
                    }
                    is BleConnectionState.Connecting -> {
                        Log.d(TAG, "Iniciando conexión con dispositivo")
                        _state.value = BleState.Connecting
                    }
                    is BleConnectionState.Disconnected -> {
                        Log.d(TAG, "Dispositivo desconectado. Estado actual: ${_state.value}")
                        when (_state.value) {
                            is BleState.Connecting,
                            is BleState.Connected -> {
                                Log.d(TAG, "Cambiando a estado Disconnected")
                                _state.value = BleState.Disconnected
                            }
                            else -> {
                                Log.d(TAG, "Manteniendo estado actual: ${_state.value}")
                            }
                        }
                    }
                    is BleConnectionState.ESP32Configuring -> {
                        Log.d(TAG, "ESP32 en configuración")
                        _state.value = BleState.WifiConfiguring
                    }
                    is BleConnectionState.ESP32Ready -> {
                        Log.d(TAG, "ESP32 configurado y listo")
                        _state.value = BleState.WaitingForRunningMode
                    }
                    is BleConnectionState.Error -> {
                        Log.e(TAG, "Error de conexión: ${connectionState.message}")

                        // Detectar errores específicos que requieren reinicio del Bluetooth
                        val errorMessage = connectionState.message.lowercase()
                        if (errorMessage.contains("timeout") || errorMessage.contains("62")) {
                            Log.d(TAG, "Detectado error que requiere reinicio de Bluetooth")
                            _state.value = BleState.Error("${connectionState.message}. Reiniciando Bluetooth...")
                            resetBluetoothAdapter()
                        } else {
                            _state.value = BleState.Error(connectionState.message)
                        }
                    }
                }
            }
        }
    }

    suspend fun isUserAdmin(): Boolean {
        return try {
            val currentUser = userRepository.getCurrentUser()
            currentUser?.role == UserRole.ADMIN
        } catch (e: Exception) {
            Log.e(TAG, "Error checking user role", e)
            false
        }
    }

    fun startScan() {
        viewModelScope.launch {
            Log.d(TAG, "Iniciando escaneo BLE")
            if (!hasRequiredPermissions()) {
                Log.d(TAG, "Requiriendo permisos para escaneo")
                requestPermissions { startScan() }
                return@launch
            }

            if (!isBluetoothEnabled()) {
                Log.d(TAG, "Bluetooth no está activado")
                _state.value = BleState.Error("Bluetooth no está activado")
                return@launch
            }

            // Cancelar cualquier escaneo anterior de manera controlada
            try {
                scanJob?.cancel()
                bleScanner.stopScan()
            } catch (e: Exception) {
                // Ignorar excepciones de cancelación
                Log.d(TAG, "Error deteniendo escaneo anterior, continuando: ${e.message}")
            }

            bleScanner.clearDevices()
            _state.value = BleState.Scanning

            Log.d(TAG, "Comenzando escaneo de dispositivos")
            scanJob = viewModelScope.launch {
                try {
                    bleScanner.startScan()
                    delay(SCAN_TIMEOUT)
                    stopScan()

                    if (_devices.value.isEmpty()) {
                        Log.d(TAG, "No se encontraron dispositivos")
                        _state.value = BleState.Error(
                            "No se encontraron dispositivos ESP32.\n" +
                                    "Asegúrate que el dispositivo esté encendido y cercano."
                        )
                    }
                } catch (e: Exception) {
                    // Verificar si es una excepción de cancelación de job
                    if (e.message?.contains("was cancelled") != true) {
                        Log.e(TAG, "Error durante el escaneo", e)
                        _state.value = BleState.Error("Error durante el escaneo: ${e.message}")
                    } else {
                        Log.d(TAG, "Escaneo cancelado intencionalmente")
                    }
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        bleScanner.stopScan()
        if (_state.value is BleState.Scanning) {
            _state.value = BleState.Initial
        }
    }


    fun connectToDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            Log.d(TAG, "Intentando conectar a dispositivo: ${device.address}")
            if (!hasRequiredPermissions()) {
                Log.d(TAG, "Requiriendo permisos para conexión")
                requestPermissions { connectToDevice(device) }
                return@launch
            }

            try {
                // Cambiar primero al estado Connecting para evitar mostrar errores intermedios
                _state.value = BleState.Connecting
                currentMac = device.address

                // Luego detener el escaneo de manera controlada
                Log.d(TAG, "Deteniendo escáner BLE antes de conectar")
                scanJob?.cancel()
                bleScanner.stopScan()

                // Añadir un retraso MAYOR antes de conectar
                delay(2000)  // Aumentar a 2 segundos

                // Iniciar el timeout después de intentar la conexión
                bleConnector.connect(device)
                startTimeoutTimer(CONNECTION_TIMEOUT)
            } catch (e: Exception) {
                // Verificar si es una excepción de cancelación de job
                if (e.message?.contains("was cancelled") != true) {
                    Log.e(TAG, "Error conectando al dispositivo", e)
                    _state.value = BleState.Error("Error conectando al dispositivo: ${e.message}")
                    currentMac = null
                } else {
                    Log.d(TAG, "Operación cancelada intencionalmente")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun resetBluetoothAdapter() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Reiniciando adaptador Bluetooth...")

                // Verificar permisos primero
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                        PackageManager.PERMISSION_GRANTED) {
                        _state.value = BleState.Error("Se requieren permisos para reiniciar Bluetooth")
                        return@launch
                    }
                } else {
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) !=
                        PackageManager.PERMISSION_GRANTED) {
                        _state.value = BleState.Error("Se requieren permisos para reiniciar Bluetooth")
                        return@launch
                    }
                }

                // Detener cualquier operación en curso
                bleScanner.stopScan()
                scanJob?.cancel()
                timeoutJob?.cancel()
                bleConnector.disconnect()

                // Apagar Bluetooth
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (bluetoothAdapter?.isEnabled == true) {
                    try {
                        bluetoothAdapter.disable()
                        // Esperar a que se apague
                        delay(3000)

                        // Encender de nuevo
                        bluetoothAdapter.enable()
                        delay(5000) // Esperar a que se inicialice

                        Log.d(TAG, "Adaptador Bluetooth reiniciado")
                        _state.value = BleState.Initial

                        // Reiniciar escaneo después
                        delay(1000)
                        startScan()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error de permisos al manipular Bluetooth", e)
                        _state.value = BleState.Error("Error de permisos: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al reiniciar Bluetooth", e)
                _state.value = BleState.Error("No se pudo reiniciar Bluetooth: ${e.message}")
            }
        }
    }

    fun sendWifiConfiguration(ssid: String, password: String) {
        if (_state.value != BleState.Connected) {
            Log.e(TAG, "Intento de enviar configuración WiFi sin estar conectado")
            return
        }

        viewModelScope.launch {
            _state.value = BleState.WifiConfigReceived

            val success = bleConnector.sendWifiConfig(WifiConfig(ssid, password))
            if (!success) {
                _state.value = BleState.Error("Error enviando configuración WiFi")
            } else {
                _state.value = BleState.WifiConfiguring
                startTimeoutTimer(WIFI_CONFIG_TIMEOUT)
            }
        }
    }

    private fun handleWifiConfigured(mac: String, ip: String) {
        viewModelScope.launch {
            try {
                // Obtener ESP32 ID de forma segura del nombre del dispositivo BLE
                val esp32Id = devices.value.firstOrNull()?.let { device ->
                    try {
                        if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                                PackageManager.PERMISSION_GRANTED) {
                                device.name?.substringAfter("ESP32-")
                            } else {
                                throw Exception("Se requieren permisos Bluetooth para configurar el dispositivo")
                            }
                        } else {
                            device.name?.substringAfter("ESP32-")
                        }
                    } catch (e: SecurityException) {
                        throw Exception("Error de permisos: ${e.message}")
                    }
                } ?: throw Exception("No se pudo obtener ID del ESP32")

                // Primero conectamos a MQTT
                connectMqtt()

                // Luego actualizamos Firestore
                esp32Repository.updateNetworkInfo(
                    esp32Id = esp32Id,
                    ip = ip,
                    mac = mac
                ).onSuccess {
                    _state.value = BleState.WaitingForRunningMode
                    startTimeoutTimer(RUNNING_MODE_TIMEOUT)
                }.onFailure { e ->
                    throw Exception("Error actualizando Firestore: ${e.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en handleWifiConfigured", e)
                _state.value = BleState.ConfigurationError(e.message ?: "Error configurando dispositivo")
            }
        }
    }

    fun onRunningModeConfirmed(esp32Device: ESP32Device) {
        _state.value = BleState.SelectingClient(esp32Device)
    }

    fun createNewPanel(
        clientId: String,
        panelName: String,
        location: String
    ) {
        viewModelScope.launch {
            try {
                if (currentESP32 == null) {
                    _state.value = BleState.Error("No hay ESP32 seleccionado")
                    return@launch
                }

                // Cancelar cualquier timeout activo antes de cambiar de estado
                timeoutJob?.cancel()

                // Actualizar estado a CreatingPanel para mostrar progreso
                _state.value = BleState.CreatingPanel(currentESP32!!, clientId)
                Log.d(TAG, "Iniciando creación de panel para ESP32 ${currentESP32!!.documentName}")

                val panel = Panel.createNew(
                    name = panelName,
                    location = location,
                    clientName = clientId,
                    esp32Id = currentESP32!!.documentName
                )

                // Crear panel en Firestore con notificación de progreso
                Log.d(TAG, "Creando panel en Firestore...")
                val panelId = panelRepository.createNewPanel(
                    clientDocName = clientId,
                    panel = panel,
                    esp32Id = currentESP32!!.documentName
                ).getOrThrow()

                Log.d(TAG, "Panel creado con ID: $panelId. Esperando confirmación del ESP32...")

                // Comenzar a observar el estado del ESP32 para confirmar configuración
                observeESP32Status(currentESP32!!.documentName)

                // Iniciar timeout para esperar la configuración
                Log.d(TAG, "Iniciando timeout de espera para confirmación...")
                startTimeoutTimer(RUNNING_MODE_TIMEOUT)

            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error: ESP32 ya asignado", e)
                _state.value = BleState.Error("Este ESP32 ya está asignado a otro panel")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating panel", e)
                _state.value = BleState.Error("Error creando panel: ${e.message}")
            }
        }
    }

    private fun observeESP32Status(esp32Id: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Iniciando observación del estado del ESP32 $esp32Id")
                esp32Repository.observeESP32Status(esp32Id).collect { status ->
                    Log.d(TAG, "ESP32 $esp32Id estado actualizado: $status")
                    when (status) {
                        ESP32Device.STATUS_ONLINE -> {
                            Log.d(TAG, "ESP32 $esp32Id está en línea y configurado exitosamente")
                            // Configuración aceptada por el ESP32
                            currentESP32?.let { esp32Device ->
                                // Actualizar el campo de estado en nuestro objeto ESP32Device
                                val updatedDevice = esp32Device.copy(status = status)
                                _state.value = BleState.ConfigurationSuccess(updatedDevice)
                            } ?: run {
                                _state.value = BleState.ConfigurationSuccess(
                                    ESP32Device(
                                        documentName = esp32Id,
                                        status = status
                                    )
                                )
                            }
                            disconnect()
                            timeoutJob?.cancel()
                        }
                        ESP32Device.STATUS_RUNNING -> {
                            Log.d(TAG, "ESP32 $esp32Id está en modo operación normal")
                            // El ESP32 está en modo operación normal
                            currentESP32?.let { esp32Device ->
                                // Actualizar el campo de estado en nuestro objeto ESP32Device
                                val updatedDevice = esp32Device.copy(status = status)
                                _state.value = BleState.ConfigurationSuccess(updatedDevice)
                            } ?: run {
                                _state.value = BleState.ConfigurationSuccess(
                                    ESP32Device(
                                        documentName = esp32Id,
                                        status = status
                                    )
                                )
                            }
                            disconnect()
                            timeoutJob?.cancel()
                        }
                        "ERROR" -> { // Usando el string directamente como alternativa
                            _state.value = BleState.ConfigurationError("Error configurando el ESP32")
                            disconnect()
                            timeoutJob?.cancel()
                        }
                        ESP32Device.STATUS_OFFLINE,
                        ESP32Device.STATUS_DISC -> {
                            _state.value = BleState.ConfigurationError("El dispositivo se ha desconectado")
                            disconnect()
                            timeoutJob?.cancel()
                        }
                        ESP32Device.STATUS_AWAITING_CONFIG -> {
                            // El ESP32 aún está esperando configuración, seguimos esperando
                            // Actualizamos el estado para mostrar el progreso
                            _state.value = BleState.WaitingForRunningMode
                            Log.d(TAG, "ESP32 esperando configuración")
                        }
                        else -> {
                            Log.d(TAG, "Estado no manejado: $status")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observando estado del ESP32", e)
                _state.value = BleState.ConfigurationError("Error monitoreando el estado del dispositivo: ${e.message}")
                disconnect()
                timeoutJob?.cancel()
            }
        }
    }

    private fun observeWifiConfigState() {
        bleConnector.onWifiConfigSuccess = { ip, esp32Id ->
            viewModelScope.launch {
                Log.d(TAG, "WiFi configurado exitosamente. IP: $ip, ESP32 ID: $esp32Id")
                timeoutJob?.cancel()

                if (esp32Id.isNotEmpty()) {
                    Log.d(TAG, "ESP32 ID recibido: $esp32Id")
                    // Esperar un momento para que Firestore se actualice
                    delay(2000)
                    startObservingESP32ById(esp32Id)
                } else {
                    // Fallback a extraer el ID del nombre BLE
                    val deviceId = devices.value.firstOrNull()?.let { device ->
                        try {
                            if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    device.name?.substringAfter("ESP32-")
                                } else null
                            } else {
                                device.name?.substringAfter("ESP32-")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error extrayendo ESP32 ID", e)
                            null
                        }
                    }

                    if (deviceId != null) {
                        Log.d(TAG, "ESP32 ID extraído: $deviceId")
                        startObservingESP32ById(deviceId)
                    } else {
                        _state.value = BleState.Error("No se pudo obtener ID del ESP32")
                    }
                }
            }
        }

        bleConnector.onWifiConfigError = { errorMessage ->
            viewModelScope.launch {
                Log.e(TAG, "Error en configuración WiFi: $errorMessage")
                _state.value = BleState.Error("Error en configuración WiFi: $errorMessage")
                disconnect()
            }
        }
    }

    private fun startObservingESP32ById(esp32Id: String) {
        viewModelScope.launch {
            Log.d(TAG, "Iniciando observación de ESP32 ID: $esp32Id")
            try {
                // Si el ESP32ID está vacío, ver si podemos obtenerlo del dispositivo conectado
                val effectiveId = if (esp32Id.isBlank()) {
                    val device = devices.value.firstOrNull()
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            // Android 12+ requiere BLUETOOTH_CONNECT
                            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                                PackageManager.PERMISSION_GRANTED) {
                                device?.name?.substringAfter("ESP32-") ?: ""
                            } else {
                                Log.d(TAG, "Falta permiso BLUETOOTH_CONNECT para acceder al nombre del dispositivo")
                                ""
                            }
                        } else {
                            // Versiones anteriores requieren BLUETOOTH
                            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH) ==
                                PackageManager.PERMISSION_GRANTED) {
                                device?.name?.substringAfter("ESP32-") ?: ""
                            } else {
                                Log.d(TAG, "Falta permiso BLUETOOTH para acceder al nombre del dispositivo")
                                ""
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error de permisos al acceder al nombre del dispositivo", e)
                        ""
                    }
                } else {
                    esp32Id
                }
                if (effectiveId.isBlank()) {
                    Log.e(TAG, "No se pudo determinar el ID del ESP32")
                    _state.value = BleState.Error("No se pudo identificar el ESP32")
                    return@launch
                }

                Log.d(TAG, "Buscando ESP32 con ID: $effectiveId")

                // Actualizar estado para mostrar progreso durante la búsqueda
                _state.value = BleState.WaitingForRunningMode

                // Ampliamos nuestro periodo de búsqueda para darle tiempo al ESP32 a registrarse en Firestore
                var timeoutMillis = 30000L // 30 segundos
                var device: ESP32Device? = null

                // Intenta varias veces con un retraso entre intentos
                while (timeoutMillis > 0 && device == null) {
                    // Esperar para darle tiempo a Firestore a actualizarse
                    delay(5000) // 5 segundos entre intentos
                    timeoutMillis -= 5000

                    // Intentar observar directamente por ID
                    try {
                        Log.d(TAG, "Intento directo por ID: $effectiveId")
                        withTimeout(3000) {
                            esp32Repository.observeESP32s().take(1).collect { devices ->
                                val found = devices.find { it.documentName == effectiveId }
                                if (found != null) {
                                    Log.d(TAG, "ESP32 encontrado directamente por ID: ${found.documentName}, estado: ${found.status}")
                                    device = found
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "No se encontró por ID directamente: ${e.message}")
                    }

                    // Si no lo encontramos directamente, intentar por MAC
                    if (device == null) {
                        try {
                            currentMac?.let { mac ->
                                Log.d(TAG, "Intentando búsqueda por MAC: $mac")
                                withTimeoutOrNull(3000) {
                                    esp32Repository.observeUnassignedESP32s().take(1)
                                        .collect { devices ->
                                            val found = devices.find {
                                                it.MAC.equals(mac, ignoreCase = true) ||
                                                        it.documentName == effectiveId
                                            }
                                            if (found != null) {
                                                Log.d(TAG, "ESP32 encontrado por MAC: ${found.MAC}, ID: ${found.documentName}")
                                                device = found
                                            }
                                        }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Error buscando por MAC: ${e.message}")
                        }
                    }

                    // Si todavía no lo encontramos, intentar por todos los no asignados
                    if (device == null) {
                        try {
                            Log.d(TAG, "Buscando entre todos los no asignados")
                            withTimeoutOrNull(3000) {
                                esp32Repository.observeUnassignedESP32s().take(1)
                                    .collect { devices ->
                                        if (devices.isNotEmpty()) {
                                            device = devices.firstOrNull()
                                            if (device != null) {
                                                Log.d(TAG, "Encontrado dispositivo no asignado: ${device!!.documentName}")
                                            }
                                        }
                                    }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Error buscando entre no asignados: ${e.message}")
                        }
                    }

                    // Actualizar estado para mostrar que seguimos buscando
                    if (device == null) {
                        _state.value = BleState.WaitingForRunningMode
                    }
                }

                // Verificar si encontramos un dispositivo
                if (device != null) {
                    Log.d(TAG, "ESP32 encontrado finalmente: ${device!!.documentName}, Estado: ${device!!.status}")
                    currentESP32 = device
                    _state.value = BleState.SelectingClient(device!!)
                    timeoutJob?.cancel()
                } else {
                    Log.e(TAG, "No se pudo encontrar el ESP32 en Firestore después de múltiples intentos")
                    _state.value = BleState.Error("No se pudo localizar el ESP32 en el sistema. Por favor intente de nuevo o contacte a soporte.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error observando ESP32 en Firestore", e)
                _state.value = BleState.Error("Error verificando estado del dispositivo: ${e.message}")
            }
        }
    }

    fun moveToClientSelection(esp32Device: ESP32Device) {
        timeoutJob?.cancel()
        _state.value = BleState.SelectingClient(esp32Device)
    }

    fun moveToCreatePanel(esp32Device: ESP32Device, clientId: String?) {
        timeoutJob?.cancel()
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                val finalClientId = clientId ?: currentUser?.clientDocName

                if (finalClientId == null) {
                    _state.value = BleState.Error("No se pudo determinar el cliente")
                    return@launch
                }

                _state.value = BleState.CreatingPanel(esp32Device, finalClientId)
            } catch (e: Exception) {
                Log.e(TAG, "Error moving to create panel", e)
                _state.value = BleState.Error("Error: ${e.message}")
            }
        }
    }

    fun onWifiConfigured(esp32Device: ESP32Device) {
        _state.value = BleState.WifiConfigured(esp32Device)
        startObservingESP32ById(esp32Device.documentName)
    }

    fun startTimeoutTimer(timeout: Long) {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            // Mostrar estado de espera inmediatamente
            when (_state.value) {
                is BleState.WifiConfiguring -> {
                    Log.d(TAG, "Iniciando timeout para configuración WiFi: ${timeout}ms")
                }
                is BleState.WaitingForRunningMode -> {
                    Log.d(TAG, "Iniciando timeout para modo operación: ${timeout}ms")
                }
                else -> {
                    Log.d(TAG, "Iniciando timeout para: ${_state.value}: ${timeout}ms")
                }
            }

            delay(timeout)

            when (_state.value) {
                is BleState.WifiConfiguring -> {
                    _state.value = BleState.Error(
                        "Timeout configurando WiFi"
                    )
                    disconnect()
                }
                is BleState.WaitingForRunningMode -> {
                    _state.value = BleState.Error(
                        "Timeout esperando confirmación"
                    )
                    disconnect()
                }
                else -> {
                    _state.value = BleState.Error(
                        "Operación cancelada por timeout"
                    )
                    disconnect()
                }
            }
        }
    }

    private fun requestPermissions(onGranted: () -> Unit) {
        _state.value = BleState.RequiresPermission(
            permissions = BlePermissionHandler.getRequiredPermissions().toList(),
            onPermissionGranted = onGranted
        )
    }

    fun disconnect() {
        currentMac = null  // Limpiar MAC al desconectar
        bleConnector.disconnect()
        timeoutJob?.cancel()
    }

    fun onPermissionsGranted() {
        when (val currentState = state.value) {
            is BleState.RequiresPermission -> currentState.onPermissionGranted()
            else -> startScan()
        }
    }

    fun selectESP32(esp32: ESP32Device) {
        currentESP32 = esp32
    }

    private val mqttClient = MqttAndroidClient(
        context,
        "ssl://node02.myqtthub.com:8883",
        "android_${UUID.randomUUID()}"
    )

    private fun connectMqtt() {
        val options = MqttConnectOptions().apply {
            userName = "ESP32-1"  // Usar las mismas credenciales que el ESP32
            password = "esp32".toCharArray()
            isCleanSession = true
        }

        mqttClient.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                subscribeToNetworkInfo()
            }
            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e(TAG, "Error conectando a MQTT", exception)
            }
        })
    }

    private fun subscribeToNetworkInfo() {
        mqttClient.subscribe("esp32/network_info", 1) { topic, message ->
            val payload = message.payload.toString(Charset.defaultCharset())
            try {
                val jsonObject = JSONObject(payload)
                val esp32Id = jsonObject.getString("esp32_id")
                val status = jsonObject.getString("status")
                val mac = jsonObject.getString("MAC")
                val timestamp = jsonObject.getJSONObject("timestamp")

                // Solo procesar mensajes en tiempo real
                if (timestamp.getString("type") == "realtime" && status == "AWAITING_CONFIG") {
                    // Usar el MAC que viene del ESP32
                    currentMac = mac  // Actualizar el MAC guardado con el que viene del ESP32

                    viewModelScope.launch {
                        esp32Repository.observeUnassignedESP32s(mac).collect { unassignedESP32s ->
                            unassignedESP32s.find { it.documentName == esp32Id }?.let { esp32Device ->
                                _state.value = BleState.SelectingClient(esp32Device)
                                timeoutJob?.cancel()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando mensaje MQTT", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentMac = null  // Limpiar MAC al destruir ViewModel
        disconnect()
        stopScan()
        scanJob?.cancel()
        timeoutJob?.cancel()
    }

    fun startWifiConfigTimeout() {
        viewModelScope.launch {
            _state.value = BleState.WifiConfiguring
            delay(WIFI_CONFIG_TIMEOUT)

            if (_state.value is BleState.WifiConfiguring) {
                Log.d(TAG, "Verificando estado de ESP32...")

                currentMac?.let { mac ->
                    esp32Repository.observeUnassignedESP32s()
                        .take(1)
                        .collect { devices ->
                            val esp32 = devices.find { it.MAC.equals(mac, ignoreCase = true) }

                            if (esp32 != null) {
                                when (esp32.status) {
                                    ESP32Device.STATUS_AWAITING_CONFIG -> {
                                        Log.d(TAG, "ESP32 encontrado en Firestore esperando configuración")
                                        onWifiConfigured(esp32)
                                    }
                                    ESP32Device.STATUS_WIFI_CONFIG -> {
                                        Log.d(TAG, "ESP32 en estado WIFI_CONFIG, asumiendo error de contraseña")
                                        _state.value = BleState.Error("Error de contraseña WiFi")
                                    }
                                    else -> {
                                        Log.d(TAG, "ESP32 en estado inesperado: ${esp32.status}")
                                        _state.value = BleState.ConfigurationError(
                                            "Estado inesperado del ESP32: ${esp32.status}"
                                        )
                                    }
                                }
                            } else {
                                _state.value = BleState.ConfigurationError(
                                    "Timeout configurando WiFi"
                                )
                            }
                        }
                } ?: run {
                    _state.value = BleState.ConfigurationError(
                        "Error interno: MAC no disponible"
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "BleViewModel"
        private const val SCAN_TIMEOUT = 45000L         // 45 segundos
        private const val WIFI_CONFIG_TIMEOUT = 120000L // 2 minutos
        private const val FIRESTORE_TIMEOUT = 180000L   // 3 minutos
        private const val RUNNING_MODE_TIMEOUT = 300000L // 5 minutos
        private const val CONNECTION_TIMEOUT = 30000L    // 30 segundos
    }
}