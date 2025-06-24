package com.pqsolutions.hdd_monitor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FCM Service Created")

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
                description = "Notificaciones sobre eventos programados"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(eventChannel)

            val statusChannel = NotificationChannel(
                "status_notifications",
                "Estado del Panel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones sobre cambios en el estado del panel"
                enableLights(true)
                enableVibration(true)
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
            }
            notificationManager.createNotificationChannel(relayChannel)
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "Nuevo token FCM: $token")

        CoroutineScope(Dispatchers.IO).launch {
            sendTokenToServerWithRetry(token)
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
                        Log.d(TAG, "Token FCM actualizado exitosamente")
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
        Log.d(TAG, "=================== INICIO MENSAJE ===================")
        Log.d(TAG, "Mensaje recibido desde: ${remoteMessage.from}")
        Log.d(TAG, "Datos completos del mensaje: ${remoteMessage.data}")

        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "Notificación: ${notification.title} - ${notification.body}")
            Log.d(TAG, "Priority: ${remoteMessage.priority}")
            Log.d(TAG, "Original Priority: ${remoteMessage.originalPriority}")
        }
        Log.d(TAG, "=================== FIN MENSAJE ===================")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val messageType = data["type"] ?: ""

            when {
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
                    when {
                        data.containsKey("eventId") || data.containsKey("action") && (
                                data["action"] == "CREATE" ||
                                        data["action"] == "DELETE" ||
                                        data["action"] == "ACCEPT" ||
                                        data["action"] == "FINISH"
                                ) -> {
                            processEventMessage(data)
                        }
                        data.containsKey("relayName") || data.containsKey("oldStatus") && data.containsKey("newStatus") -> {
                            processRelayMessage(data)
                        }
                        data.containsKey("status") && !data.containsKey("eventId") -> {
                            processStatusMessage(data)
                        }
                        else -> {
                            Log.d(TAG, "Tipo de mensaje desconocido, tratando como evento genérico")
                            val title = data["title"] ?: "Nueva notificación"
                            val message = data["message"] ?: "Ha recibido una nueva notificación"
                            val eventId = data["eventId"] ?: ""
                            val clientDocName = data["clientDocName"] ?: ""
                            val action = data["action"] ?: ""

                            showEventNotification(
                                title = title,
                                message = message,
                                notificationId = System.currentTimeMillis().toString(),
                                eventId = eventId,
                                clientDocName = clientDocName,
                                action = action
                            )
                        }
                    }
                }
            }
        }
    }

    private fun processEventMessage(data: Map<String, String>) {
        Log.d(TAG, "=================== INICIO EVENTO ===================")
        Log.d(TAG, "Datos de evento recibidos: $data")

        val clientDocName = data["clientDocName"] ?: ""
        val eventId = data["eventId"] ?: ""
        val eventType = data["eventType"] ?: ""
        val status = data["status"] ?: ""
        val action = data["action"] ?: ""
        val panelDocName = data["panelDocName"] ?: data["panel_id"] ?: ""
        val panelName = data["panelName"] ?: data["panel_name"] ?: ""

        val title = data["title"] ?: data["eventTitle"] ?: ""

        var messageFromData = data["message"] ?: ""

        if (panelName.isNotEmpty() && panelDocName.isNotEmpty() && messageFromData.contains(panelDocName)) {
            messageFromData = messageFromData.replace(panelDocName, panelName)
        }

        val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

        Log.d(TAG, "Datos extraídos para mensaje de evento:" +
                "\n- clientDocName: $clientDocName" +
                "\n- eventId: $eventId" +
                "\n- eventType: $eventType" +
                "\n- status: $status" +
                "\n- action: $action" +
                "\n- title: $title" +
                "\n- messageFromData: $messageFromData")

        if (SHOW_VISUAL_NOTIFICATIONS) {
            val notificationTitle = when (action) {
                "CREATE" -> "Nuevo evento $eventType"
                "ACCEPT" -> "Evento $eventType aceptado"
                "FINISH" -> "Evento $eventType finalizado"
                "DELETE" -> "Evento $eventType eliminado"
                else -> "Evento $eventType"
            }

            val notificationMessage = messageFromData.ifEmpty {
                when (action) {
                    "CREATE" -> if (title.isNotEmpty()) "Se ha creado un nuevo evento: \"$title\"" else "Se ha creado un nuevo evento"
                    "ACCEPT" -> if (title.isNotEmpty()) "El evento \"$title\" ha sido aceptado" else "El evento ha sido aceptado"
                    "FINISH" -> if (title.isNotEmpty()) "El evento \"$title\" ha sido finalizado" else "El evento ha sido finalizado"
                    "DELETE" -> if (title.isNotEmpty()) "Se ha eliminado el evento \"$title\"" else "Se ha eliminado el evento"
                    else -> if (title.isNotEmpty()) "Actualización del evento \"$title\"" else "Actualización de evento"
                }
            }

            val notificationId = "event_${eventId}_${action}".hashCode()

            showEventNotification(
                title = notificationTitle,
                message = notificationMessage,
                notificationId = notificationId.toString(),
                eventId = eventId,
                clientDocName = clientDocName,
                action = action
            )
        }

        Log.d(TAG, "=================== FIN EVENTO ===================")
    }

    private fun showEventNotification(
        title: String,
        message: String,
        notificationId: String,
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

        val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, pendingIntentFlag
        )

        val channelId = "event_notifications"

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId.hashCode(), notificationBuilder.build())

        Log.d(TAG, "Notificación de evento mostrada: $title - $message")
    }

    private fun processStatusMessage(data: Map<String, String>) {
        val clientDocName = data["clientDocName"] ?: ""
        val panelDocName = data["panelDocName"] ?: ""
        val status = data["status"] ?: ""
        val timestamp = data["timestamp"]?.toLongOrNull() ?: Date().time

        Log.d(TAG, "Datos de estado recibidos: $data")
        Log.d(TAG, "Datos extraídos para mensaje de status:" +
                "\n- clientDocName: $clientDocName" +
                "\n- panelDocName: $panelDocName" +
                "\n- status: $status")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (clientDocName.isNotEmpty() && panelDocName.isNotEmpty()) {
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
                            Log.d(TAG, "Estado de ESP32 $esp32Id actualizado a $status en Firestore")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error actualizando estado en Firestore", e)
                    }
                }

                if (SHOW_STATUS_NOTIFICATIONS && (status == "ONLINE" || status == "OFFLINE")) {
                    showStatusNotification(clientDocName, panelDocName, status)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando mensaje de status", e)
            }
        }
    }

    private fun showStatusNotification(clientDocName: String, panelDocName: String, status: String) {
        val isOnline = status == "ONLINE"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val panelResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName/panels/$panelDocName")
                        .get()
                        .await()
                }

                val clientResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName")
                        .get()
                        .await()
                }

                val panelName = panelResult.getOrNull()?.getString("name") ?: panelDocName
                val clientName = clientResult.getOrNull()?.getString("name") ?: clientDocName

                val title = if (isOnline) "Panel Nuevamente ONLINE" else "Alerta: Panel OFFLINE"
                val message = if (isOnline)
                    "El panel \"$panelName\" del cliente $clientName está nuevamente ONLINE"
                else
                    "El panel \"$panelName\" del cliente $clientName está OFFLINE"

                withContext(Dispatchers.Main) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    val intent = Intent(this@FirebaseMessagingService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("clientDocName", clientDocName)
                        putExtra("panelDocName", panelDocName)
                        putExtra("status", status)
                        putExtra("type", "status")
                    }

                    val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }

                    val pendingIntent = PendingIntent.getActivity(
                        this@FirebaseMessagingService, 0, intent, pendingIntentFlag
                    )

                    val channelId = "status_notifications"
                    val notificationBuilder = NotificationCompat.Builder(this@FirebaseMessagingService, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

                    val notificationId = "${panelDocName}_${status}".hashCode()

                    notificationManager.notify(notificationId, notificationBuilder.build())
                    Log.d(TAG, "Notificación de estado mostrada: $status para panel $panelName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error mostrando notificación de estado", e)
            }
        }
    }

    private fun processRelayMessage(data: Map<String, String>) {
        Log.d(TAG, "=================== INICIO RELAY ===================")
        Log.d(TAG, "Datos de relay recibidos: $data")

        val clientDocName = data["clientDocName"] ?: ""
        val panelDocName = data["panelDocName"] ?: ""
        val type = data["type"] ?: ""

        if (type == "status") {
            val status = data["status"] ?: ""
            processStatusMessage(data)
            Log.d(TAG, "=================== FIN RELAY ===================")
            return
        }

        val relayName = data["relayName"] ?: ""
        val oldStatus = data["oldStatus"] ?: ""
        val newStatus = data["newStatus"] ?: ""
        val messageFromServer = data["message"] ?: ""
        val timestamp = data["timestamp"]?.toLongOrNull() ?: Date().time

        Log.d(TAG, "Datos extraídos para mensaje de relay:" +
                "\n- clientDocName: $clientDocName" +
                "\n- panelDocName: $panelDocName" +
                "\n- relayName: $relayName" +
                "\n- oldStatus: $oldStatus" +
                "\n- newStatus: $newStatus" +
                "\n- messageFromServer: $messageFromServer")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (clientDocName.isNotEmpty() && panelDocName.isNotEmpty() && relayName.isNotEmpty()) {
                    try {
                        val dateTime = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))

                        val updateData = mapOf(
                            "status" to newStatus,
                            "date_time" to dateTime,
                            "lastUpdate" to com.google.firebase.Timestamp.now(),
                            "commandSource" to "fcm"
                        )

                        firestore.document("hdd-monitor/accounts/clients/$clientDocName/panels/$panelDocName/relays/$relayName")
                            .update(updateData)
                            .await()

                        Log.d(TAG, "Estado de relay $relayName actualizado a $newStatus en Firestore")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error actualizando relay en Firestore", e)
                    }
                }

                val isEsp32 = relayName.equals("Sistema", ignoreCase = true)
                if (SHOW_VISUAL_NOTIFICATIONS && !isEsp32) {
                    showRelayNotificationWithMessage(clientDocName, panelDocName, relayName, oldStatus, newStatus, messageFromServer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando mensaje de relay", e)
            }
        }

        Log.d(TAG, "=================== FIN RELAY ===================")
    }

    private fun showRelayNotificationWithMessage(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        oldStatus: String,
        newStatus: String,
        messageFromServer: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val clientResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName")
                        .get()
                        .await()
                }

                val clientName = clientResult.getOrNull()?.getString("name") ?: clientDocName
                val title = "$clientName - Cambio de Estado"

                val message = if (messageFromServer.isNotEmpty()) {
                    messageFromServer
                } else {
                    "Cambio de estado detectado en relay"
                }

                withContext(Dispatchers.Main) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    val intent = Intent(this@FirebaseMessagingService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("clientDocName", clientDocName)
                        putExtra("panelDocName", panelDocName)
                        putExtra("relayName", relayName)
                        putExtra("newStatus", newStatus)
                        putExtra("type", "relay")
                    }

                    val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }

                    val pendingIntent = PendingIntent.getActivity(
                        this@FirebaseMessagingService, 0, intent, pendingIntentFlag
                    )

                    val channelId = "relay_notifications"
                    val notificationBuilder = NotificationCompat.Builder(this@FirebaseMessagingService, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

                    val notificationId = "${panelDocName}_${relayName}_${newStatus}".hashCode()

                    notificationManager.notify(notificationId, notificationBuilder.build())
                    Log.d(TAG, "Notificación de relay mostrada: $message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error mostrando notificación de relay", e)
            }
        }
    }

    private fun showRelayNotification(
        clientDocName: String,
        panelDocName: String,
        relayName: String,
        oldStatus: String,
        newStatus: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val panelResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName/panels/$panelDocName")
                        .get()
                        .await()
                }

                val clientResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName")
                        .get()
                        .await()
                }

                val panelName = panelResult.getOrNull()?.getString("name") ?: panelDocName
                val clientName = clientResult.getOrNull()?.getString("name") ?: clientDocName

                val title = "$clientName - Cambio de Estado"
                val message = "El relay $relayName del panel \"$panelName\" ha cambiado de $oldStatus a $newStatus"

                withContext(Dispatchers.Main) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    val intent = Intent(this@FirebaseMessagingService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("clientDocName", clientDocName)
                        putExtra("panelDocName", panelDocName)
                        putExtra("relayName", relayName)
                        putExtra("newStatus", newStatus)
                        putExtra("type", "relay")
                    }

                    val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }

                    val pendingIntent = PendingIntent.getActivity(
                        this@FirebaseMessagingService, 0, intent, pendingIntentFlag
                    )

                    val channelId = "relay_notifications"
                    val notificationBuilder = NotificationCompat.Builder(this@FirebaseMessagingService, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

                    val notificationId = "${panelDocName}_${relayName}_${newStatus}".hashCode()

                    notificationManager.notify(notificationId, notificationBuilder.build())
                    Log.d(TAG, "Notificación de relay mostrada: $relayName -> $newStatus para panel $panelName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error mostrando notificación de relay", e)
            }
        }
    }

    companion object {
        private const val TAG = "FirebaseMessagingService"
    }
}