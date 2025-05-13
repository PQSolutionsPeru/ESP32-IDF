package com.pqsolutions.hdd_monitor.presentation

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.presentation.navigation.AppNavigation
import com.pqsolutions.hdd_monitor.presentation.theme.HDD1_2Theme
import com.pqsolutions.hdd_monitor.presentation.viewmodel.MainViewModel
import com.pqsolutions.hdd_monitor.service.MonitoringService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 123
        const val CHANNEL_ID_RELAY = "relay_status"
        const val CHANNEL_ID_EVENT = "event_notifications"
    }

    private val requiredPermissions = mutableListOf(
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startMonitoringService()
        } else {
            // Algunos permisos fueron denegados, mostrar configuración
            showAppSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Iniciando aplicación")

        try {
            // Inicialización con manejo de errores
            initializeWithErrorHandling()
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico durante la inicialización", e)
            showErrorScreen(e)
        }

        Log.d(TAG, "onCreate: Configuración inicial completada")
    }

    /**
     * Método principal de inicialización con manejo de errores
     */
    private fun initializeWithErrorHandling() {
        // Separar inicializaciones en bloques try-catch independientes
        // para que un error en un componente no impida la inicialización de los demás

        try {
            checkAndRequestPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Error en checkAndRequestPermissions", e)
        }

        try {
            checkAndSetupBatteryOptimization()
        } catch (e: Exception) {
            Log.e(TAG, "Error en checkAndSetupBatteryOptimization", e)
        }

        try {
            initializeFirebase()
        } catch (e: Exception) {
            Log.e(TAG, "Error en initializeFirebase", e)
        }

        try {
            createNotificationChannels()
        } catch (e: Exception) {
            Log.e(TAG, "Error en createNotificationChannels", e)
        }

        try {
            requestBatteryOptimizationExemption()
        } catch (e: Exception) {
            Log.e(TAG, "Error en requestBatteryOptimizationExemption", e)
        }

        try {
            setAppContent()
        } catch (e: Exception) {
            Log.e(TAG, "Error en setAppContent", e)
            showErrorScreen(e)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            startMonitoringService()
        }
    }

    private fun checkAndSetupBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // Si no está desactivada la optimización de batería, mostrar diálogo
            AlertDialog.Builder(this)
                .setTitle("Optimización de batería")
                .setMessage("Para asegurar el correcto funcionamiento de la aplicación, es necesario desactivar la optimización de batería. ¿Desea hacerlo ahora?")
                .setPositiveButton("Sí") { _, _ ->
                    requestBatteryOptimizationExemption()
                }
                .setNegativeButton("Más tarde") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
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
            Log.d(TAG, "Servicio de monitoreo iniciado correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar el servicio de monitoreo", e)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                // Primero intentamos con el diálogo directo
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error solicitando exención de optimización de batería", e)
                // Si falla, llevamos al usuario a la configuración general de optimización de batería
                try {
                    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(settingsIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo abrir la configuración de optimización de batería", e)
                    // Como último recurso, mostrar la configuración general de la aplicación
                    showAppSettings()
                }
            }
        }
    }

    private fun showAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la configuración de la aplicación", e)
        }
    }

    private fun requestNotificationPermission() {
        Log.d(TAG, "Solicitando permisos de notificación")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d(TAG, "Permisos de notificación ya otorgados")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permisos de notificación otorgados por el usuario")
                } else {
                    Log.d(TAG, "Permisos de notificación denegados por el usuario")
                }
            }
        }
    }

    private fun initializeFirebase() {
        Log.d(TAG, "Inicializando Firebase")
        try {
            FirebaseApp.initializeApp(this)

            // Verificar y mostrar el token FCM
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Token FCM obtenido exitosamente: ${task.result}")
                        lifecycleScope.launch {
                            try {
                                viewModel.updateFCMToken()
                                Log.d(TAG, "Token FCM actualizado en el repositorio")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error actualizando token FCM", e)
                            }
                        }
                    } else {
                        Log.e(TAG, "Error obteniendo token FCM", task.exception)
                    }
                }

            // Configurar comportamiento de mensajes en primer plano
            FirebaseMessaging.getInstance().isAutoInitEnabled = true

            // Suscribirse a tópicos relevantes
            FirebaseMessaging.getInstance().subscribeToTopic("relay-status")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Suscripción exitosa al tópico relay-status")
                    } else {
                        Log.e(TAG, "Error en suscripción a relay-status", task.exception)
                    }
                }

            Log.d(TAG, "Inicialización de Firebase completada")
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico inicializando Firebase", e)
            throw e  // Re-lanzar para manejo global
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Creando canales de notificación")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Canal para notificaciones de relay
            NotificationChannel(
                CHANNEL_ID_RELAY,
                "Estado de Relay",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de cambios de estado en relays"
                enableVibration(true)
                vibrationPattern = longArrayOf(100, 200, 300, 400, 500)
                enableLights(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setShowBadge(true)
                notificationManager.createNotificationChannel(this)
                Log.d(TAG, "Canal de relay creado: $id")
            }

            // Canal para notificaciones de eventos
            NotificationChannel(
                CHANNEL_ID_EVENT,
                "Eventos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de actualizaciones de eventos"
                enableVibration(true)
                vibrationPattern = longArrayOf(100, 200, 300, 400, 500)
                enableLights(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setShowBadge(true)
                notificationManager.createNotificationChannel(this)
                Log.d(TAG, "Canal de eventos creado: $id")
            }
        }
    }

    private fun setAppContent() {
        Log.d(TAG, "Configurando contenido de la aplicación")

        try {
            setContent {
                HDD1_2Theme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val uiState by viewModel.uiState.collectAsState()
                        Log.d(TAG, "Estado actual de UI: $uiState")

                        AppNavigation(viewModel)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico configurando la aplicación", e)
            showErrorScreen(e)
        }
    }

    /**
     * Muestra una pantalla de error cuando hay un problema crítico
     */
    private fun showErrorScreen(error: Exception) {
        try {
            setContent {
                HDD1_2Theme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Error inicializando la aplicación",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.error
                            )

                            Text(
                                "Se ha producido un error al iniciar la aplicación. " +
                                        "Por favor, reinicie la aplicación.",
                                modifier = Modifier.padding(vertical = 16.dp)
                            )

                            // Siempre mostrar el mensaje de error para diagnóstico
                            Text(
                                "Error: ${error.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )

                            Button(onClick = { recreate() }) {
                                Text("Reintentar")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando pantalla de error", e)
            // En este punto, simplemente finalizamos la actividad como último recurso
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Actividad iniciada")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Actividad en primer plano")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Actividad pausada")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Actividad detenida")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Actividad destruida")
    }
}