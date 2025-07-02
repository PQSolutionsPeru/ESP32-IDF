package com.pqsolutions.hdd_monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                if (intent.dataString?.contains(context.packageName) == true) {
                    Log.d(TAG, "App actualizada, reiniciando servicio")
                    startMonitoringService(context)
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Pantalla apagada, verificando servicio")
                if (!MonitoringService.isRunning()) {
                    Log.w(TAG, "Servicio no está corriendo con pantalla apagada, reiniciando")
                    startMonitoringService(context)
                }
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Pantalla encendida, verificando servicio")
                if (!MonitoringService.isRunning()) {
                    Log.w(TAG, "Servicio no está corriendo con pantalla encendida, reiniciando")
                    startMonitoringService(context)
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "Usuario presente, verificando servicio")
                if (!MonitoringService.isRunning()) {
                    startMonitoringService(context)
                }
            }
            "android.intent.action.DOZE_DEVICE_ENTER" -> {
                Log.w(TAG, "Dispositivo entrando en Doze Mode")
            }
            "android.intent.action.DOZE_DEVICE_EXIT" -> {
                Log.d(TAG, "Dispositivo saliendo de Doze Mode, verificando servicio")
                if (!MonitoringService.isRunning()) {
                    startMonitoringService(context)
                }
            }
        }
    }

    private fun startMonitoringService(context: Context) {
        try {
            val serviceIntent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Servicio iniciado desde KeepAliveReceiver")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando servicio desde KeepAliveReceiver", e)
        }
    }
}