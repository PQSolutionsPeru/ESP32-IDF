package com.pqsolutions.hdd_monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pqsolutions.hdd_monitor.service.MonitoringService
import com.pqsolutions.hdd_monitor.data.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ServiceCheckWorker @Inject constructor(
    @ApplicationContext private val context: Context,
    params: WorkerParameters,
    private val userRepository: UserRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceCheckWorker"
        const val SERVICE_CHECK_WORK = "service_check_work"
    }

    override suspend fun doWork(): Result {
        try {
            // Verificar si hay usuario logueado
            val isLoggedIn = userRepository.getCurrentUser() != null

            if (!isLoggedIn) {
                Log.d(TAG, "No hay usuario logueado, deteniendo servicio si existe")
                stopMonitoringService()
                return Result.success()
            }

            if (!isServiceRunning()) {
                Log.d(TAG, "Servicio de monitoreo no encontrado y usuario logueado, reiniciando...")
                startMonitoringService()
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando servicio", e)
            return Result.retry()
        }
    }

    private fun isServiceRunning(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (MonitoringService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(context, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun stopMonitoringService() {
        val serviceIntent = Intent(context, MonitoringService::class.java)
        context.stopService(serviceIntent)
    }
}