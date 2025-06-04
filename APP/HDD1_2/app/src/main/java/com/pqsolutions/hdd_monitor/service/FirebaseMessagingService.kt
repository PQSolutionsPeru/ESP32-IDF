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
import com.pqsolutions.hdd_monitor.util.StatusUpdateManager
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

    // Configuración para mostrar notificaciones visuales
    private val SHOW_VISUAL_NOTIFICATIONS = true
    private val SHOW_STATUS_NOTIFICATIONS = true

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FCM Service Created")

        // Crear canales de notificación
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Canal para notificaciones de eventos
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

            // Canal para notificaciones de estado
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

            // Canal para notificaciones de relay
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

        // Enviar token al servidor con reintentos
        CoroutineScope(Dispatchers.IO).launch {
            sendTokenToServerWithRetry(token)
        }
    }

    private suspend fun sendTokenToServerWithRetry(token: String) {
        var retries = 0
        val maxRetries = 5

        while (retries < maxRetries) {
            try {
                // Verificar conectividad
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "Sin conexión de red, esperando...")
                    delay(5000)
                    continue
                }

                // Actualizar token en el repositorio
                userRepository.updateFCMToken(token)
                    .onSuccess {
                        Log.d(TAG, "Token FCM actualizado exitosamente")
                    }
                    .onFailure { error ->
                        throw error
                    }

                break // Éxito, salir del loop

            } catch (e: Exception) {
                Log.e(TAG, "Error enviando token, intento ${retries + 1}", e)
                retries++
                if (retries < maxRetries) {
                    // Backoff exponencial
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

        // Procesar notificación
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "Notificación: ${notification.title} - ${notification.body}")
            Log.d(TAG, "Priority: ${remoteMessage.priority}")
            Log.d(TAG, "Original Priority: ${remoteMessage.originalPriority}")
        }
        Log.d(TAG, "=================== FIN MENSAJE ===================")

        // Procesar datos específicos
        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            // Determinar el tipo de mensaje basado en varios factores
            val messageType = data["type"] ?: ""

            when {
                // Si es explícitamente de tipo "event" o contiene campos típicos de eventos
                messageType == "event" || data.containsKey("eventId") || data.containsKey("eventType") -> {
                    processEventMessage(data)
                }
                // Si es explícitamente de tipo "status"
                messageType == "status" -> {
                    processStatusMessage(data)
                }
                // Si es explícitamente de tipo "relay"
                messageType == "relay" -> {
                    processRelayMessage(data)
                }
                // En caso de duda, intentar inferir el tipo basado en los campos presentes
                else -> {
                    when {
                        data.containsKey("eventId") || data.containsKey("action") && (
                                data["action"] == "CREATE" ||
                                        data["action"] == "DELETE" ||
                                        data["action"] == "ACCEPT" ||
                                        data["action"] == "FINISH"
                                ) -> {
                            // Probablemente sea un evento
                            processEventMessage(data)
                        }
                        data.containsKey("relayName") || data.containsKey("oldStatus") && data.containsKey("newStatus") -> {
                            // Probablemente sea una actualización de relay
                            processRelayMessage(data)
                        }
                        data.containsKey("status") && !data.containsKey("eventId") -> {
                            // Probablemente sea una actualización de estado
                            processStatusMessage(data)
                        }
                        else -> {
                            // No podemos determinar el tipo, usar eventMessage como fallback
                            Log.d(TAG, "Tipo de mensaje desconocido, tratando como evento genérico")
                            val title = data["title"] ?: "Nueva notificación"
                            val message = data["message"] ?: "Ha recibido una nueva notificación"
                            val eventId = data["eventId"] ?: ""
                            val clientDocName = data["clientDocName"] ?: ""
                            val action = data["action"] ?: ""

                            // Usar showEventNotification como fallback
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

        // Extraer datos importantes
        val clientDocName = data["clientDocName"] ?: ""
        val eventId = data["eventId"] ?: ""
        val eventType = data["eventType"] ?: ""
        val status = data["status"] ?: ""
        val action = data["action"] ?: ""
        val panelDocName = data["panelDocName"] ?: data["panel_id"] ?: ""
        val panelName = data["panelName"] ?: data["panel_name"] ?: ""

        // Intentar obtener el título de diferentes lugares
        val title = data["title"] ?: data["eventTitle"] ?: ""

        // Si hay mensaje en los datos, usarlo; de lo contrario, construir uno
        var messageFromData = data["message"] ?: ""

        // Reemplazar el ID del panel con el nombre del panel en el mensaje
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

        // Mostrar notificación si está habilitado
        if (SHOW_VISUAL_NOTIFICATIONS) {
            val notificationTitle = when (action) {
                "CREATE" -> "Nuevo evento $eventType"
                "ACCEPT" -> "Evento $eventType aceptado"
                "FINISH" -> "Evento $eventType finalizado"
                "DELETE" -> "Evento $eventType eliminado"
                else -> "Evento $eventType"
            }

            // Usar el mensaje formateado
            val notificationMessage = messageFromData.ifEmpty {
                when (action) {
                    "CREATE" -> if (title.isNotEmpty()) "Se ha creado un nuevo evento: \"$title\"" else "Se ha creado un nuevo evento"
                    "ACCEPT" -> if (title.isNotEmpty()) "El evento \"$title\" ha sido aceptado" else "El evento ha sido aceptado"
                    "FINISH" -> if (title.isNotEmpty()) "El evento \"$title\" ha sido finalizado" else "El evento ha sido finalizado"
                    "DELETE" -> if (title.isNotEmpty()) "Se ha eliminado el evento \"$title\"" else "Se ha eliminado el evento"
                    else -> if (title.isNotEmpty()) "Actualización del evento \"$title\"" else "Actualización de evento"
                }
            }

            // Usar un ID único basado en el eventId y la acción
            val notificationId = "event_${eventId}_${action}".hashCode()

            // Mostrar notificación visual al usuario
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

        // Usar un canal específico para eventos
        val channelId = "event_notifications"

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

        // Mostrar notificación
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

        // Solo emitir actualización de estado, no generar notificación visual
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Emitir actualización para UI
                StatusUpdateManager.emitEsp32StatusUpdate(
                    panelDocName = panelDocName,
                    newStatus = status
                )
                Log.d(TAG, "Evento de actualización de ESP32 emitido: $panelDocName -> $status")

                // 2. Actualizar el estado en la base de datos local
                if (clientDocName.isNotEmpty() && panelDocName.isNotEmpty()) {
                    try {
                        // Actualizar directamente el estado en Firestore
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

                // 3. Si tiene SHOW_STATUS_NOTIFICATIONS activado
                if (SHOW_STATUS_NOTIFICATIONS && (status == "ONLINE" || status == "OFFLINE")) {
                    showStatusNotification(clientDocName, panelDocName, status)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando mensaje de status", e)
            }
        }
    }

    // Método específico para mostrar notificaciones de estado
    private fun showStatusNotification(clientDocName: String, panelDocName: String, status: String) {
        // Determinar el título y mensaje según el estado
        val isOnline = status == "ONLINE"

        // Ejecutar en hilo de IO para acceder a Firestore
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Obtener información del panel
                val panelResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName/panels/$panelDocName")
                        .get()
                        .await()
                }

                // 2. Obtener información del cliente
                val clientResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName")
                        .get()
                        .await()
                }

                // 3. Extraer nombres
                val panelName = panelResult.getOrNull()?.getString("name") ?: panelDocName
                val clientName = clientResult.getOrNull()?.getString("name") ?: clientDocName

                // 4. Crear el mensaje de notificación
                val title = if (isOnline) "Panel Nuevamente ONLINE" else "Alerta: Panel OFFLINE"
                val message = if (isOnline)
                    "El panel \"$panelName\" del cliente $clientName está nuevamente ONLINE"
                else
                    "El panel \"$panelName\" del cliente $clientName está OFFLINE"

                // 5. Mostrar la notificación (en el hilo principal)
                withContext(Dispatchers.Main) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    // Intent para abrir la app
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

                    // Crear la notificación
                    val channelId = "status_notifications"
                    val notificationBuilder = NotificationCompat.Builder(this@FirebaseMessagingService, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

                    // Generar un ID único basado en el panelId y estado
                    val notificationId = "${panelDocName}_${status}".hashCode()

                    // Mostrar la notificación
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

        // Extraer datos importantes
        val clientDocName = data["clientDocName"] ?: ""
        val panelDocName = data["panelDocName"] ?: ""
        val type = data["type"] ?: ""

        // Si es un mensaje de estado explícito
        if (type == "status") {
            val status = data["status"] ?: ""
            processStatusMessage(data)
            Log.d(TAG, "=================== FIN RELAY ===================")
            return
        }

        // Es un mensaje de relay normal
        val relayName = data["relayName"] ?: ""
        val oldStatus = data["oldStatus"] ?: ""
        val newStatus = data["newStatus"] ?: ""
        val timestamp = data["timestamp"]?.toLongOrNull() ?: Date().time

        Log.d(TAG, "Datos extraídos para mensaje de relay:" +
                "\n- clientDocName: $clientDocName" +
                "\n- panelDocName: $panelDocName" +
                "\n- relayName: $relayName" +
                "\n- oldStatus: $oldStatus" +
                "\n- newStatus: $newStatus")

        // Varias operaciones en un coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Emitir actualización para UI
                val isEsp32 = relayName.equals("Sistema", ignoreCase = true)
                if (isEsp32) {
                    StatusUpdateManager.emitEsp32StatusUpdate(
                        panelDocName = panelDocName,
                        newStatus = newStatus
                    )
                } else {
                    StatusUpdateManager.emitRelayStatusUpdate(
                        panelDocName = panelDocName,
                        relayName = relayName,
                        newStatus = newStatus
                    )
                }
                Log.d(TAG, "Evento de actualización emitido: $panelDocName -> $newStatus (${if (isEsp32) "ESP32" else relayName})")

                // 2. Mostrar notificación visual si está habilitado
                if (SHOW_VISUAL_NOTIFICATIONS && !isEsp32) {
                    // Solo mostrar para relays normales, no para el Sistema
                    showRelayNotification(clientDocName, panelDocName, relayName, oldStatus, newStatus)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando mensaje de relay", e)
            }
        }

        Log.d(TAG, "=================== FIN RELAY ===================")
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
                // 1. Obtener información del panel
                val panelResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName/panels/$panelDocName")
                        .get()
                        .await()
                }

                // 2. Obtener información del cliente
                val clientResult = kotlin.runCatching {
                    firestore.document("hdd-monitor/accounts/clients/$clientDocName")
                        .get()
                        .await()
                }

                // 3. Extraer nombres
                val panelName = panelResult.getOrNull()?.getString("name") ?: panelDocName
                val clientName = clientResult.getOrNull()?.getString("name") ?: clientDocName

                // 4. Crear el mensaje de notificación
                val title = "$clientName - Cambio de Estado"
                val message = "El relay $relayName del panel \"$panelName\" ha cambiado de $oldStatus a $newStatus"

                // 5. Mostrar la notificación (en el hilo principal)
                withContext(Dispatchers.Main) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    // Intent para abrir la app
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

                    // Crear la notificación
                    val channelId = "relay_notifications"
                    val notificationBuilder = NotificationCompat.Builder(this@FirebaseMessagingService, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

                    // Generar un ID único basado en el relay y su estado
                    val notificationId = "${panelDocName}_${relayName}_${newStatus}".hashCode()

                    // Mostrar la notificación
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