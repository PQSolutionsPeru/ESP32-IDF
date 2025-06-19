package com.pqsolutions.hdd_monitor.presentation.navigation

import android.util.Log
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.presentation.screens.*
import com.pqsolutions.hdd_monitor.presentation.state.MainUiEvent
import com.pqsolutions.hdd_monitor.presentation.viewmodel.LoginViewModel
import com.pqsolutions.hdd_monitor.presentation.viewmodel.MainViewModel

private const val TAG = "AppNavigation"

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Login : Screen("login")
    object Dashboard : Screen("dashboard")
    object ClientManagement : Screen("client_management")
    object NotificationHistory : Screen("notification_history")
    object Events : Screen("events")
    object RelayControl : Screen("relay_control")
    object ESP32Management : Screen("esp32_management")
    object PanelConfiguration : Screen("panel_configuration")
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val hasPendingNotifications by viewModel.hasPendingNotifications.collectAsState()

    val startDestination = when {
        uiState.isFirstLaunch -> Screen.Onboarding.route
        uiState.isLoggedIn -> Screen.Dashboard.route
        else -> Screen.Login.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() }
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onFinish = {
                    viewModel.onEvent(MainUiEvent.FinishOnboarding)
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Login.route) {
            val loginViewModel: LoginViewModel = hiltViewModel()
            LoginScreen(
                loginViewModel = loginViewModel,
                onLoginClick = { email, password ->
                    viewModel.onEvent(MainUiEvent.Login(email, password))
                }
            )
        }

        composable(Screen.Dashboard.route) {
            when (uiState.userData?.role) {
                UserRole.ADMIN -> {
                    AdminDashboardScreen(
                        onLogoutClick = { viewModel.onEvent(MainUiEvent.Logout) },
                        onManageUsersClick = { navController.navigate(Screen.ClientManagement.route) },
                        onViewEventsClick = { navController.navigate(Screen.Events.route) },
                        onViewNotificationHistoryClick = { navController.navigate(Screen.NotificationHistory.route) },
                        onManageESP32Click = { navController.navigate(Screen.ESP32Management.route) },
                        hasPendingNotifications = hasPendingNotifications,
                        selectedPanelId = null
                    )
                }
                UserRole.USER -> {
                    UserDashboardScreen(
                        onLogoutClick = { viewModel.onEvent(MainUiEvent.Logout) },
                        onViewNotificationHistoryClick = { navController.navigate(Screen.NotificationHistory.route) },
                        onViewEventsClick = { navController.navigate(Screen.Events.route) },
                        onManageESP32Click = { navController.navigate(Screen.ESP32Management.route) },
                        hasPendingNotifications = hasPendingNotifications,
                        selectedPanelId = null
                    )
                }
                null -> {
                    LaunchedEffect(Unit) {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
        }

        composable(Screen.ClientManagement.route) {
            ClientManagementScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route)
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { navController.navigate(Screen.NotificationHistory.route) }
            )
        }

        composable(Screen.NotificationHistory.route) {
            NotificationHistoryScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route)
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNavigateToEvent = { navController.navigate(Screen.Events.route) },
                onNavigateToPanel = { navController.navigate(Screen.Dashboard.route) }
            )
        }

        composable(Screen.Events.route) {
            EventScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route)
                    }
                },
                isAdmin = uiState.userData?.role == UserRole.ADMIN,
                hasPendingNotifications = hasPendingNotifications,
                onNavigateToEvent = { navController.navigate(Screen.Events.route) },
                onNavigateToPanel = { navController.navigate(Screen.Dashboard.route) },
                onViewNotificationHistoryClick = { navController.navigate(Screen.NotificationHistory.route) }
            )
        }

        composable(Screen.RelayControl.route) {
            RelayControlScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route)
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { navController.navigate(Screen.NotificationHistory.route) }
            )
        }

        composable(Screen.ESP32Management.route) {
            ESP32ManagementScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route)
                    }
                },
                onCreatePanel = { navController.navigate(Screen.PanelConfiguration.route) },
                onEditPanel = { navController.navigate(Screen.PanelConfiguration.route) },
                onCustomizeRelays = { navController.navigate(Screen.RelayControl.route) },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { navController.navigate(Screen.NotificationHistory.route) }
            )
        }

        composable(Screen.PanelConfiguration.route) {
            PanelConfigurationScreen(
                panelId = null,
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.ESP32Management.route)
                    }
                },
                onSaveSuccess = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.ESP32Management.route)
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { navController.navigate(Screen.NotificationHistory.route) }
            )
        }
    }

    LaunchedEffect(uiState.isLoggedIn, startDestination) {
        val currentRoute = navController.currentDestination?.route

        if (uiState.isLoggedIn && currentRoute == Screen.Login.route) {
            navController.navigate(Screen.Dashboard.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
                launchSingleTop = true
            }
        } else if (!uiState.isLoggedIn && currentRoute != Screen.Login.route && currentRoute != Screen.Onboarding.route) {
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}