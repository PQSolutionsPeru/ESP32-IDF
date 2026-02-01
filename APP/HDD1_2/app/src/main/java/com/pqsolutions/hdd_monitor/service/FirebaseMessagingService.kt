package com.pqsolutions.hdd_monitor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.ClientRepository
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class FirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var clientRepository: ClientRepository

    @Inject
    lateinit var panelRepository: PanelRepository

    @Inject
    lateinit var notificationRepository: NotificationRepository

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var firestore: FirebaseFirestore

    private val SHOW_VISUAL_NOTIFICATIONS = true
    private val SHOW_STATUS_NOTIFICATIONS = true

    private val sharedPreferences: SharedPreferences by lazy {
        getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "OptimizedFCMService"
        private const val PREF_PENDING_FCM_TOKEN = "pending_fcm_token"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FCM Service optimizado creado para 24/7")
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val eventChannel = NotificationChannel(
                "event_notifications",
                "Eventos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones sobre eventos del sistema"
                enableLights(true)
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            notificationManager.createNotificationChannel(eventChannel)

            val connectivityChannel = NotificationChannel(
                "connectivity_notifications",
                "Conectividad",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones sobre problemas de conectividad WiFi e Internet"
                enableLights(true)
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            notificationManager.createNotificationChannel(connectivityChannel)

            val statusChannel = NotificationChannel(
                "status_notifications",
                "Estado del Panel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones sobre cambios en el estado del panel"
                enableLights(true)
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            notificationManager.createNotificationChannel(statusChannel)

            val relayChannel = NotificationChannel(
                "relay_notifications",
                "Estado del Relay",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones sobre cambios en el estado de los relays"
                enableLights(true)
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            notificationManager.createNotificationChannel(relayChannel)
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "Nuevo token FCM para 24/7: $token")

        // Guardar el token localmente siempre
        sharedPreferences.edit().putString(PREF_PENDING_FCM_TOKEN, token).apply()
        Log.d(TAG, "Token guardado en SharedPreferences")

        CoroutineScope(Dispatchers.IO).launch {
            // Solo intentar enviar si hay un usuario logueado
            val currentUser = userRepository.getCurrentUser()
            if (currentUser != null) {
                Log.d(TAG, "Usuario logueado detectado, enviando token al servidor")
                sendTokenToServerWithRetry(token)
            } else {
                Log.d(TAG, "No hay usuario logueado, token guardado para envío posterior")
            }
        }
    }

    private suspend fun sendTokenToServerWithRetry(token: String) {
        var retries = 0
        val maxRetries = 5

        while (retries < maxRetries) {
            try {
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "Sin conexión de red, esperando...")
                    delay(5000)
                    continue
                }

                userRepository.updateFCMToken(token)
                    .onSuccess {
                        Log.d(TAG, "Token FCM 24/7 actualizado exitosamente")
                    }
                    .onFailure { error ->
                        throw error
                    }

                break

            } catch (e: Exception) {
                Log.e(TAG, "Error enviando token, intento ${retries + 1}", e)
                retries++
                if (retries < maxRetries) {
                    delay(1000L * (1 shl minOf(retries, 5)))
                }
            }
        }

        if (retries >= maxRetries) {
            Log.e(TAG, "No se pudo actualizar el token FCM después de $maxRetries intentos")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "=================== FCM 24/7 MENSAJE ===================")
        Log.d(TAG, "Mensaje recibido desde: ${remoteMessage.from}")
        Log.d(TAG, "Priority: ${remoteMessage.priority}")
        Log.d(TAG, "Datos: ${remoteMessage.data}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val messageType = data["type"] ?: ""

            Log.d(TAG, "Procesando mensaje tipo: $messageType")

            when {
                messageType == "connectivity" -> {
                    processConnectivityMessage(data)
                }
                messageType == "event" || data.containsKey("eventId") || data.containsKey("eventType") -> {
                    processEventMessage(data)
                }
                messageType == "status" -> {
                    processStatusMessage(data)
                }
                messageType == "relay" -> {
                    processRelayMessage(data)
                }
                else -> {
                    processGenericMessage(data)
                }
            }
        }

        Log.d(TAG, "=================== FIN FCM 24/7 ===================")
    }

    private fun processConnectivityMessage(data: Map<String, String>) {
        Log.d(TAG, "=== PROCESANDO CONECTIVIDAD 24/7 ===")

        val clientDocName = data["clientDocName"] ?: ""
        val panelName = data["panelName"] ?: data["panel_name"] ?: "Panel"
        val connectivityType = data["connectivityType"] ?: data["connectivity_type"] ?: ""
        val ssid = data["ssid"] ?: ""
        val timeRange = data["timeRange"] ?: data["time_range"] ?: ""
        val messageFromServer = data["message"] ?: ""

        Log.d(TAG, "Datos completos de conectividad: $data")
        Log.d(TAG, "Panel: $panelName, Tipo: $connectivityType, SSID: $ssid")
        Log.d(TAG, "Mensaje del servidor: '$messageFromServer'")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (messageFromServer.isNotEmpty()) {
                    Log.d(TAG, "Usando mensaje específico del servidor: '$messageFromServer'")
                    showConnectivityNotification(clientDocName, panelName, connectivityType, messageFromServer, ssid, timeRange)
                } else {
                    val fallbackMessage = when {
                        connectivityType.contains("disconnection") || connectivityType.contains("lost") -> {
                            when {
                                connectivityType.contains("wifi") -> "Panel $panelName se desconectó de la red WiFi $ssid"
                                connectivityType.contains("mqtt") -> "Panel $panelName perdió conexión con el servidor MQTT"
                                else -> "Panel $panelName perdió conexión a Internet"
                            }
                        }
                        connectivityType.contains("reconnected") || connectivityType.contains("recovered") -> {
                            when {
                                connectivityType.contains("wifi") -> "Panel $panelName se reconectó a la red WiFi $ssid"
                                connectivityType.contains("mqtt") -> "Panel $panelName se reconectó al servidor MQTT"
                                else -> "Panel $panelName recuperó conexión a Internet"
                            }
                        }
                        else -> "Cambio de conectividad en panel $panelName"
                    }

                    Log.w(TAG, "Usando mensaje fallback: '$fallbackMessage'")
                    showConnectivityNotification(clientDocName, panelName, connectivityType, fallbackMessage, ssid, timeRange)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error procesando connectivity message", e)
            }
        }
    }

    private fun showConnectivityNotification(
        clientDocName: String,
        panelName: String,
        connectivityType: String,
        message: String,
        ssid: String,
        timeRange: String
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "connectivity")
            putExtra("clientDocName", clientDocName)
            putExtra("panelName", panelName)
            putExtra("connectivityType", connectivityType)
            putExtra("ssid", ssid)
            putExtra("timeRange", timeRange)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, priority, color) = when {
            connectivityType.contains("disconnection") || connectivityType.contains("lost") -> {
                when {
                    connectivityType.contains("wifi") -> Triple("⚠️ WiFi Desconectado", NotificationCompat.PRIORITY_HIGH, 0xFFFF4444.toInt())
                    connectivityType.contains("mqtt") -> Triple("⚠️ Servidor Desconectado", NotificationCompat.PRIORITY_HIGH, 0xFFFF8800.toInt())
                    else -> Triple("⚠️ Sin Internet", NotificationCompat.PRIORITY_HIGH, 0xFFFF6600.toInt())
                }
            }
            connectivityType.contains("reconnected") || connectivityType.contains("recovered") -> {
                when {
                    connectivityType.contains("wifi") -> Triple("✅ WiFi Reconectado", NotificationCompat.PRIORITY_DEFAULT, 0xFF00AA00.toInt())
                    connectivityType.contains("mqtt") -> Triple("✅ Servidor Reconectado", NotificationCompat.PRIORITY_DEFAULT, 0xFF0088AA.toInt())
                    else -> Triple("✅ Internet Recuperado", NotificationCompat.PRIORITY_DEFAULT, 0xFF00AA00.toInt())
                }
            }
            else -> {
                Triple("🔄 Conectividad", NotificationCompat.PRIORITY_DEFAULT, 0xFF0088FF.toInt())
            }
        }

        val notification = NotificationCompat.Builder(this, "connectivity_notifications")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$clientDocName - $title")
            .setContentText(message)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setLights(color, 500, 500)
            .build()

        // Usar clientDocName + timestamp para ID único y evitar suplantación
        val uniqueId = "${clientDocName}_${panelName}_${connectivityType}_${System.currentTimeMillis()}".hashCode()
        notificationManager.notify(uniqueId, notification)
        Log.d(TAG, "Notificación de conectividad mostrada: '$message'")
    }

    private fun processRelayMessage(data: Map<String, String>) {
        Log.d(TAG, "=== PROCESANDO RELAY 24/7 ===")

        val clientDocName = data["clientDocName"] ?: ""
        val panelDocName = data["panelDocName"] ?: ""
        val relayName = data["relayName"] ?: ""
        val newStatus = data["newStatus"] ?: ""
        val oldStatus = data["oldStatus"] ?: ""
        val messageFromServer = data["message"] ?: ""

        Log.d(TAG, "Datos completos del relay: $data")
        Log.d(TAG, "Relay: $relayName, Status: $oldStatus → $newStatus")
        Log.d(TAG, "Mensaje del servidor: '$messageFromServer'")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateRelayInFirestore(clientDocName, panelDocName, relayName, newStatus)

                withContext(Dispatchers.Main) {
                    if (SHOW_VISUAL_NOTIFICATIONS) {
                        if (messageFromServer.isNotEmpty()) {
                            Log.d(TAG, "Usando mensaje específico del servidor: '$messageFromServer'")
                            showRelayNotification(clientDocName, relayName, newStatus, messageFromServer)
                        } else {
                            val fallbackMessage = "$relayName cambió de $oldStatus a $newStatus"
                            Log.w(TAG, "Usando mensaje fallback: '$fallbackMessage'")
                            showRelayNotification(clientDocName, relayName, newStatus, fallbackMessage)
                        }
                    }
                }

                sendLocalBroadcast(clientDocName, panelDocName, relayName, newStatus)

            } catch (e: Exception) {
                Log.e(TAG, "Error procesando relay message", e)
            }
        }
    }

    private suspend fun updateRelayInFirestore(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        newStatus: String
    ) {
        try {
            val dateTime = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))

            val updateData = mapOf(
                "status" to newStatus,
                "date_time" to dateTime,
                "lastUpdate" to com.google.firebase.Timestamp.now(),
                "commandSource" to "fcm_24x7"
            )

            firestore.document("hdd-monitor/accounts/clients/$clientDocName/panels/$panelDocName/relays/$relayName")
                .update(updateData)
                .await()

            Log.d(TAG, "Relay actualizado 24/7: $relayName → $newStatus")
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando relay", e)
        }
    }

    private fun sendLocalBroadcast(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        newStatus: String
    ) {
        val intent = Intent("com.pqsolutions.hdd_monitor.RELAY_UPDATE_24X7").apply {
            putExtra("clientDocName", clientDocName)
            putExtra("panelDocName", panelDocName)
            putExtra("relayName", relayName)
            putExtra("newStatus", newStatus)
            putExtra("timestamp", System.currentTimeMillis())
        }

        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(this)
            .sendBroadcast(intent)
    }

    private fun showRelayNotification(
        clientDocName: String,
        relayName: String,
        newStatus: String,
        message: String
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "relay")
            putExtra("clientDocName", clientDocName)
            putExtra("relayName", relayName)
            putExtra("newStatus", newStatus)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "relay_notifications")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$clientDocName - Cambio de Estado")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setLights(0xFF0000FF.toInt(), 500, 500)
            .build()

        // Usar clientDocName + timestamp para ID único y evitar suplantación
        val uniqueId = "${clientDocName}_relay_${relayName}_${newStatus}_${System.currentTimeMillis()}".hashCode()
        notificationManager.notify(uniqueId, notification)
        Log.d(TAG, "Notificación de relay mostrada: '$message'")
    }

    private fun processEventMessage(data: Map<String, String>) {
        Log.d(TAG, "Procesando evento 24/7: ${data["eventType"]}")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val title = data["title"] ?: data["eventTitle"] ?: "Evento del Sistema"
                val message = data["message"] ?: ""
                val eventId = data["eventId"] ?: ""
                val clientDocName = data["clientDocName"] ?: ""
                val action = data["action"] ?: ""

                Log.d(TAG, "Datos completos del evento: $data")

                if (message.isNotEmpty()) {
                    Log.d(TAG, "Mensaje específico del servidor: '$message'")
                    withContext(Dispatchers.Main) {
                        showEventNotification(title, message, eventId, clientDocName, action)
                    }
                } else {
                    Log.w(TAG, "Evento sin mensaje específico del servidor - datos: $data")
                    val fallbackMessage = "Evento ${data["eventType"] ?: ""} ${action.lowercase()}"
                    withContext(Dispatchers.Main) {
                        showEventNotification(title, fallbackMessage, eventId, clientDocName, action)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error procesando evento", e)
            }
        }
    }

    private fun processStatusMessage(data: Map<String, String>) {
        Log.d(TAG, "Procesando status 24/7: ${data["status"]}")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val clientDocName = data["clientDocName"] ?: ""
                val panelDocName = data["panelDocName"] ?: ""
                val status = data["status"] ?: ""

                Log.d(TAG, "Datos completos del status: $data")
                Log.d(TAG, "Status: $panelDocName → $status")

                if (clientDocName.isNotEmpty() && panelDocName.isNotEmpty()) {
                    updateESP32Status(clientDocName, panelDocName, status)
                }

                if (SHOW_STATUS_NOTIFICATIONS && (status == "ONLINE" || status == "OFFLINE")) {
                    withContext(Dispatchers.Main) {
                        val isOnline = status == "ONLINE"
                        val message = if (isOnline) {
                            "Chip de monitoreo $panelDocName está nuevamente ONLINE"
                        } else {
                            "Chip de monitoreo $panelDocName está OFFLINE"
                        }
                        showStatusNotification(clientDocName, panelDocName, status, message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando status", e)
            }
        }
    }

    private fun processGenericMessage(data: Map<String, String>) {
        Log.d(TAG, "Procesando mensaje genérico 24/7")

        val title = data["title"] ?: data["eventTitle"] ?: "HDD Monitor"
        val message = data["message"] ?: data["eventMessage"] ?: ""

        val finalMessage = if (message.isNotEmpty()) {
            message
        } else {
            val eventType = data["eventType"] ?: ""
            val action = data["action"] ?: ""
            val clientName = data["clientDocName"] ?: ""

            when {
                eventType.isNotEmpty() && action.isNotEmpty() -> {
                    val actionText = when (action) {
                        "CREATE" -> "Se ha creado"
                        "UPDATE" -> "Se ha actualizado"
                        "DELETE" -> "Se ha eliminado"
                        "ACCEPT" -> "Se ha aceptado"
                        "FINISH" -> "Se ha finalizado"
                        else -> "Se ha modificado"
                    }
                    "$actionText un evento de tipo $eventType"
                }
                clientName.isNotEmpty() -> "Nueva notificación de $clientName"
                else -> "Nueva notificación del sistema"
            }
        }

        Log.d(TAG, "Datos completos del mensaje: $data")
        Log.d(TAG, "Mensaje específico del servidor: $finalMessage")

        CoroutineScope(Dispatchers.Main).launch {
            showEventNotification(title, finalMessage, "", "", "")
        }
    }

    private suspend fun updateESP32Status(clientDocName: String, panelDocName: String, status: String) {
        try {
            val esp32Id = firestore
                .document("hdd-monitor/accounts/clients/$clientDocName/panels/$panelDocName")
                .get()
                .await()
                .getString("esp32_id") ?: ""

            if (esp32Id.isNotEmpty()) {
                firestore.document("hdd-monitor/esp32/registered/$esp32Id")
                    .update("status", status)
                    .await()
                Log.d(TAG, "Estado ESP32 actualizado: $esp32Id → $status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando estado ESP32", e)
        }
    }

    private fun showStatusNotification(clientDocName: String, panelDocName: String, status: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isOnline = status == "ONLINE"
                val title = if (isOnline) "Panel Conectado" else "⚠️ Panel Desconectado"

                Log.d(TAG, "Mensaje de status: '$message'")

                withContext(Dispatchers.Main) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    val notification = NotificationCompat.Builder(this@FirebaseMessagingService, "status_notifications")
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setAutoCancel(true)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                        .setVibrate(longArrayOf(0, 500, 250, 500))
                        .setLights(if (isOnline) 0xFF00FF00.toInt() else 0xFFFF4444.toInt(), 500, 500)
                        .build()

                    // Usar clientDocName + timestamp para ID único y evitar suplantación
                    val uniqueId = "${clientDocName}_status_${panelDocName}_${status}_${System.currentTimeMillis()}".hashCode()
                    notificationManager.notify(uniqueId, notification)
                    Log.d(TAG, "Notificación de status mostrada: '$message'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error mostrando notificación de estado", e)
            }
        }
    }

    private fun showEventNotification(
        title: String,
        message: String,
        eventId: String,
        clientDocName: String,
        action: String
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("eventId", eventId)
            putExtra("clientDocName", clientDocName)
            putExtra("action", action)
            putExtra("type", "event")
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "event_notifications")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Usar clientDocName + timestamp para ID único y evitar suplantación
        val uniqueId = "${clientDocName}_event_${eventId}_${action}_${System.currentTimeMillis()}".hashCode()
        notificationManager.notify(uniqueId, notification)
        Log.d(TAG, "Notificación de evento mostrada: '$message'")
    }
}