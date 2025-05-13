package com.pqsolutions.hdd_monitor.presentation.state

import com.pqsolutions.hdd_monitor.data.UserData

data class MainUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isFirstLaunch: Boolean = true,
    val userData: UserData? = null,
    val error: String? = null,
    val currentRoute: String = "login",
    val theme: String = "system",
    val language: String = "es",
    val notificationsEnabled: Boolean = true
)