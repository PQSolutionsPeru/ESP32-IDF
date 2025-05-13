package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.data.ClientRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.esp32.ESP32Device
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import com.pqsolutions.hdd_monitor.util.StatusUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var panelsJob: Job? = null
    private var statusUpdateJob: Job? = null
    private var refreshJob: Job? = null

    companion object {
        private const val TAG = "DashboardViewModel"
        private const val REFRESH_INTERVAL = 30000L // 30 segundos
    }

    init {
        Log.d(TAG, "DashboardViewModel initialized")
        listenForStatusUpdates()
        startPeriodicRefresh()
    }

    /**
     * Escucha actualizaciones de estado en tiempo real desde el StatusUpdateManager
     */
    private fun listenForStatusUpdates() {
        statusUpdateJob?.cancel()
        statusUpdateJob = viewModelScope.launch {
            Log.d(TAG, "Comenzando a escuchar actualizaciones de estado")

            StatusUpdateManager.statusUpdates
                .distinctUntilChanged() // Evitar duplicados
                .collect { update ->
                    Log.d(TAG, "Recibida actualización de estado: ${update.panelDocName} -> ${update.newStatus}")

                    if (update.isEsp32) {
                        // Actualizar estado ESP32
                        updatePanelESP32Status(update.panelDocName, update.newStatus)
                    } else {
                        // Actualizar estado de relay
                        updatePanelRelayStatus(update.panelDocName, update.relayName, update.newStatus)
                    }

                    // Forzar recomposición
                    _uiState.update { it.copy(lastUpdate = System.currentTimeMillis()) }
                }
        }
    }

    /**
     * Actualiza el estado ESP32 de un panel específico en el UI
     */
    private fun updatePanelESP32Status(panelDocName: String, newStatus: String) {
        Log.d(TAG, "Actualizando estado ESP32 en UI para panel $panelDocName a $newStatus")

        _uiState.update { currentState ->
            val updatedPanels = currentState.panels.map { panel ->
                if (panel.documentName == panelDocName) {
                    Log.d(TAG, "Actualizando estado ESP32 del panel ${panel.name} a $newStatus")
                    // Crear un nuevo objeto Panel con el estado actualizado
                    val updatedPanel = panel.copy(esp32Status = newStatus)
                    // Verificar que los estados derivados se calculen correctamente
                    Log.d(TAG, "Panel actualizado: isESP32Offline=${updatedPanel.isESP32Offline()}, " +
                            "hasIssues=${updatedPanel.hasIssues}, overallStatus=${updatedPanel.overallStatus}")
                    updatedPanel
                } else {
                    panel
                }
            }

            val updatedGroupedPanels = updatedPanels.groupBy {
                currentState.clientNames[it.clientName] ?: it.clientName
            }

            currentState.copy(
                panels = updatedPanels,
                groupedPanels = updatedGroupedPanels,
                lastUpdate = System.currentTimeMillis()
            )
        }
    }

    /**
     * Actualiza el estado de un relay específico en el UI
     */
    private fun updatePanelRelayStatus(panelDocName: String, relayName: String, newStatus: String) {
        Log.d(TAG, "Actualizando estado relay en UI: $panelDocName, $relayName -> $newStatus")

        _uiState.update { currentState ->
            val updatedPanels = currentState.panels.map { panel ->
                if (panel.documentName == panelDocName) {
                    val updatedRelays = panel.relays.map { relay ->
                        if (relay.name == relayName) {
                            Log.d(TAG, "Actualizando relay ${relay.name} del panel ${panel.name} a $newStatus")
                            relay.copy(status = newStatus)
                        } else {
                            relay
                        }
                    }
                    panel.copy(relays = updatedRelays)
                } else {
                    panel
                }
            }

            val updatedGroupedPanels = updatedPanels.groupBy {
                currentState.clientNames[it.clientName] ?: it.clientName
            }

            currentState.copy(
                panels = updatedPanels,
                groupedPanels = updatedGroupedPanels,
                lastUpdate = System.currentTimeMillis()
            )
        }
    }

    /**
     * Carga los paneles desde el repositorio
     */
    fun loadPanels() {
        Log.d(TAG, "loadPanels() called")

        // Si ya hay un trabajo activo, no iniciar otro y no cancelarlo
        if (panelsJob?.isActive == true) {
            Log.d(TAG, "Panels already loading, skipping redundant call")
            return
        }

        // Indicar carga
        _uiState.update { it.copy(isLoading = true, error = null) }

        panelsJob = viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                Log.d(TAG, "Current user: ${currentUser?.documentName}, Role: ${currentUser?.role}")

                if (currentUser != null) {
                    val clientDocName = when (currentUser.role) {
                        UserRole.USER -> {
                            Log.d(TAG, "User role detected, using client: ${currentUser.clientDocName}")
                            currentUser.clientDocName
                        }
                        UserRole.ADMIN -> {
                            Log.d(TAG, "Admin role detected, fetching all panels")
                            null
                        }
                    }

                    // Cargar mapa de nombres de clientes primero para mejorar la experiencia del usuario
                    val clientsMap = loadClientNames(clientDocName)
                    _uiState.update { it.copy(clientNames = clientsMap) }

                    // Monitorear paneles con el flujo del repositorio
                    panelRepository.getPanels(clientDocName).collect { panels ->
                        Log.d(TAG, "Received ${panels.size} panels")

                        // Filtrar paneles válidos (seguridad adicional)
                        val validPanels = panels.filter { panel ->
                            panel.documentName.startsWith(DocumentPrefixes.PANEL) &&
                                    panel.clientName.startsWith(DocumentPrefixes.CLIENT)
                        }

                        // Agrupar por cliente para la UI
                        val groupedPanels = validPanels.groupBy {
                            clientsMap[it.clientName] ?: it.clientName
                        }

                        // Actualizar estado UI
                        _uiState.update { currentState ->
                            currentState.copy(
                                isLoading = false,
                                panels = validPanels,
                                groupedPanels = groupedPanels,
                                clientNames = clientsMap,
                                error = null,
                                lastUpdate = System.currentTimeMillis()
                            )
                        }
                    }
                } else {
                    Log.e(TAG, "No authenticated user found")
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "Error cargando paneles: no hay usuario autenticado"
                    ) }
                }
            } catch (e: Exception) {
                // Solo actualizar el error si la coroutine no fue cancelada intencionalmente
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error loading panels", e)
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = e.message ?: "Error desconocido"
                    ) }
                } else {
                    Log.d(TAG, "Panel loading cancelled intentionally")
                }
            }
        }
    }

    /**
     * Carga los nombres de los clientes para mostrarlos en la interfaz,
     * usando Source.SERVER para garantizar datos actualizados
     */
    private suspend fun loadClientNames(clientDocName: String?): Map<String, String> {
        return try {
            if (clientDocName != null) {
                // Para usuario normal, solo necesitamos un cliente
                // Usar Source.SERVER para forzar consulta fresca
                val clientSnapshot = firestore
                    .collection("hdd-monitor/accounts/clients")
                    .document(clientDocName)
                    .get(Source.SERVER)
                    .await()

                if (clientSnapshot.exists()) {
                    val clientName = clientSnapshot.getString("name")
                    if (clientName != null) {
                        mapOf(clientDocName to clientName)
                    } else {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
            } else {
                // Para admin, cargar todos los clientes usando también Source.SERVER
                val clients = mutableMapOf<String, String>()
                val clientsSnapshot = firestore
                    .collection("hdd-monitor/accounts/clients")
                    .get(Source.SERVER)
                    .await()

                for (doc in clientsSnapshot.documents) {
                    val clientName = doc.getString("name")
                    if (clientName != null) {
                        clients[doc.id] = clientName
                    }
                }
                clients
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading client names from server, trying cache", e)

            // Si falla la consulta al servidor, intentar con caché
            try {
                if (clientDocName != null) {
                    val clientSnapshot = firestore
                        .collection("hdd-monitor/accounts/clients")
                        .document(clientDocName)
                        .get(Source.CACHE)
                        .await()

                    if (clientSnapshot.exists()) {
                        val clientName = clientSnapshot.getString("name")
                        if (clientName != null) {
                            return mapOf(clientDocName to clientName)
                        }
                    }
                    emptyMap()
                } else {
                    val clients = mutableMapOf<String, String>()
                    val clientsSnapshot = firestore
                        .collection("hdd-monitor/accounts/clients")
                        .get(Source.CACHE)
                        .await()

                    for (doc in clientsSnapshot.documents) {
                        val clientName = doc.getString("name")
                        if (clientName != null) {
                            clients[doc.id] = clientName
                        }
                    }
                    clients
                }
            } catch (cacheEx: Exception) {
                Log.e(TAG, "Error accessing client names cache", cacheEx)
                emptyMap()
            }
        }
    }

    /**
     * Inicia una actualización periódica para mantener los datos frescos
     */
    fun startPeriodicRefresh() {
        Log.d(TAG, "startPeriodicRefresh() called")

        // Cancelar trabajo anterior si existe
        refreshJob?.cancel()

        refreshJob = viewModelScope.launch {
            while (isActive) {
                try {
                    // Esperar el intervalo configurado
                    delay(REFRESH_INTERVAL)

                    // Verificar si deberíamos continuar
                    if (!isActive) break

                    Log.d(TAG, "Ejecutando actualización periódica")
                    refreshPanels()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        break
                    }
                    Log.e(TAG, "Error en actualización periódica", e)
                    delay(5000) // Esperar un poco antes de reintentar
                }
            }
        }
    }

    /**
     * Detiene la actualización periódica
     */
    fun stopPeriodicRefresh() {
        Log.d(TAG, "stopPeriodicRefresh() called")
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * Función para forzar una recarga de todos los paneles
     * Útil para acciones manuales (botón de refresh) o recuperación de errores
     */
    fun refreshPanels() {
        Log.d(TAG, "refreshPanels() called - Recargando datos forzadamente")

        viewModelScope.launch {
            try {
                // Primero limpiar listeners y cachés para forzar recargas frescas
                panelRepository.clearListeners()

                // Esperar un poco para permitir que se complete la limpieza
                delay(300)

                // Ahora cargar paneles de nuevo
                loadPanels()
            } catch (e: Exception) {
                Log.e(TAG, "Error forzando refresco de paneles", e)
            }
        }
    }

    /**
     * Cancela tareas en curso para gestión de ciclo de vida
     */
    fun cancelCurrentJob() {
        Log.d(TAG, "cancelCurrentJob() called")
        panelsJob?.cancel()
        panelsJob = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelCurrentJob()
        statusUpdateJob?.cancel()
        refreshJob?.cancel()
        panelRepository.clearListeners()
        Log.d(TAG, "ViewModel cleared, all listeners and jobs cancelled")
    }

    data class DashboardUiState(
        val isLoading: Boolean = true,
        val panels: List<Panel> = emptyList(),
        val groupedPanels: Map<String, List<Panel>> = emptyMap(),
        val clientNames: Map<String, String> = emptyMap(),
        val error: String? = null,
        val lastUpdate: Long = System.currentTimeMillis()
    ) {
        override fun toString(): String {
            return "DashboardUiState(" +
                    "isLoading=$isLoading, " +
                    "panels=${panels.size}, " +
                    "groupedPanels=${groupedPanels.keys}, " +
                    "clientNames=${clientNames.size}, " +
                    "error=$error, " +
                    "lastUpdate=$lastUpdate" +
                    ")"
        }
    }
}