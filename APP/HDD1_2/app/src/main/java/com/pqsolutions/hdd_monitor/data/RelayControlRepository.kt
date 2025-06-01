package com.pqsolutions.hdd_monitor.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayControlRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val esp32Repository: ESP32Repository
) {
    companion object {
        private const val TAG = "RelayControlRepository"
        private const val BASE_PATH = "hdd-monitor/accounts/clients"
        private const val COMMAND_TIMEOUT = 30000L // 30 segundos
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")
    }

    // Cache para comandos pendientes
    private val pendingCommands = ConcurrentHashMap<String, RelayCommand>()

    // Listeners activos con mejor gestión
    private val activeListeners = ConcurrentHashMap<String, ListenerRegistration>()

    // Scope con SupervisorJob para mejor manejo de errores
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class RelayCommand(
        val commandId: String,
        val panelId: String,
        val relayName: String,
        val targetStatus: String,
        val timestamp: Long,
        val clientId: String
    )

    /**
     * Obtiene todos los paneles con sus relays para administradores
     */
    fun getAllPanelsWithRelays(): Flow<List<Panel>> = callbackFlow {
        Log.d(TAG, "Obteniendo todos los paneles para admin")

        val listenerId = "all_panels_relays_${System.currentTimeMillis()}"
        val allPanels = mutableListOf<Panel>()

        // Limpiar listeners previos
        clearOldListeners("all_panels_relays")

        try {
            val registration = firestore.collection(BASE_PATH)
                .addSnapshotListener { clientsSnapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error obteniendo clientes", error)
                        if (error !is CancellationException) {
                            trySend(emptyList())
                        }
                        return@addSnapshotListener
                    }

                    if (clientsSnapshot == null || clientsSnapshot.isEmpty) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }

                    // Procesar clientes de forma simplificada
                    val totalClients = clientsSnapshot.size()
                    var processedClients = 0

                    if (totalClients == 0) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }

                    clientsSnapshot.documents.forEach { clientDoc ->
                        val clientId = clientDoc.id

                        // Usar una sola consulta por cliente sin listeners anidados
                        repositoryScope.launch {
                            try {
                                val panelsSnapshot = firestore.collection("$BASE_PATH/$clientId/panels")
                                    .get()
                                    .await()

                                val clientPanels = panelsSnapshot.documents.mapNotNull { panelDoc ->
                                    try {
                                        panelDoc.toObject(Panel::class.java)?.copy(
                                            documentName = panelDoc.id,
                                            clientName = clientId
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error convirtiendo panel ${panelDoc.id}", e)
                                        null
                                    }
                                }

                                // Cargar relays inmediatamente sin listeners adicionales
                                clientPanels.forEach { panel ->
                                    loadRelaysImmediate(clientId, panel)
                                }

                                synchronized(allPanels) {
                                    allPanels.removeAll { it.clientName == clientId }
                                    allPanels.addAll(clientPanels)
                                    processedClients++

                                    if (processedClients >= totalClients) {
                                        trySend(allPanels.toList())
                                    }
                                }

                            } catch (e: Exception) {
                                if (e !is CancellationException) {
                                    Log.e(TAG, "Error procesando cliente $clientId", e)
                                }
                                synchronized(allPanels) {
                                    processedClients++
                                    if (processedClients >= totalClients) {
                                        trySend(allPanels.toList())
                                    }
                                }
                            }
                        }
                    }
                }

            activeListeners[listenerId] = registration

            awaitClose {
                Log.d(TAG, "Cerrando listener de todos los paneles")
                try {
                    registration.remove()
                    activeListeners.remove(listenerId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cerrando listener", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error configurando listener de paneles", e)
            trySend(emptyList())
            close(e)
        }

    }.flowOn(Dispatchers.IO)

    /**
     * Obtiene paneles con relays para un cliente específico
     */
    fun getPanelsWithRelaysByClient(clientId: String): Flow<List<Panel>> = callbackFlow {
        Log.d(TAG, "Obteniendo paneles para cliente: $clientId")

        val listenerId = "client_panels_relays_${clientId}_${System.currentTimeMillis()}"

        // Limpiar listeners previos del mismo cliente
        clearOldListeners("client_panels_relays_$clientId")

        try {
            val registration = firestore.collection("$BASE_PATH/$clientId/panels")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error obteniendo paneles del cliente $clientId", error)
                        if (error !is CancellationException) {
                            trySend(emptyList())
                        }
                        return@addSnapshotListener
                    }

                    if (snapshot == null || snapshot.isEmpty) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }

                    val panels = snapshot.documents.mapNotNull { doc ->
                        try {
                            doc.toObject(Panel::class.java)?.copy(
                                documentName = doc.id,
                                clientName = clientId
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error convirtiendo panel ${doc.id}", e)
                            null
                        }
                    }

                    // Cargar relays inmediatamente
                    repositoryScope.launch {
                        try {
                            panels.forEach { panel ->
                                loadRelaysImmediate(clientId, panel)
                            }
                            trySend(panels)
                        } catch (e: Exception) {
                            if (e !is CancellationException) {
                                Log.e(TAG, "Error cargando relays", e)
                                trySend(panels) // Enviar paneles sin relays si falla
                            }
                        }
                    }
                }

            activeListeners[listenerId] = registration

            awaitClose {
                Log.d(TAG, "Cerrando listener del cliente $clientId")
                try {
                    registration.remove()
                    activeListeners.remove(listenerId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cerrando listener", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error configurando listener para cliente $clientId", e)
            trySend(emptyList())
            close(e)
        }

    }.flowOn(Dispatchers.IO)

    /**
     * Carga relays inmediatamente sin crear listeners adicionales
     */
    private suspend fun loadRelaysImmediate(clientId: String, panel: Panel) {
        try {
            val relaysSnapshot = firestore
                .collection("$BASE_PATH/$clientId/panels/${panel.documentName}/relays")
                .get()
                .await()

            if (!relaysSnapshot.isEmpty) {
                val relays = relaysSnapshot.documents.mapNotNull { doc ->
                    try {
                        Relay.fromMap(
                            doc.data?.plus(mapOf("name" to doc.id)) ?: emptyMap()
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error convirtiendo relay ${doc.id}", e)
                        null
                    }
                }
                panel.relays = relays
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Error cargando relays para panel ${panel.documentName}", e)
            }
        }
    }

    /**
     * Limpia listeners antiguos que coinciden con un prefijo
     */
    private fun clearOldListeners(prefix: String) {
        val listenersToRemove = activeListeners.keys.filter { it.startsWith(prefix) }

        listenersToRemove.forEach { key ->
            try {
                activeListeners[key]?.remove()
                activeListeners.remove(key)
            } catch (e: Exception) {
                Log.e(TAG, "Error removiendo listener $key", e)
            }
        }

        if (listenersToRemove.isNotEmpty()) {
            Log.d(TAG, "Removidos ${listenersToRemove.size} listeners antiguos con prefijo: $prefix")
        }
    }

    /**
     * Envía comando para cambiar estado de un relay
     */
    suspend fun sendRelayCommand(
        esp32Id: String,
        clientId: String,
        panelId: String,
        relayName: String,
        newStatus: String,
        commandId: String
    ): Boolean = try {
        Log.d(TAG, "Enviando comando relay: $clientId/$panelId/$relayName -> $newStatus")

        // Obtener información del panel para verificar ESP32
        val panelDoc = firestore
            .document("$BASE_PATH/$clientId/panels/$panelId")
            .get(Source.SERVER)
            .await()

        if (!panelDoc.exists()) {
            throw IllegalStateException("Panel no encontrado: $panelId")
        }

        val esp32IdFromPanel = panelDoc.getString("esp32_id")
            ?: throw IllegalStateException("Panel sin ESP32 asignado")

        // Verificar que el ESP32 esté online
        val esp32Doc = firestore
            .document("hdd-monitor/esp32/registered/$esp32IdFromPanel")
            .get(Source.SERVER)
            .await()

        if (!esp32Doc.exists()) {
            throw IllegalStateException("ESP32 no encontrado: $esp32IdFromPanel")
        }

        val esp32Status = esp32Doc.getString("status")
        if (esp32Status != "ONLINE" && esp32Status != "RUNNING") {
            throw IllegalStateException("ESP32 no está conectado (Estado: $esp32Status)")
        }

        // Crear comando
        val command = RelayCommand(
            commandId = commandId,
            panelId = panelId,
            relayName = relayName,
            targetStatus = newStatus,
            timestamp = System.currentTimeMillis(),
            clientId = clientId
        )

        // Almacenar comando pendiente
        pendingCommands[commandId] = command

        // Actualizar estado del relay en Firestore con metadata del comando
        val now = LocalDateTime.now().format(DATE_FORMATTER)
        val relayRef = firestore
            .document("$BASE_PATH/$clientId/panels/$panelId/relays/$relayName")

        val updateData = mapOf(
            "status" to newStatus,
            "date_time" to now,
            "lastUpdate" to com.google.firebase.Timestamp.now(),
            "lastCommandId" to commandId,
            "commandSource" to "app",
            "commandTimestamp" to System.currentTimeMillis()
        )

        relayRef.update(updateData).await()

        Log.d(TAG, "Comando enviado exitosamente: $commandId")

        // Programar limpieza del comando después del timeout
        scheduleCommandCleanup(commandId)

        true
    } catch (error: Exception) {
        Log.e(TAG, "Error enviando comando relay", error)
        pendingCommands.remove(commandId) // Limpiar comando fallido
        false
    }

    /**
     * Actualiza la configuración de un relay (nombre personalizado, etc.)
     */
    suspend fun updateRelayConfiguration(
        clientId: String,
        panelId: String,
        relay: Relay
    ): Boolean = try {
        Log.d(TAG, "Actualizando configuración relay: $clientId/$panelId/${relay.name}")

        val relayRef = firestore
            .document("$BASE_PATH/$clientId/panels/$panelId/relays/${relay.name}")

        val updateData = mapOf(
            "name" to relay.name,
            "status" to relay.status,
            "date_time" to relay.date_time,
            "lastUpdate" to com.google.firebase.Timestamp.now(),
            "isActive" to true,
            "customName" to relay.name,
            "isControllable" to true
        )

        relayRef.set(updateData).await()
        Log.d(TAG, "Configuración de relay actualizada exitosamente")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error actualizando configuración de relay", e)
        false
    }

    /**
     * Obtiene el estado actual de un relay específico
     */
    suspend fun getRelayStatus(
        clientId: String,
        panelId: String,
        relayName: String
    ): Result<Relay> = runCatching {
        val relayDoc = firestore
            .document("$BASE_PATH/$clientId/panels/$panelId/relays/$relayName")
            .get(Source.SERVER)
            .await()

        if (!relayDoc.exists()) {
            throw IllegalStateException("Relay no encontrado: $relayName")
        }

        Relay.fromMap(
            relayDoc.data?.plus(mapOf("name" to relayDoc.id)) ?: emptyMap()
        )
    }

    /**
     * Verifica si un comando fue ejecutado exitosamente
     */
    suspend fun checkCommandStatus(commandId: String): Result<Boolean> = runCatching {
        val command = pendingCommands[commandId]
            ?: throw IllegalStateException("Comando no encontrado: $commandId")

        // Verificar estado actual del relay
        val currentRelay = getRelayStatus(
            command.clientId,
            command.panelId,
            command.relayName
        ).getOrThrow()

        // El comando fue exitoso si el estado actual coincide con el objetivo
        val success = currentRelay.status == command.targetStatus

        if (success) {
            pendingCommands.remove(commandId)
        }

        success
    }

    /**
     * Obtiene comandos pendientes para un panel específico
     */
    fun getPendingCommands(clientId: String, panelId: String): List<RelayCommand> {
        return pendingCommands.values.filter {
            it.clientId == clientId && it.panelId == panelId
        }
    }

    /**
     * Programa la limpieza automática de un comando después del timeout
     */
    private fun scheduleCommandCleanup(commandId: String) {
        repositoryScope.launch {
            try {
                delay(COMMAND_TIMEOUT)
                val command = pendingCommands.remove(commandId)
                if (command != null) {
                    Log.w(TAG, "Comando $commandId eliminado por timeout")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Limpieza de comando cancelada: $commandId")
            } catch (e: Exception) {
                Log.e(TAG, "Error en limpieza de comando", e)
            }
        }
    }

    /**
     * Obtiene información del ESP32 asociado a un panel
     */
    suspend fun getESP32Info(clientId: String, panelId: String): Result<Map<String, Any?>> = runCatching {
        val panelDoc = firestore
            .document("$BASE_PATH/$clientId/panels/$panelId")
            .get(Source.SERVER)
            .await()

        if (!panelDoc.exists()) {
            throw IllegalStateException("Panel no encontrado")
        }

        val esp32Id = panelDoc.getString("esp32_id")
            ?: throw IllegalStateException("Panel sin ESP32 asignado")

        val esp32Doc = firestore
            .document("hdd-monitor/esp32/registered/$esp32Id")
            .get(Source.SERVER)
            .await()

        if (!esp32Doc.exists()) {
            throw IllegalStateException("ESP32 no encontrado")
        }

        mapOf(
            "esp32_id" to esp32Id,
            "status" to (esp32Doc.getString("status") ?: "UNKNOWN"),
            "ip" to (esp32Doc.getString("IP") ?: ""),
            "mac" to (esp32Doc.getString("MAC") ?: ""),
            "lastUpdate" to esp32Doc.getTimestamp("lastUpdate")
        )
    }

    /**
     * Verifica el estado de un ESP32
     */
    suspend fun checkESP32Status(esp32Id: String): Result<String> = runCatching {
        val esp32Doc = firestore
            .document("hdd-monitor/esp32/registered/$esp32Id")
            .get(Source.SERVER)
            .await()

        if (!esp32Doc.exists()) {
            throw IllegalStateException("ESP32 no encontrado: $esp32Id")
        }

        esp32Doc.getString("status") ?: "UNKNOWN"
    }

    /**
     * Limpia todos los listeners activos
     */
    fun clearListeners() {
        Log.d(TAG, "Limpiando ${activeListeners.size} listeners de relay control")

        val listeners = activeListeners.values.toList()
        activeListeners.clear()
        pendingCommands.clear()

        listeners.forEach { listener ->
            try {
                listener.remove()
            } catch (e: Exception) {
                Log.e(TAG, "Error removiendo listener", e)
            }
        }

        Log.d(TAG, "Listeners de relay control limpiados")
    }

    /**
     * Fuerza una actualización desde el servidor
     */
    suspend fun forceRefresh() {
        try {
            Log.d(TAG, "Forzando actualización desde servidor")

            clearListeners()
            delay(500) // Pausa breve para asegurar limpieza

            // Verificar conectividad
            firestore.collection(BASE_PATH)
                .limit(1)
                .get(Source.SERVER)
                .await()

            Log.d(TAG, "Actualización forzada completada")
        } catch (e: Exception) {
            Log.e(TAG, "Error en actualización forzada", e)
            throw e
        }
    }
}