package com.pqsolutions.hdd_monitor.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile

abstract class BluetoothGattCallbackWrapper : BluetoothGattCallback() {

    abstract fun onConnectionStateChanged(gatt: BluetoothGatt, status: Int, newState: Int)

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        onConnectionStateChanged(gatt, status, newState)
    }
}