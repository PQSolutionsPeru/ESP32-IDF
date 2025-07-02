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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            startMonitoringServiceWithRetry()
        } else {
            Log.d(TAG, "Algunos permisos fueron denegados")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Iniciando aplicación")

        setupWindow()
        setAppContent()

        lifecycleScope.launch(Dispatchers.IO) {
            initializeInBackground()
        }
    }

    private fun setupWindow() {
        try {
            Log.d(TAG, "Configuración de ventana completada")
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando ventana", e)
        }
    }

    private suspend fun initializeInBackground() {
        try {
            delay(1000)

            withContext(Dispatchers.Main) {
                checkAndRequestPermissions()
            }

            delay(500)
            checkBatteryOptimization()

            delay(500)
            updateFCMTokenIfNeeded()

        } catch (e: Exception) {
            Log.e(TAG, "Error durante la inicialización en background", e)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            startMonitoringServiceWithRetry()
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(2000)
                    if (!isFinishing && !isDestroyed) {
                        showBatteryOptimizationDialog()
                    }
                }
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        if (!isFinishing && !isDestroyed) {
            try {
                AlertDialog.Builder(this)
                    .setTitle("CRÍTICO: Optimización de batería")
                    .setMessage("Para el funcionamiento 24/7 del sistema de monitoreo de incendios, DEBE desactivar la optimización de batería. Sin esto, las notificaciones pueden fallar.")
                    .setPositiveButton("Configurar Ahora") { _, _ ->
                        requestBatteryOptimizationExemption()
                    }
                    .setNegativeButton("Recordar después") { dialog, _ ->
                        dialog.dismiss()
                        scheduleReminderForBatteryOptimization()
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Error mostrando diálogo de batería", e)
            }
        }
    }

    private fun scheduleReminderForBatteryOptimization() {
        lifecycleScope.launch {
            delay(300000)
            if (!isFinishing && !isDestroyed) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    showBatteryOptimizationDialog()
                }
            }
        }
    }

    private fun startMonitoringServiceWithRetry() {
        lifecycleScope.launch {
            var attempts = 0
            val maxAttempts = 3

            while (attempts < maxAttempts) {
                try {
                    val serviceIntent = Intent(this@MainActivity, MonitoringService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }

                    delay(2000)

                    if (MonitoringService.isRunning()) {
                        Log.d(TAG, "Servicio de monitoreo iniciado correctamente")
                        break
                    } else {
                        attempts++
                        if (attempts < maxAttempts) {
                            Log.w(TAG, "Reintentando iniciar servicio (${attempts}/${maxAttempts})")
                            delay(3000)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error al iniciar el servicio de monitoreo, intento $attempts", e)
                    attempts++
                    if (attempts < maxAttempts) {
                        delay(5000)
                    }
                }
            }

            if (!MonitoringService.isRunning()) {
                Log.e(TAG, "CRÍTICO: No se pudo iniciar el servicio de monitoreo después de $maxAttempts intentos")
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error solicitando exención de optimización de batería", e)
                    try {
                        val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(settingsIntent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "No se pudo abrir la configuración de optimización de batería", e2)
                        showAppSettings()
                    }
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

    private suspend fun updateFCMTokenIfNeeded() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Token FCM obtenido: ${task.result}")
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

            FirebaseMessaging.getInstance().subscribeToTopic("relay-status")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Suscripción exitosa al tópico relay-status")
                    } else {
                        Log.e(TAG, "Error en suscripción a relay-status", task.exception)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error con Firebase Messaging", e)
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
                        AppNavigation(viewModel)
                    }
                }
            }
            Log.d(TAG, "Contenido de aplicación configurado exitosamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico configurando la aplicación", e)
            showErrorScreen(e)
        }
    }

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

        lifecycleScope.launch {
            delay(1000)
            if (!MonitoringService.isRunning()) {
                Log.w(TAG, "Servicio no está corriendo en onResume, reiniciando")
                startMonitoringServiceWithRetry()
            }
        }
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