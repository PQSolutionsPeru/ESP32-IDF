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
        private const val BATTERY_REMINDER_INTERVAL = 3600000L
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
                lifecycleScope.launch {
                    delay(2000)
                    if (!isFinishing && !isDestroyed) {
                        showImprovedBatteryOptimizationDialog()
                    }
                }
            }
        }
    }

    private fun showImprovedBatteryOptimizationDialog() {
        if (!isFinishing && !isDestroyed) {
            try {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ CONFIGURACIÓN CRÍTICA REQUERIDA")
                    .setMessage(getDetailedBatteryMessage())
                    .setPositiveButton("Configurar Ahora") { _, _ ->
                        requestBatteryOptimizationExemption()
                    }
                    .setNeutralButton("Ir a Configuración") { _, _ ->
                        openBatteryOptimizationSettings()
                    }
                    .setNegativeButton("Recordar en 1 hora") { _, _ ->
                        scheduleReminderForBatteryOptimization()
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Error mostrando diálogo de batería mejorado", e)
            }
        }
    }

    private fun getDetailedBatteryMessage(): String {
        return """
🔥 SISTEMA DE MONITOREO DE INCENDIOS 🔥

Esta aplicación monitorea paneles de incendio 24/7/365.

⚠️ SIN ESTA CONFIGURACIÓN:
• Las notificaciones de emergencia pueden NO llegar
• Los alertas de incendio pueden perderse
• El sistema puede dejar de funcionar en segundo plano
• Podría NO recibir avisos críticos de seguridad

📱 ANDROID OPTIMIZA AUTOMÁTICAMENTE:
Android cierra apps en segundo plano para ahorrar batería, pero esto interfiere con sistemas de seguridad críticos.

✅ LA SOLUCIÓN ES SIMPLE:
Agregue esta app a la lista de "No optimizar" en configuración de batería. Solo toma 30 segundos.

🛡️ RESPONSABILIDAD:
Para garantizar que reciba todas las alertas de seguridad, debe configurar esta exención.
        """.trimIndent()
    }

    private fun scheduleReminderForBatteryOptimization() {
        lifecycleScope.launch {
            delay(BATTERY_REMINDER_INTERVAL)

            if (!isFinishing && !isDestroyed) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    showImprovedBatteryOptimizationDialog()
                }
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
            showBatterySettingsInstructions()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir configuración de optimización", e)
            try {
                val batteryIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                startActivity(batteryIntent)
                showBatterySettingsInstructions()
            } catch (e2: Exception) {
                Log.e(TAG, "No se pudo abrir configuración de batería", e2)
                openAppSpecificSettings()
            }
        }
    }

    private fun showBatterySettingsInstructions() {
        lifecycleScope.launch {
            delay(1000)

            if (!isFinishing && !isDestroyed) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("📱 Instrucciones")
                    .setMessage("""
PASOS A SEGUIR:

1️⃣ Busque "HDD Monitor" en la lista
2️⃣ Toque en "HDD Monitor"
3️⃣ Seleccione "No optimizar"
4️⃣ Confirme la selección

💡 Si no ve la opción, busque:
• "Optimización de batería"
• "Administración de energía"
• "Apps no optimizadas"

🔄 Regrese a la app cuando termine
                    """.trimIndent())
                    .setPositiveButton("Entendido") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun openAppSpecificSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            showAppSettingsInstructions()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir configuración de la aplicación", e)
            showManualInstructions()
        }
    }

    private fun showAppSettingsInstructions() {
        lifecycleScope.launch {
            delay(1000)

            if (!isFinishing && !isDestroyed) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("⚙️ Configuración de App")
                    .setMessage("""
PASOS EN CONFIGURACIÓN DE APP:

1️⃣ Busque "Batería" o "Battery"
2️⃣ Toque en "Optimización de batería"
3️⃣ Cambie a "No optimizar"
4️⃣ Confirme los cambios

O también:
• "Administración de energía"
• "Uso de batería"
• "Optimización automática"

✅ El objetivo: Permitir que la app funcione en segundo plano
                    """.trimIndent())
                    .setPositiveButton("Entendido") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun showManualInstructions() {
        AlertDialog.Builder(this)
            .setTitle("📖 Instrucciones Manuales")
            .setMessage("""
CONFIGURACIÓN MANUAL:

📱 Vaya a Configuración de Android:
1️⃣ Configuración → Apps
2️⃣ Busque "HDD Monitor"
3️⃣ Toque en "Batería"
4️⃣ Seleccione "No optimizar"

📱 O también:
1️⃣ Configuración → Batería
2️⃣ Optimización de batería
3️⃣ Busque "HDD Monitor"
4️⃣ Cambie a "No optimizar"

⚠️ IMPORTANTE:
Sin esta configuración, las alertas de incendio pueden no llegar.
            """.trimIndent())
            .setPositiveButton("Entendido") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
                    showQuickInstructions()
                } catch (e: Exception) {
                    Log.e(TAG, "Error con solicitud directa, usando método alternativo", e)
                    openBatteryOptimizationSettings()
                }
            }
        }
    }

    private fun showQuickInstructions() {
        lifecycleScope.launch {
            delay(500)

            if (!isFinishing && !isDestroyed) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("✅ Último Paso")
                    .setMessage("""
¡Perfecto! Solo confirme:

🔘 Seleccione "Permitir" o "Sí"
🔘 Confirme la excepción

⏱️ ¡Son solo 5 segundos más!

🛡️ Con esto garantiza recibir todas las alertas de incendio 24/7.
                    """.trimIndent())
                    .setPositiveButton("Entendido") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
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