package com.pqsolutions.hdd_monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pqsolutions.hdd_monitor.service.MonitoringService
import com.pqsolutions.hdd_monitor.data.UserRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ServiceCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val userRepository: UserRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceCheckWorker"
        const val SERVICE_CHECK_WORK = "service_check_work"
    }

    override suspend fun doWork(): Result {
        try {
            Log.d(TAG, "Iniciando verificación del servicio")

            // Verificar si hay usuario logueado
            val currentUser = userRepository.getCurrentUser()
            val isLoggedIn = currentUser != null

            if (!isLoggedIn) {
                Log.d(TAG, "No hay usuario logueado, deteniendo servicio si existe")
                if (isServiceRunning()) {
                    stopMonitoringService()
                }
                return Result.success()
            }

            // Usuario logueado, verificar servicio
            if (!isServiceRunning()) {
                Log.d(TAG, "Servicio de monitoreo no encontrado y usuario logueado, reiniciando...")
                startMonitoringService()
            } else {
                Log.d(TAG, "Servicio de monitoreo está ejecutándose correctamente")
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando servicio", e)
            // Reintentar en caso de error
            return Result.retry()
        }
    }

    private fun isServiceRunning(): Boolean {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // En Android O y superior, usar un método diferente
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Intentar con getRunningAppProcesses primero
                val runningProcesses = activityManager.runningAppProcesses
                if (runningProcesses != null) {
                    for (processInfo in runningProcesses) {
                        if (processInfo.processName == context.packageName) {
                            // El proceso está corriendo, ahora verificar el servicio específico
                            return true // Asumimos que si el proceso está corriendo, el servicio también
                        }
                    }
                }
                return false
            } else {
                // En versiones anteriores, usar getRunningServices
                @Suppress("DEPRECATION")
                for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
                    if (MonitoringService::class.java.name == service.service.className) {
                        return true
                    }
                }
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando si el servicio está corriendo", e)
            return false
        }
    }

    private fun startMonitoringService() {
        try {
            val serviceIntent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Servicio de monitoreo iniciado desde ServiceCheckWorker")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando servicio de monitoreo", e)
        }
    }

    private fun stopMonitoringService() {
        try {
            val serviceIntent = Intent(context, MonitoringService::class.java)
            context.stopService(serviceIntent)
            Log.d(TAG, "Servicio de monitoreo detenido desde ServiceCheckWorker")
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo servicio de monitoreo", e)
        }
    }
}