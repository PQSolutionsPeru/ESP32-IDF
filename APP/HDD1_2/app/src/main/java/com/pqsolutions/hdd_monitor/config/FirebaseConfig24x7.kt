package com.pqsolutions.hdd_monitor.config

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.data.manager.ListenerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseConfig24x7 @Inject constructor(
    private val context: Context,
    private val listenerManager: ListenerManager
) {
    companion object {
        private const val TAG = "FirebaseConfig24x7"
        private const val CONNECTION_CHECK_INTERVAL = 30000L
        private const val MAX_RETRY_ATTEMPTS = 10
        private const val RETRY_DELAY_MS = 2000L
        private const val CACHE_SIZE_MB = 100L * 1024 * 1024

        private val CRITICAL_TOPICS = listOf(
            "relay-status",
            "relay-control",
            "panel_updates",
            "fire-alert",
            "emergency-notifications"
        )
    }

    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false
    private var connectionHealthy = true

    suspend fun initializeFor24x7() {
        if (isInitialized) {
            Log.w(TAG, "Firebase ya inicializado")
            return
        }

        Log.d(TAG, "INICIANDO CONFIGURACIÓN FIREBASE 24/7/365")

        try {
            initializeFirebaseApp()
            configureFirestoreFor24x7()
            configureFCMFor24x7()
            startConnectionMonitoring()
            validateConfiguration()

            isInitialized = true
            Log.d(TAG, "FIREBASE CONFIGURADO EXITOSAMENTE PARA 24/7")

        } catch (e: Exception) {
            Log.e(TAG, "Error crítico en inicialización Firebase", e)
            throw FirebaseConfigurationException("Failed to initialize Firebase for 24/7 operation", e)
        }
    }

    private suspend fun initializeFirebaseApp() {
        var attempts = 0
        while (attempts < MAX_RETRY_ATTEMPTS) {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context)
                    Log.d(TAG, "FirebaseApp inicializado")
                } else {
                    Log.d(TAG, "FirebaseApp ya existe")
                }
                return
            } catch (e: Exception) {
                attempts++
                Log.e(TAG, "Error inicializando FirebaseApp (intento $attempts)", e)
                if (attempts >= MAX_RETRY_ATTEMPTS) throw e
                delay(RETRY_DELAY_MS * attempts)
            }
        }
    }

    private fun configureFirestoreFor24x7() {
        try {
            val firestore = FirebaseFirestore.getInstance()

            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(CACHE_SIZE_MB)
                .setSslEnabled(true)
                .setHost("firestore.googleapis.com")
                .build()

            firestore.firestoreSettings = settings
            FirebaseFirestore.setLoggingEnabled(false)

            Log.d(TAG, "Firestore configurado: Cache ${CACHE_SIZE_MB / 1024 / 1024}MB, SSL habilitado")

        } catch (e: Exception) {
            Log.e(TAG, "Error configurando Firestore", e)
            throw e
        }
    }

    private suspend fun configureFCMFor24x7() {
        try {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = true
            subscribeToCriticalTopics(messaging)
            Log.d(TAG, "FCM configurado para notificaciones críticas")
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando FCM", e)
        }
    }

    private suspend fun subscribeToCriticalTopics(messaging: FirebaseMessaging) {
        CRITICAL_TOPICS.forEach { topic ->
            var attempts = 0
            while (attempts < 3) {
                try {
                    messaging.subscribeToTopic(topic).await()
                    Log.d(TAG, "Suscrito a tópico crítico: $topic")
                    break
                } catch (e: Exception) {
                    attempts++
                    Log.w(TAG, "Error suscribiendo a $topic (intento $attempts): ${e.message}")
                    if (attempts < 3) {
                        delay(1000L * attempts)
                    }
                }
            }
        }
    }

    private fun startConnectionMonitoring() {
        configScope.launch {
            while (true) {
                try {
                    val isConnected = checkFirebaseConnection()

                    if (!isConnected && connectionHealthy) {
                        Log.w(TAG, "Pérdida de conexión Firebase detectada")
                        connectionHealthy = false
                        handleConnectionLoss()
                    } else if (isConnected && !connectionHealthy) {
                        Log.d(TAG, "Conexión Firebase restaurada")
                        connectionHealthy = true
                        onConnectionRestored()
                    }

                    delay(CONNECTION_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo de conexión", e)
                    delay(10000)
                }
            }
        }
    }

    private suspend fun checkFirebaseConnection(): Boolean {
        return try {
            val firestore = FirebaseFirestore.getInstance()

            firestore.collection("hdd-monitor")
                .limit(1)
                .get(Source.SERVER)
                .await()

            true
        } catch (e: Exception) {
            Log.d(TAG, "Test de conexión falló: ${e.message}")
            false
        }
    }

    private suspend fun handleConnectionLoss() {
        Log.w(TAG, "Manejando pérdida de conexión Firebase")

        try {
            listenerManager.forceReconnection()

            var attempt = 1
            while (attempt <= MAX_RETRY_ATTEMPTS && !connectionHealthy) {
                delay(RETRY_DELAY_MS * attempt)

                if (checkFirebaseConnection()) {
                    connectionHealthy = true
                    Log.d(TAG, "Reconexión exitosa en intento $attempt")
                    break
                }

                attempt++
                Log.d(TAG, "Reintento de conexión $attempt/$MAX_RETRY_ATTEMPTS")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en manejo de pérdida de conexión", e)
        }
    }

    private suspend fun onConnectionRestored() {
        try {
            validateConfiguration()
            configureFCMFor24x7()
            Log.d(TAG, "Configuración validada tras reconexión")
        } catch (e: Exception) {
            Log.e(TAG, "Error validando tras reconexión", e)
        }
    }

    private suspend fun validateConfiguration() {
        try {
            Log.d(TAG, "Validando configuración Firebase...")

            val firestore = FirebaseFirestore.getInstance()
            val settings = firestore.firestoreSettings

            Log.d(TAG, """
                CONFIGURACIÓN FIRESTORE:
                Persistencia: ${settings.isPersistenceEnabled}
                Cache: ${settings.cacheSizeBytes / 1024 / 1024}MB
                SSL: ${settings.isSslEnabled}
                Host: ${settings.host}
            """.trimIndent())

            firestore.collection("hdd-monitor")
                .limit(1)
                .get()
                .await()

            Log.d(TAG, "Validación de configuración exitosa")

        } catch (e: Exception) {
            Log.e(TAG, "Error en validación de configuración", e)
            throw e
        }
    }

    fun optimizeMemoryUsage() {
        try {
            Log.d(TAG, "Optimizando uso de memoria Firebase")

            val firestore = FirebaseFirestore.getInstance()

            configScope.launch {
                try {
                    val runtime = Runtime.getRuntime()
                    val memoryUsage = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024

                    if (memoryUsage > 400) {
                        Log.d(TAG, "Memoria alta (${memoryUsage}MB), considerando limpieza cache")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error optimizando memoria", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en optimización de memoria", e)
        }
    }

    fun getConnectionStatus(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized,
            "connectionHealthy" to connectionHealthy,
            "timestamp" to System.currentTimeMillis()
        )
    }

    suspend fun emergencyReinitialize() {
        Log.w(TAG, "REINICIALIZACIÓN DE EMERGENCIA INICIADA")

        try {
            isInitialized = false
            connectionHealthy = false

            delay(2000)
            initializeFor24x7()

            Log.d(TAG, "Reinicialización de emergencia completada")

        } catch (e: Exception) {
            Log.e(TAG, "Error en reinicialización de emergencia", e)
            throw e
        }
    }

    fun getDetailedStats(): Map<String, Any> {
        return try {
            val runtime = Runtime.getRuntime()
            val firestore = FirebaseFirestore.getInstance()
            val settings = firestore.firestoreSettings

            mapOf<String, Any>(
                "firebase" to mapOf(
                    "isInitialized" to isInitialized,
                    "connectionHealthy" to connectionHealthy,
                    "host" to (settings.host ?: "unknown"),
                    "persistenceEnabled" to settings.isPersistenceEnabled,
                    "cacheSizeMB" to (settings.cacheSizeBytes / 1024 / 1024),
                    "sslEnabled" to settings.isSslEnabled
                ),
                "memory" to mapOf(
                    "totalMB" to (runtime.totalMemory() / 1024 / 1024),
                    "freeMB" to (runtime.freeMemory() / 1024 / 1024),
                    "usedMB" to ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024),
                    "maxMB" to (runtime.maxMemory() / 1024 / 1024)
                ),
                "topics" to CRITICAL_TOPICS,
                "timestamp" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo stats", e)
            mapOf<String, Any>("error" to (e.message ?: "unknown error"))
        }
    }
}

class FirebaseConfigurationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)