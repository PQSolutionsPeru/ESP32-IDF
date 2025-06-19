package com.pqsolutions.hdd_monitor.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow

object StatusUpdateManager {
    private const val TAG = "StatusUpdateManager"

    private val _statusUpdates = MutableSharedFlow<StatusUpdate>(
        replay = 0,
        extraBufferCapacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @OptIn(FlowPreview::class)
    val statusUpdates: Flow<StatusUpdate> = _statusUpdates.asSharedFlow()
        .debounce(100)

    suspend fun emitRelayStatusUpdate(panelDocName: String, relayName: String, newStatus: String) {
        try {
            val update = StatusUpdate(
                panelDocName = panelDocName,
                relayName = relayName,
                newStatus = newStatus,
                isEsp32 = false,
                timestamp = System.currentTimeMillis()
            )
            Log.d(TAG, "Emitiendo actualización de relay: $panelDocName/$relayName -> $newStatus")
            _statusUpdates.emit(update)
        } catch (e: Exception) {
            Log.e(TAG, "Error emitiendo actualización de relay", e)
        }
    }

    suspend fun emitEsp32StatusUpdate(panelDocName: String, newStatus: String) {
        try {
            val update = StatusUpdate(
                panelDocName = panelDocName,
                relayName = "",
                newStatus = newStatus,
                isEsp32 = true,
                timestamp = System.currentTimeMillis()
            )
            Log.d(TAG, "Emitiendo actualización de ESP32: $panelDocName -> $newStatus")
            _statusUpdates.emit(update)
        } catch (e: Exception) {
            Log.e(TAG, "Error emitiendo actualización de ESP32", e)
        }
    }

    fun emitRelayStatusUpdateSync(panelDocName: String, relayName: String, newStatus: String) {
        CoroutineScope(Dispatchers.IO).launch {
            emitRelayStatusUpdate(panelDocName, relayName, newStatus)
        }
    }

    fun emitEsp32StatusUpdateSync(panelDocName: String, newStatus: String) {
        CoroutineScope(Dispatchers.IO).launch {
            emitEsp32StatusUpdate(panelDocName, newStatus)
        }
    }

    data class StatusUpdate(
        val panelDocName: String,
        val relayName: String,
        val newStatus: String,
        val isEsp32: Boolean,
        val timestamp: Long
    )
}