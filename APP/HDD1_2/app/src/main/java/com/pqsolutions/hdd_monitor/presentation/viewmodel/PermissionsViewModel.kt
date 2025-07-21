package com.pqsolutions.hdd_monitor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pqsolutions.hdd_monitor.data.util.PermissionsHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val permissionsHelper: PermissionsHelper
) : ViewModel() {

    private val _permissionsState = MutableStateFlow<PermissionsState>(PermissionsState.Loading)
    val permissionsState: StateFlow<PermissionsState> = _permissionsState

    private val _currentStep = MutableStateFlow<PermissionStep>(PermissionStep.SystemPermissions)
    val currentStep: StateFlow<PermissionStep> = _currentStep

    fun checkPermissions() {
        val permissionsMap = permissionsHelper.checkPermissions()
        val batteryOptimizationDisabled = permissionsHelper.isBatteryOptimizationDisabled()

        if (permissionsMap.all { it.value } && batteryOptimizationDisabled) {
            _permissionsState.value = PermissionsState.AllGranted
            _currentStep.value = PermissionStep.Completed
        } else {
            val pendingPermissions = permissionsMap.filterValues { !it }.keys.toList()
            _permissionsState.value = PermissionsState.NeedsPermissions(
                permissions = pendingPermissions,
                needsBatteryOptimization = !batteryOptimizationDisabled
            )

            _currentStep.value = when {
                pendingPermissions.isNotEmpty() -> PermissionStep.SystemPermissions
                !batteryOptimizationDisabled -> PermissionStep.BatteryOptimization
                else -> PermissionStep.Completed
            }
        }
    }

    fun handlePermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            val batteryOptimizationDisabled = permissionsHelper.isBatteryOptimizationDisabled()

            if (!batteryOptimizationDisabled) {
                _currentStep.value = PermissionStep.BatteryOptimization
                _permissionsState.value = PermissionsState.NeedsPermissions(
                    permissions = emptyList(),
                    needsBatteryOptimization = true
                )
            } else {
                _permissionsState.value = PermissionsState.AllGranted
                _currentStep.value = PermissionStep.Completed
            }
        } else {
            checkPermissions()
        }
    }

    fun onBatteryOptimizationHandled() {
        viewModelScope.launch {
            delay(1000)
            checkPermissions()
        }
    }

    fun getNextActionMessage(): String {
        return when (val state = _permissionsState.value) {
            is PermissionsState.NeedsPermissions -> {
                when {
                    state.permissions.isNotEmpty() && state.needsBatteryOptimization ->
                        "Configurar permisos y batería"
                    state.permissions.isNotEmpty() ->
                        "Configurar permisos"
                    state.needsBatteryOptimization ->
                        "Configurar batería"
                    else -> "¡Listo!"
                }
            }
            is PermissionsState.AllGranted -> "¡Todo listo!"
            PermissionsState.Loading -> "Verificando..."
        }
    }

    fun getBatteryOptimizationIntent() = permissionsHelper.getBatteryOptimizationIntent()
    fun getNotificationSettingsIntent() = permissionsHelper.getNotificationSettingsIntent()

    sealed class PermissionsState {
        object Loading : PermissionsState()
        object AllGranted : PermissionsState()
        data class NeedsPermissions(
            val permissions: List<String>,
            val needsBatteryOptimization: Boolean
        ) : PermissionsState()
    }

    sealed class PermissionStep {
        object SystemPermissions : PermissionStep()
        object BatteryOptimization : PermissionStep()
        object Completed : PermissionStep()
    }
}