package com.pqsolutions.hdd_monitor.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : Service() {
    @Inject
    lateinit var userRepository: UserRepository

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "MonitoringServiceChannel"

    companion object {
        private const val TAG = "MonitoringService"
        private var isServiceRunning = false
        private const val SESSION_CHECK_INTERVAL = 300_000L // 5 minutos
        private const val FCM_HEALTH_CHECK_INTERVAL = 1_800_000L // 30 minutos

        fun isRunning() = isServiceRunning
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio 24/7 creado")
        createNotificationChannel()
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Servicio 24/7 iniciado (modo FCM-only)")

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error creando notificación inicial", e)
            startForeground(NOTIFICATION_ID, createFallbackNotification())
        }

        startOptimizedMonitoring()
        startPeriodicFCMValidation()

        return START_STICKY
    }

    private fun startOptimizedMonitoring() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val isLoggedIn = userRepository.getCurrentUser() != null
                    if (!isLoggedIn) {
                        Log.d(TAG, "Usuario no logueado, deteniendo servicio")
                        stopSelf()
                        break
                    }

                    Log.d(TAG, "Verificación de sesión OK, próxima en 5 minutos")
                    delay(SESSION_CHECK_INTERVAL)

                } catch (e: Exception) {
                    Log.e(TAG, "Error en verificación de sesión", e)
                    delay(60_000L) // 1 minuto en caso de error
                }
            }
        }
    }

    private fun startPeriodicFCMValidation() {
        serviceScope.launch {
            while (isActive) {
                try {
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "FCM token válido - sistema 24/7 operativo")
                        } else {
                            Log.w(TAG, "Advertencia: Problema con FCM token", task.exception)
                        }
                    }

                    delay(FCM_HEALTH_CHECK_INTERVAL)

                } catch (e: Exception) {
                    Log.e(TAG, "Error en validación FCM", e)
                    delay(300_000L) // 5 minutos en caso de error
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sistema 24/7 (Optimizado)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Monitoreo optimizado para funcionamiento continuo"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HDD Monitor 24/7")
            .setContentText("Sistema optimizado activo (FCM-only)")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .build()
    }

    private fun createFallbackNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HDD Monitor 24/7")
            .setContentText("Sistema optimizado")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "Servicio 24/7 optimizado siendo destruido")
        isServiceRunning = false
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Tarea removida - verificando reinicio automático")
        super.onTaskRemoved(rootIntent)

        serviceScope.launch {
            try {
                val isLoggedIn = userRepository.getCurrentUser() != null
                if (isLoggedIn) {
                    Log.d(TAG, "Usuario logueado, programando reinicio del servicio")
                    // Pequeña pausa antes de reiniciar
                    delay(2000)
                    startService(Intent(applicationContext, MonitoringService::class.java))
                } else {
                    Log.d(TAG, "Usuario no logueado, no se reinicia el servicio")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verificando estado para reinicio", e)
                stopSelf()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.d(TAG, "Memoria baja detectada - activando limpieza")
        System.gc()
    }

    fun getServiceStats(): Map<String, Any> {
        return mapOf(
            "isRunning" to isServiceRunning,
            "sessionCheckInterval" to SESSION_CHECK_INTERVAL,
            "fcmCheckInterval" to FCM_HEALTH_CHECK_INTERVAL,
            "wakeLockUsed" to false,
            "optimizedFor24x7" to true,
            "timestamp" to System.currentTimeMillis()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
}