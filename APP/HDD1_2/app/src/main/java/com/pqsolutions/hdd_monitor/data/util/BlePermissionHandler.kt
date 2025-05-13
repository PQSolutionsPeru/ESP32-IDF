package com.pqsolutions.hdd_monitor.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import android.os.Build
import android.util.Log

object BlePermissionHandler {
    private const val TAG = "BlePermissionHandler"

    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    fun allPermissionsGranted(context: Context): Boolean {
        return getRequiredPermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun checkBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        return adapter?.isEnabled == true
    }

    fun logPermissionStatus(context: Context) {
        getRequiredPermissions().forEach { permission ->
            val isGranted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permiso $permission: ${if (isGranted) "CONCEDIDO" else "DENEGADO"}")
        }
        val bluetoothEnabled = checkBluetoothEnabled(context)
        Log.d(TAG, "Bluetooth habilitado: $bluetoothEnabled")
    }
}