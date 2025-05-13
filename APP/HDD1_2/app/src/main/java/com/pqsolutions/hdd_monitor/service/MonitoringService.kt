package com.pqsolutions.hdd_monitor.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
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

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "MonitoringServiceChannel"

    companion object {
        private const val TAG = "MonitoringService"
        private var isServiceRunning = false

        fun isRunning() = isServiceRunning
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio creado")
        createNotificationChannel()
        acquireWakeLock()
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Servicio iniciado/reiniciado")

        // Inmediatamente mostrar la notificación para cumplir con el requisito de Android
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error creando notificación inicial", e)
            startForeground(NOTIFICATION_ID, createFallbackNotification())
        }

        // Después de mostrar la notificación, verificar el estado de login
        serviceScope.launch {
            try {
                val isLoggedIn = userRepository.getCurrentUser() != null
                if (!isLoggedIn) {
                    Log.d(TAG, "Usuario no logueado, deteniendo servicio")
                    stopSelf()
                    return@launch
                }

                // Iniciar el monitoreo solo si el usuario está logueado
                startMonitoringLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error verificando estado de login", e)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val isLoggedIn = userRepository.getCurrentUser() != null
                    if (!isLoggedIn) {
                        Log.d(TAG, "Usuario no logueado, deteniendo servicio")
                        stopSelf()
                        break
                    }

                    delay(30_000) // 30 segundos
                } catch (e: Exception) {
                    Log.e(TAG, "Error en el loop de monitoreo", e)
                    delay(5_000) // Esperar 5 segundos antes de reintentar
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Estado del Servicio",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Estado del servicio de monitoreo"
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
            .setContentTitle("HDD Monitor")
            .setContentText("Monitoreando estado del panel")
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
            .setContentTitle("HDD Monitor")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HddMonitor::MonitoringLock"
                ).apply {
                    acquire(24 * 60 * 60 * 1000L) // 24 horas
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adquiriendo WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando WakeLock", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Servicio siendo destruido")
        isServiceRunning = false
        serviceJob.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Tarea removida")
        super.onTaskRemoved(rootIntent)

        // Verificar estado de login antes de reiniciar
        serviceScope.launch {
            try {
                val isLoggedIn = userRepository.getCurrentUser() != null
                if (isLoggedIn) {
                    Log.d(TAG, "Usuario logueado, reiniciando servicio")
                    startService(Intent(applicationContext, MonitoringService::class.java))
                } else {
                    Log.d(TAG, "Usuario no logueado, no se reinicia el servicio")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verificando estado de login", e)
                stopSelf()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.d(TAG, "Memoria baja detectada")
        System.gc()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}