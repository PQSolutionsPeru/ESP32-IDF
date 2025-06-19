package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.Relay
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.presentation.state.RelayControlState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class RelayControlViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val userRepository: UserRepository,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    companion object {
        private const val TAG = "RelayControlViewModel"
    }

    private val _uiState = MutableStateFlow(RelayControlState())
    val uiState: StateFlow<RelayControlState> = _uiState.asStateFlow()

    private var currentJob: Job? = null

    init {
        Log.d(TAG, "RelayControlViewModel inicializado")
        loadPanels()
    }

    fun loadSpecificPanel(panelId: String?) {
        if (panelId.isNullOrBlank()) {
            loadPanels()
            return
        }

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Cargando panel específico: $panelId")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val currentUser = userRepository.getCurrentUser()
                if (currentUser == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Usuario no encontrado")
                    return@launch
                }

                val clientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> findPanelClient(panelId)
                }

                if (clientDocName != null) {
                    observePanel(clientDocName, panelId)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Panel no encontrado")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error cargando panel", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Error: ${e.message}")
            }
        }
    }

    fun loadPanels() {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Cargando todos los paneles")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val currentUser = userRepository.getCurrentUser()
                if (currentUser == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Usuario no encontrado")
                    return@launch
                }

                val clientDocName = when (currentUser.role) {
                    UserRole.USER -> currentUser.clientDocName
                    UserRole.ADMIN -> null
                }

                val clientsMap = loadClientNames(clientDocName)
                _uiState.value = _uiState.value.copy(clientNames = clientsMap)

                panelRepository.getPanels(clientDocName)
                    .catch { error ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Error: ${error.message}")
                    }
                    .collect { panels ->
                        val groupedPanels = panels.groupBy { panel ->
                            clientsMap[panel.clientName] ?: panel.clientName
                        }

                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            panels = panels,
                            groupedPanels = groupedPanels,
                            error = null
                        )
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Error cargando paneles", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Error: ${e.message}")
            }
        }
    }

    private suspend fun observePanel(clientDocName: String, panelId: String) {
        panelRepository.observePanelUpdates(clientDocName, panelId)
            .catch { error ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Error: ${error.message}")
            }
            .collect { panel ->
                if (panel != null) {
                    val clientsMap = _uiState.value.clientNames
                    val clientDisplayName = clientsMap[panel.clientName] ?: panel.clientName
                    val groupedPanels: Map<String, List<Panel>> = mapOf(clientDisplayName to listOf(panel))

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        panels = listOf(panel),
                        groupedPanels = groupedPanels,
                        error = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Panel no encontrado")
                }
            }
    }

    private suspend fun findPanelClient(panelId: String): String? {
        return try {
            val allClientsSnapshot = firestore.collection("hdd-monitor/accounts/clients").get().await()
            for (clientDoc in allClientsSnapshot.documents) {
                if (clientDoc.exists()) {
                    val clientId = clientDoc.id
                    val panelExists = panelRepository.verifyPanelExists(clientId, panelId)
                    if (panelExists) return clientId
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando cliente del panel", e)
            null
        }
    }

    private suspend fun loadClientNames(clientDocName: String?): Map<String, String> {
        return try {
            if (clientDocName != null) {
                val clientSnapshot = firestore.collection("hdd-monitor/accounts/clients").document(clientDocName).get().await()
                if (clientSnapshot.exists()) {
                    val clientName = clientSnapshot.getString("name")
                    if (clientName != null) mapOf(clientDocName to clientName) else emptyMap()
                } else emptyMap()
            } else {
                val clients = mutableMapOf<String, String>()
                val clientsSnapshot = firestore.collection("hdd-monitor/accounts/clients").get().await()
                for (doc in clientsSnapshot.documents) {
                    val clientName = doc.getString("name")
                    if (clientName != null) clients[doc.id] = clientName
                }
                clients
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando nombres de clientes", e)
            emptyMap()
        }
    }

    fun refreshPanels() = loadPanels()
    fun refreshSpecificPanel(panelId: String?) = loadSpecificPanel(panelId)

    fun updateRelayConfig(panel: Panel, updatedRelay: Relay) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(operationInProgress = true)

                val updateData = mutableMapOf<String, Any>(
                    "isActive" to updatedRelay.isActive,
                    "contactType" to updatedRelay.contactType,
                    "lastUpdate" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                val customName = updatedRelay.customName?.trim()
                if (!customName.isNullOrBlank() && customName != updatedRelay.name) {
                    updateData["customName"] = customName
                } else {
                    updateData["customName"] = com.google.firebase.firestore.FieldValue.delete()
                }

                firestore.document("hdd-monitor/accounts/clients/${panel.clientName}/panels/${panel.documentName}/relays/${updatedRelay.name}")
                    .update(updateData)
                    .await()

                _uiState.value = _uiState.value.copy(operationInProgress = false)

            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando configuración", e)
                _uiState.value = _uiState.value.copy(operationInProgress = false, error = "Error: ${e.message}")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "RelayControlViewModel limpiado")
        currentJob?.cancel()
    }
}