package com.pqsolutions.hdd_monitor.presentation.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pqsolutions.hdd_monitor.domain.model.UserRole
import com.pqsolutions.hdd_monitor.presentation.screens.*
import com.pqsolutions.hdd_monitor.presentation.state.MainUiEvent
import com.pqsolutions.hdd_monitor.presentation.state.MainUiState
import com.pqsolutions.hdd_monitor.presentation.viewmodel.DashboardViewModel
import com.pqsolutions.hdd_monitor.presentation.viewmodel.LoginViewModel
import com.pqsolutions.hdd_monitor.presentation.viewmodel.MainViewModel
import com.pqsolutions.hdd_monitor.presentation.viewmodel.NotificationViewModel

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

    companion object {
        fun eventDetail(eventId: String) = "events/$eventId"
        fun panelDetail(panelId: String) = "dashboard?panelId=$panelId"
        fun panelConfiguration(panelId: String? = null) = if (panelId != null) "panel_configuration/$panelId" else "panel_configuration"
    }
}

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    startDestination: String = Screen.Login.route
) {
    val uiState by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val hasPendingNotifications by viewModel.hasPendingNotifications.collectAsState()

    val dashboardViewModel: DashboardViewModel = hiltViewModel()
    val notificationViewModel: NotificationViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = getStartDestination(uiState)
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

        composable(
            route = "${Screen.Dashboard.route}?panelId={panelId}",
            arguments = listOf(
                navArgument("panelId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val panelId = entry.arguments?.getString("panelId")

            when (uiState.userData?.role) {
                UserRole.ADMIN -> {
                    AdminDashboardScreen(
                        viewModel = dashboardViewModel,
                        notificationViewModel = notificationViewModel,
                        onLogoutClick = {
                            viewModel.onEvent(MainUiEvent.Logout)
                        },
                        onManageUsersClick = {
                            navController.navigate(Screen.ClientManagement.route)
                        },
                        onViewEventsClick = {
                            navController.navigate(Screen.Events.route)
                        },
                        onViewNotificationHistoryClick = {
                            navController.navigate(Screen.NotificationHistory.route)
                        },
                        onManageESP32Click = {
                            navController.navigate(Screen.ESP32Management.route)
                        },
                        hasPendingNotifications = hasPendingNotifications,
                        selectedPanelId = panelId
                    )
                }
                UserRole.USER -> {
                    UserDashboardScreen(
                        viewModel = dashboardViewModel,
                        notificationViewModel = notificationViewModel,
                        onLogoutClick = {
                            viewModel.onEvent(MainUiEvent.Logout)
                        },
                        onViewNotificationHistoryClick = {
                            navController.navigate(Screen.NotificationHistory.route)
                        },
                        onViewEventsClick = {
                            navController.navigate(Screen.Events.route)
                        },
                        onManageESP32Click = {
                            navController.navigate(Screen.ESP32Management.route)
                        },
                        hasPendingNotifications = hasPendingNotifications,
                        selectedPanelId = panelId
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
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = {
                    navController.navigate(Screen.NotificationHistory.route)
                }
            )
        }

        composable(Screen.NotificationHistory.route) {
            NotificationHistoryScreen(
                notificationViewModel = notificationViewModel,
                onBackClick = {
                    notificationViewModel.clearError()
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNavigateToEvent = { eventId ->
                    if (eventId.isBlank()) {
                        navController.navigate(Screen.Events.route)
                    } else {
                        navController.navigate(Screen.eventDetail(eventId))
                    }
                },
                onNavigateToPanel = { panelId ->
                    navController.navigate(Screen.panelDetail(panelId))
                }
            )
        }

        composable(Screen.Events.route) {
            EventScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                isAdmin = uiState.userData?.role == UserRole.ADMIN,
                hasPendingNotifications = hasPendingNotifications,
                onNavigateToEvent = { eventId ->
                    navController.navigate(Screen.eventDetail(eventId))
                },
                onNavigateToPanel = { panelId ->
                    navController.navigate(Screen.panelDetail(panelId))
                },
                onViewNotificationHistoryClick = {
                    navController.navigate(Screen.NotificationHistory.route)
                }
            )
        }

        composable(
            route = "${Screen.Events.route}/{eventId}",
            arguments = listOf(
                navArgument("eventId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getString("eventId")
            EventScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                isAdmin = uiState.userData?.role == UserRole.ADMIN,
                hasPendingNotifications = hasPendingNotifications,
                eventId = eventId,
                onNavigateToEvent = { newEventId ->
                    navController.navigate(Screen.eventDetail(newEventId))
                },
                onNavigateToPanel = { panelId ->
                    navController.navigate(Screen.panelDetail(panelId))
                },
                onViewNotificationHistoryClick = {
                    navController.navigate(Screen.NotificationHistory.route)
                }
            )
        }

        composable(Screen.RelayControl.route) {
            RelayControlScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = {
                    navController.navigate(Screen.NotificationHistory.route)
                }
            )
        }

        composable(Screen.ESP32Management.route) {
            ESP32ManagementScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onCreatePanel = {
                    navController.navigate(Screen.panelConfiguration())
                },
                onEditPanel = { panelId ->
                    navController.navigate(Screen.panelConfiguration(panelId))
                },
                onCustomizeRelays = { panelId ->
                    navController.navigate(Screen.RelayControl.route)
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = {
                    navController.navigate(Screen.NotificationHistory.route)
                }
            )
        }

        composable(
            route = "${Screen.PanelConfiguration.route}/{panelId}",
            arguments = listOf(
                navArgument("panelId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val panelId = backStackEntry.arguments?.getString("panelId")
            PanelConfigurationScreen(
                panelId = panelId,
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onSaveSuccess = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = {
                    navController.navigate(Screen.NotificationHistory.route)
                }
            )
        }

        composable(Screen.PanelConfiguration.route) {
            PanelConfigurationScreen(
                panelId = null,
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onSaveSuccess = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = {
                    navController.navigate(Screen.NotificationHistory.route)
                }
            )
        }
    }

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == Screen.Login.route || currentRoute == Screen.Onboarding.route) {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        } else {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute != Screen.Login.route && currentRoute != Screen.Onboarding.route) {
                navController.navigate(Screen.Login.route) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }
}

private fun getStartDestination(uiState: MainUiState): String {
    return when {
        uiState.isFirstLaunch -> Screen.Onboarding.route
        uiState.isLoggedIn -> Screen.Dashboard.route
        else -> Screen.Login.route
    }
}