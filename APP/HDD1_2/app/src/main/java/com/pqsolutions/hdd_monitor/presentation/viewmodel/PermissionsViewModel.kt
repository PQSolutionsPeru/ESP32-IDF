package com.pqsolutions.hdd_monitor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.pqsolutions.hdd_monitor.data.util.PermissionsHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val permissionsHelper: PermissionsHelper
) : ViewModel() {

    private val _permissionsState = MutableStateFlow<PermissionsState>(PermissionsState.Loading)
    val permissionsState: StateFlow<PermissionsState> = _permissionsState

    fun checkPermissions() {
        val permissionsMap = permissionsHelper.checkPermissions()
        val batteryOptimizationDisabled = permissionsHelper.isBatteryOptimizationDisabled()

        if (permissionsMap.all { it.value } && batteryOptimizationDisabled) {
            _permissionsState.value = PermissionsState.AllGranted
        } else {
            _permissionsState.value = PermissionsState.NeedsPermissions(
                permissions = permissionsMap.filterValues { !it }.keys.toList(),
                needsBatteryOptimization = !batteryOptimizationDisabled
            )
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
}