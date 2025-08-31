package com.pqsolutions.hdd_monitor.data.manager

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.pqsolutions.hdd_monitor.data.EventRepository
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.RelayControlRepository
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListenerManager @Inject constructor(
    private val panelRepository: PanelRepository,
    private val relayControlRepository: RelayControlRepository,
    private val notificationRepository: NotificationRepository,
    private val eventRepository: EventRepository,
    private val esp32Repository: ESP32Repository,
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val TAG = "ListenerManager"
        private const val HEALTH_CHECK_INTERVAL = 120000L
        private const val DEEP_HEALTH_CHECK_INTERVAL = 600000L
        private const val SELECTIVE_CLEANUP_INTERVAL = 1800000L
        private const val MEMORY_CHECK_INTERVAL = 180000L
        private const val CONNECTION_TIMEOUT = 30000L
        private const val MAX_MEMORY_USAGE_MB = 512
        private const val MAX_TOTAL_LISTENERS = 150
        private const val EMERGENCY_CLEANUP_THRESHOLD = 0.95
        private const val MAX_RECONNECTION_ATTEMPTS = 20
        private const val BACKOFF_BASE_DELAY = 1000L
        private const val MAX_BACKOFF_DELAY = 60000L
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private val lastHealthCheck = AtomicLong(0)
    private val reconnectionAttempts = AtomicLong(0)
    private var currentScreen = "dashboard"

    private val _systemHealth = MutableStateFlow(SystemHealth())
    val systemHealth: StateFlow<SystemHealth> = _systemHealth.asStateFlow()

    data class SystemHealth(
        val isHealthy: Boolean = true,
        val totalListeners: Int = 0,
        val memoryUsageMB: Long = 0,
        val firebaseConnected: Boolean = true,
        val lastError: String? = null,
        val uptime: Long = 0,
        val reconnectionAttempts: Long = 0,
        val repositoryStats: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    fun setCurrentScreen(screenName: String) {
        val previousScreen = currentScreen
        currentScreen = screenName
        Log.d(TAG, "Screen changed from: $previousScreen to: $screenName")

        if (screenName == "dashboard" && previousScreen != "dashboard") {
            Log.d(TAG, "Returning to dashboard - scheduling listener reactivation")
            managerScope.launch {
                delay(500)
                onReturnedToDashboard()
            }
        }
    }

    private suspend fun onReturnedToDashboard() {
        Log.d(TAG, "Reactivating dashboard listeners after screen change")

        try {
            panelRepository.clearStaleListeners()
            esp32Repository.clearStaleListeners()

            if (!checkFirebaseConnection()) {
                Log.w(TAG, "Firebase connection issue detected, attempting reconnection")
                handleConnectionLoss()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error reactivating dashboard listeners", e)
        }
    }

    fun optimizeFirebaseForLongRunning() {
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .setSslEnabled(true)
                .setHost("firestore.googleapis.com")
                .build()

            firestore.firestoreSettings = settings

            Log.d(TAG, "Firebase optimizado para funcionamiento 24/7")
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizando Firebase", e)
        }
    }

    fun startLongRunningManagement() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Gestor ya está ejecutándose")
            return
        }

        Log.d(TAG, "INICIANDO GESTIÓN 24/7/365 - MODO CONSERVADOR")

        optimizeFirebaseForLongRunning()

        startHealthMonitoring()
        startMemoryMonitoring()
        startSelectiveCleanup()
        startConnectionMonitoring()

        Log.d(TAG, "Sistema de gestión 24/7 iniciado correctamente")
    }

    private fun startHealthMonitoring() {
        managerScope.launch {
            val startTime = System.currentTimeMillis()

            while (isRunning.get()) {
                try {
                    val healthResult = performHealthCheck()
                    val uptime = System.currentTimeMillis() - startTime

                    _systemHealth.value = healthResult.copy(uptime = uptime)
                    lastHealthCheck.set(System.currentTimeMillis())

                    if (uptime % DEEP_HEALTH_CHECK_INTERVAL < HEALTH_CHECK_INTERVAL) {
                        performDeepHealthCheck()
                    }

                    delay(HEALTH_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo de salud", e)
                    updateHealthError("Health monitoring error: ${e.message}")
                    delay(10000)
                }
            }
        }
    }

    private fun startMemoryMonitoring() {
        managerScope.launch {
            while (isRunning.get()) {
                try {
                    val memoryUsage = getMemoryUsage()

                    when {
                        memoryUsage > MAX_MEMORY_USAGE_MB * EMERGENCY_CLEANUP_THRESHOLD -> {
                            Log.e(TAG, "MEMORIA CRÍTICA: ${memoryUsage}MB - LIMPIEZA EMERGENCIA")
                            performEmergencyCleanup()
                            forceGarbageCollection()
                        }
                        memoryUsage > MAX_MEMORY_USAGE_MB * 0.8 -> {
                            Log.w(TAG, "Memoria alta: ${memoryUsage}MB - Limpieza selectiva")
                            performSelectiveCleanup()
                        }
                    }

                    delay(MEMORY_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo de memoria", e)
                    delay(15000)
                }
            }
        }
    }

    private fun startSelectiveCleanup() {
        managerScope.launch {
            while (isRunning.get()) {
                try {
                    delay(SELECTIVE_CLEANUP_INTERVAL)
                    if (currentScreen != "dashboard") {
                        performSelectiveCleanup()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en limpieza selectiva", e)
                }
            }
        }
    }

    private fun startConnectionMonitoring() {
        managerScope.launch {
            while (isRunning.get()) {
                try {
                    val isConnected = checkFirebaseConnection()

                    if (!isConnected) {
                        handleConnectionLoss()
                    } else {
                        reconnectionAttempts.set(0)
                    }

                    delay(CONNECTION_TIMEOUT)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo de conexión", e)
                    delay(10000)
                }
            }
        }
    }

    private suspend fun performHealthCheck(): SystemHealth {
        val totalListeners = getTotalListenersCount()
        val memoryUsage = getMemoryUsage()
        val repositoryStats = getAllRepositoryStats()

        val isHealthy = memoryUsage < MAX_MEMORY_USAGE_MB &&
                totalListeners < MAX_TOTAL_LISTENERS &&
                reconnectionAttempts.get() < MAX_RECONNECTION_ATTEMPTS

        return SystemHealth(
            isHealthy = isHealthy,
            totalListeners = totalListeners,
            memoryUsageMB = memoryUsage,
            firebaseConnected = checkFirebaseConnection(),
            reconnectionAttempts = reconnectionAttempts.get(),
            repositoryStats = repositoryStats
        )
    }

    private suspend fun performDeepHealthCheck() {
        Log.d(TAG, "Realizando check de salud profundo")

        try {
            val panelStats = panelRepository.getListenerStats()
            val relayStats = getRelayControlStats()
            val esp32Stats = getESP32Stats()

            val totalListeners = getTotalListenersCount()
            val memoryUsage = getMemoryUsage()

            Log.d(TAG, """
                ESTADÍSTICAS DEL SISTEMA:
                Memoria: ${memoryUsage}MB / ${MAX_MEMORY_USAGE_MB}MB
                Listeners totales: $totalListeners / $MAX_TOTAL_LISTENERS
                Paneles: ${panelStats["activeListeners"]}
                Relays: ${relayStats["activeListeners"]}
                ESP32s: ${esp32Stats["activeListeners"]}
                Reconexiones: ${reconnectionAttempts.get()}
                Pantalla actual: $currentScreen
            """.trimIndent())

        } catch (e: Exception) {
            Log.e(TAG, "Error en check profundo", e)
        }
    }

    private suspend fun performSelectiveCleanup() {
        Log.d(TAG, "Iniciando limpieza selectiva - conservando listeners activos")

        val beforeStats = getAllRepositoryStats()
        val beforeMemory = getMemoryUsage()

        try {
            if (currentScreen != "dashboard") {
                cleanupStaleListenersOnly()
            }

            if (beforeMemory > MAX_MEMORY_USAGE_MB * 0.7) {
                forceGarbageCollection()
            }

            val afterStats = getAllRepositoryStats()
            val afterMemory = getMemoryUsage()

            Log.d(TAG, "Limpieza selectiva completada - Memoria: ${beforeMemory}MB → ${afterMemory}MB")

        } catch (e: Exception) {
            Log.e(TAG, "Error en limpieza selectiva", e)
        }
    }

    private fun cleanupStaleListenersOnly() {
        try {
            Log.d(TAG, "Limpiando solo listeners obsoletos")

            panelRepository.clearStaleListeners()
            esp32Repository.clearStaleListeners()

            System.gc()

        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando listeners obsoletos", e)
        }
    }

    private fun performEmergencyCleanup() {
        Log.e(TAG, "LIMPIEZA DE EMERGENCIA ACTIVADA")

        try {
            if (currentScreen != "dashboard") {
                panelRepository.clearListeners()
                relayControlRepository.clearListeners()
            }

            notificationRepository.clearListeners()
            eventRepository.clearListeners()

            repeat(3) {
                System.gc()
                Thread.sleep(100)
            }

            Log.w(TAG, "Limpieza de emergencia completada")

            if (currentScreen == "dashboard") {
                managerScope.launch {
                    delay(5000)
                    Log.d(TAG, "Listeners esenciales preservados para dashboard")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en limpieza de emergencia", e)
        }
    }

    private suspend fun handleConnectionLoss() {
        val attempts = reconnectionAttempts.incrementAndGet()

        if (attempts > MAX_RECONNECTION_ATTEMPTS) {
            Log.e(TAG, "Máximo de reintentos alcanzado, realizando reset selectivo")
            performSelectiveCleanup()
            reconnectionAttempts.set(0)
            return
        }

        val backoffDelay = minOf(
            BACKOFF_BASE_DELAY * (1L shl attempts.toInt()),
            MAX_BACKOFF_DELAY
        )

        Log.w(TAG, "Reintento de conexión $attempts/$MAX_RECONNECTION_ATTEMPTS en ${backoffDelay}ms")

        delay(backoffDelay)

        try {
            if (checkFirebaseConnection()) {
                reconnectionAttempts.set(0)
                Log.d(TAG, "Conexión restaurada")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en reintento de conexión", e)
        }
    }

    private suspend fun checkFirebaseConnection(): Boolean {
        return try {
            firestore.collection("hdd-monitor/accounts/clients")
                .limit(1)
                .get()
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getTotalListenersCount(): Int {
        return try {
            val panelListeners = (panelRepository.getListenerStats()["activeListeners"] as? Int) ?: 0
            val relayListeners = (getRelayControlStats()["activeListeners"] as? Int) ?: 0
            val esp32Listeners = (getESP32Stats()["activeListeners"] as? Int) ?: 0
            val notificationListeners = getNotificationListenersCount()
            val eventListeners = getEventListenersCount()

            panelListeners + relayListeners + esp32Listeners + notificationListeners + eventListeners
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo conteo de listeners", e)
            0
        }
    }

    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }

    private fun getAllRepositoryStats(): Map<String, Any> {
        return try {
            mapOf(
                "panel" to panelRepository.getListenerStats(),
                "relayControl" to getRelayControlStats(),
                "esp32" to getESP32Stats(),
                "notification" to getNotificationStats(),
                "event" to getEventStats()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo stats", e)
            emptyMap()
        }
    }

    private fun getRelayControlStats(): Map<String, Any> {
        return try {
            relayControlRepository.getListenerStats()
        } catch (e: Exception) {
            mapOf("activeListeners" to 0)
        }
    }

    private fun getESP32Stats(): Map<String, Any> {
        return try {
            esp32Repository.getListenerStats()
        } catch (e: Exception) {
            mapOf("activeListeners" to 0)
        }
    }

    private fun getNotificationStats(): Map<String, Any> {
        return try {
            notificationRepository.getListenerStats()
        } catch (e: Exception) {
            mapOf("activeListeners" to 0)
        }
    }

    private fun getEventStats(): Map<String, Any> {
        return try {
            mapOf("activeListeners" to 0)
        } catch (e: Exception) {
            mapOf("activeListeners" to 0)
        }
    }

    private fun getNotificationListenersCount(): Int {
        return try {
            (getNotificationStats()["activeListeners"] as? Int) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun getEventListenersCount(): Int {
        return try {
            (getEventStats()["activeListeners"] as? Int) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun forceGarbageCollection() {
        repeat(2) {
            System.gc()
            Thread.sleep(50)
        }
    }

    private fun updateHealthError(error: String) {
        _systemHealth.value = _systemHealth.value.copy(
            isHealthy = false,
            lastError = error,
            timestamp = System.currentTimeMillis()
        )
    }

    fun forceCleanupAll() {
        Log.d(TAG, "Limpieza forzada solicitada")
        managerScope.launch {
            performEmergencyCleanup()
        }
    }

    fun getSystemStats(): Map<String, Any> {
        return mapOf(
            "isRunning" to isRunning.get(),
            "currentScreen" to currentScreen,
            "totalListeners" to getTotalListenersCount(),
            "memoryUsageMB" to getMemoryUsage(),
            "maxMemoryMB" to MAX_MEMORY_USAGE_MB,
            "reconnectionAttempts" to reconnectionAttempts.get(),
            "lastHealthCheck" to lastHealthCheck.get(),
            "repositoryStats" to getAllRepositoryStats()
        )
    }

    fun stopManagement() {
        Log.d(TAG, "Deteniendo gestión 24/7")
        isRunning.set(false)
    }

    fun restartManagement() {
        Log.d(TAG, "Reiniciando gestión 24/7")
        stopManagement()
        Thread.sleep(1000)
        startLongRunningManagement()
    }

    fun forceReconnection() {
        Log.d(TAG, "FORZANDO RECONEXIÓN")
        managerScope.launch {
            handleConnectionLoss()
        }
    }
}