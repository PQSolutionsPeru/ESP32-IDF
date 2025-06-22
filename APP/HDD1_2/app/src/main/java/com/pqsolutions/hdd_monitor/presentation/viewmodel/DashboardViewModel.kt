package com.pqsolutions.hdd_monitor.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.util.Constants.DocumentPrefixes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val panelRepository: PanelRepository,
    private val userRepository: UserRepository,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var panelsJob: Job? = null

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    init {
        Log.d(TAG, "DashboardViewModel initialized")
        loadPanels()
    }

    fun loadPanels() {
        Log.d(TAG, "loadPanels() called")

        _uiState.update { it.copy(isLoading = true, error = null) }

        panelsJob?.cancel()
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

                    val clientsMap = loadClientNames(clientDocName)

                    panelRepository.getPanels(clientDocName).collect { panels ->
                        Log.d(TAG, "Received ${panels.size} panels from repository")

                        panels.forEach { panel ->
                            Log.d(TAG, "Panel: ${panel.name}, Relays: ${panel.relays.size}, Active: ${panel.activeRelays.size}")
                        }

                        val validPanels = panels.filter { panel ->
                            panel.documentName.startsWith(DocumentPrefixes.PANEL) &&
                                    panel.clientName.startsWith(DocumentPrefixes.CLIENT)
                        }

                        val groupedPanels = validPanels.groupBy {
                            clientsMap[it.clientName] ?: it.clientName
                        }

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

                        Log.d(TAG, "Dashboard panels updated successfully: ${validPanels.size} panels")
                    }
                } else {
                    Log.e(TAG, "No authenticated user found")
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "Error cargando paneles: no hay usuario autenticado"
                    ) }
                }
            } catch (e: Exception) {
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

    private suspend fun loadClientNames(clientDocName: String?): Map<String, String> {
        return try {
            if (clientDocName != null) {
                val clientSnapshot = firestore
                    .collection("hdd-monitor/accounts/clients")
                    .document(clientDocName)
                    .get()
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
                val clients = mutableMapOf<String, String>()
                val clientsSnapshot = firestore
                    .collection("hdd-monitor/accounts/clients")
                    .get()
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
            Log.e(TAG, "Error loading client names", e)
            emptyMap()
        }
    }

    override fun onCleared() {
        try {
            super.onCleared()
            Log.d(TAG, "DashboardViewModel clearing")

            panelsJob?.cancel()
            panelsJob = null
            panelRepository.clearListeners()

            Log.d(TAG, "DashboardViewModel cleared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in DashboardViewModel.onCleared", e)
        }
    }

    data class DashboardUiState(
        val isLoading: Boolean = true,
        val panels: List<Panel> = emptyList(),
        val groupedPanels: Map<String, List<Panel>> = emptyMap(),
        val clientNames: Map<String, String> = emptyMap(),
        val error: String? = null,
        val lastUpdate: Long = System.currentTimeMillis()
    )
}