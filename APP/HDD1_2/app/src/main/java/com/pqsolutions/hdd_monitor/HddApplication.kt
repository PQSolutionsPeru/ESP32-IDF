package com.pqsolutions.hdd_monitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.service.MonitoringService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class HddApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "HddApplication"
        private const val SERVICE_CHECK_WORK = "service_check_work"
        private const val MEMORY_CHECK_INTERVAL = 120000L
        private const val MAX_MEMORY_USAGE_MB = 400L
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        initializeFirebaseWithRetry()
        setupFirestore()
        createNotificationChannels()

        applicationScope.launch {
            delay(2000)
            startMonitoringService()
        }

        setupKeepAlive()
        scheduleServiceCheck()
        startMemoryMonitoring()
    }

    private fun initializeFirebaseWithRetry() {
        applicationScope.launch {
            var retries = 0
            val maxRetries = 3

            while (retries < maxRetries) {
                try {
                    FirebaseApp.initializeApp(this@HddApplication)
                    Log.d(TAG, "Firebase inicializado correctamente")
                    initializeMessagingWithRetry()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error inicializando Firebase, intento ${retries + 1}", e)
                    retries++
                    if (retries < maxRetries) {
                        delay(2000L * retries)
                    }
                }
            }
        }
    }

    private suspend fun initializeMessagingWithRetry() {
        try {
            FirebaseMessaging.getInstance().apply {
                isAutoInitEnabled = true

                subscribeToTopic("panel_updates")
                    .addOnSuccessListener {
                        Log.d(TAG, "Suscrito a panel_updates")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error suscribiendo a tópico, se reintentará", e)
                        applicationScope.launch {
                            delay(5000)
                            subscribeToTopic("panel_updates")
                        }
                    }

                subscribeToTopic("relay-status")
                    .addOnSuccessListener {
                        Log.d(TAG, "Suscrito a relay-status")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error suscribiendo a relay-status", e)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando messaging", e)
        }
    }

    private fun setupFirestore() {
        applicationScope.launch {
            try {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .setSslEnabled(true)
                    .setHost("firestore.googleapis.com")
                    .build()

                val firestore = FirebaseFirestore.getInstance()
                firestore.firestoreSettings = settings

                Log.d(TAG, "Firestore configurado correctamente")
            } catch (e: Exception) {
                Log.e(TAG, "Error configurando Firestore", e)
                try {
                    val basicSettings = FirebaseFirestoreSettings.Builder().build()
                    FirebaseFirestore.getInstance().firestoreSettings = basicSettings
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "Error crítico configurando Firestore", fallbackError)
                }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                "MonitoringServiceChannel",
                "Estado del Servicio",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Indica que el servicio de monitoreo está activo"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = NotificationManager.IMPORTANCE_MIN
            }
            notificationManager.createNotificationChannel(serviceChannel)

            val eventChannel = NotificationChannel(
                "event_notifications",
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_description)
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
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

    private fun startMonitoringService() {
        try {
            val serviceIntent = Intent(this, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "Servicio de monitoreo iniciado")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando servicio de monitoreo", e)
            applicationScope.launch {
                delay(5000)
                startMonitoringService()
            }
        }
    }

    private fun setupKeepAlive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "La app no está excluida de optimización de batería")
            }
        }
    }

    private fun scheduleServiceCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val serviceCheckWork = PeriodicWorkRequestBuilder<ServiceCheckWorker>(
            2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                30000L,
                TimeUnit.MILLISECONDS
            )
            .addTag("service_check")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SERVICE_CHECK_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            serviceCheckWork
        )

        Log.d(TAG, "Service check work programado cada 2 horas")
    }

    private fun startMemoryMonitoring() {
        applicationScope.launch {
            while (true) {
                try {
                    val runtime = Runtime.getRuntime()
                    val memoryUsage = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024

                    if (memoryUsage > MAX_MEMORY_USAGE_MB) {
                        Log.w(TAG, "Memoria alta detectada: ${memoryUsage}MB")
                        System.gc()
                    }

                    delay(MEMORY_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo de memoria", e)
                    delay(60000)
                }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Memoria baja detectada")
        System.gc()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w(TAG, "Memoria crítica detectada")
                System.gc()
            }
            TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(TAG, "Memoria baja detectada")
                System.gc()
            }
        }
    }
}