package com.pqsolutions.hdd_monitor.presentation.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import kotlinx.coroutines.launch

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
    Log.d(TAG, "Starting AppNavigation composition")
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
                    safeNavigateToLogin(navController)
                }
            )
        }

        composable(Screen.Login.route) {
            val loginViewModel: LoginViewModel = hiltViewModel()
            LoginScreen(
                loginViewModel = loginViewModel,
                onLoginClick = { email, password ->
                    Log.d(TAG, "Login attempt with email: $email")
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

            LaunchedEffect(Unit) {
                handleDeepLink(navController, viewModel)
            }

            Log.d(TAG, "Navigating to Dashboard. User role: ${uiState.userData?.role}")
            when (uiState.userData?.role) {
                UserRole.ADMIN -> {
                    AdminDashboardScreen(
                        viewModel = dashboardViewModel,
                        notificationViewModel = notificationViewModel,
                        onLogoutClick = { handleLogout(viewModel) },
                        onManageUsersClick = { safeNavigate(navController, Screen.ClientManagement.route) },
                        onViewEventsClick = { safeNavigate(navController, Screen.Events.route) },
                        onViewNotificationHistoryClick = { safeNavigate(navController, Screen.NotificationHistory.route) },
                        onManageESP32Click = { safeNavigate(navController, Screen.ESP32Management.route) },
                        hasPendingNotifications = hasPendingNotifications,
                        selectedPanelId = panelId
                    )
                }
                UserRole.USER -> {
                    UserDashboardScreen(
                        viewModel = dashboardViewModel,
                        notificationViewModel = notificationViewModel,
                        onLogoutClick = { handleLogout(viewModel) },
                        onViewNotificationHistoryClick = { safeNavigate(navController, Screen.NotificationHistory.route) },
                        onViewEventsClick = { safeNavigate(navController, Screen.Events.route) },
                        onManageESP32Click = { safeNavigate(navController, Screen.ESP32Management.route) },
                        hasPendingNotifications = hasPendingNotifications,
                        selectedPanelId = panelId
                    )
                }
                null -> {
                    Log.d(TAG, "Invalid user role, navigating to Login")
                    LaunchedEffect(Unit) {
                        safeNavigateToLogin(navController)
                    }
                }
            }
        }

        composable(Screen.ClientManagement.route) {
            ClientManagementScreen(
                onBackClick = { safeNavigateBack(navController) },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { safeNavigate(navController, Screen.NotificationHistory.route) }
            )
        }

        composable(Screen.NotificationHistory.route) {
            NotificationHistoryScreen(
                notificationViewModel = notificationViewModel,
                onBackClick = {
                    notificationViewModel.clearError()
                    safeNavigateBack(navController)
                },
                hasPendingNotifications = hasPendingNotifications,
                onNavigateToEvent = { eventId ->
                    if (eventId.isBlank()) {
                        Log.d(TAG, "Navegando a la pantalla general de eventos desde notificaciones")
                        navController.navigate(Screen.Events.route) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    } else {
                        Log.d(TAG, "Navegando a evento específico: $eventId")
                        navController.navigate(Screen.eventDetail(eventId)) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                onNavigateToPanel = { panelId ->
                    navController.navigate(Screen.panelDetail(panelId)) {
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }

        composable(Screen.Events.route) {
            EventScreen(
                onBackClick = { safeNavigateBack(navController) },
                isAdmin = uiState.userData?.role == UserRole.ADMIN,
                hasPendingNotifications = hasPendingNotifications,
                onNavigateToEvent = { eventId ->
                    navController.navigate(Screen.eventDetail(eventId)) {
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToPanel = { panelId ->
                    navController.navigate(Screen.panelDetail(panelId)) {
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onViewNotificationHistoryClick = {
                    safeNavigate(navController, Screen.NotificationHistory.route)
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
                onBackClick = { safeNavigateBack(navController) },
                isAdmin = uiState.userData?.role == UserRole.ADMIN,
                hasPendingNotifications = hasPendingNotifications,
                eventId = eventId,
                onNavigateToEvent = { newEventId ->
                    navController.navigate(Screen.eventDetail(newEventId)) {
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToPanel = { panelId ->
                    navController.navigate(Screen.panelDetail(panelId)) {
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onViewNotificationHistoryClick = {
                    safeNavigate(navController, Screen.NotificationHistory.route)
                }
            )
        }

        composable(Screen.RelayControl.route) {
            RelayControlScreen(
                onBackClick = { safeNavigateBack(navController) },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { safeNavigate(navController, Screen.NotificationHistory.route) }
            )
        }

        composable(Screen.ESP32Management.route) {
            ESP32ManagementScreen(
                onBackClick = { safeNavigateBack(navController) },
                onCreatePanel = {
                    safeNavigate(navController, Screen.panelConfiguration())
                },
                onEditPanel = { panelId ->
                    safeNavigate(navController, Screen.panelConfiguration(panelId))
                },
                onCustomizeRelays = { panelId ->
                    safeNavigate(navController, Screen.RelayControl.route)
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { safeNavigate(navController, Screen.NotificationHistory.route) }
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
                onBackClick = { safeNavigateBack(navController) },
                onSaveSuccess = {
                    safeNavigateBack(navController)
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { safeNavigate(navController, Screen.NotificationHistory.route) }
            )
        }

        composable(Screen.PanelConfiguration.route) {
            PanelConfigurationScreen(
                panelId = null,
                onBackClick = { safeNavigateBack(navController) },
                onSaveSuccess = {
                    safeNavigateBack(navController)
                },
                hasPendingNotifications = hasPendingNotifications,
                onNotificationClick = { safeNavigate(navController, Screen.NotificationHistory.route) }
            )
        }
    }

    LaunchedEffect(uiState.currentRoute) {
        Log.d(TAG, "LaunchedEffect: Current route changed to ${uiState.currentRoute}")
        val currentRoute = uiState.currentRoute
        val currentDestination = navController.currentDestination?.route

        if (currentRoute != currentDestination && currentRoute.isNotBlank()) {
            try {
                kotlinx.coroutines.delay(150)

                when {
                    currentRoute == Screen.Login.route -> {
                        navController.navigate(currentRoute) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    currentRoute == Screen.Dashboard.route -> {
                        if (currentDestination != Screen.Dashboard.route) {
                            navController.navigate(currentRoute) {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    else -> {
                        navController.navigate(currentRoute) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Navigation error: ${e.message}", e)
                try {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback navigation failed: ${e.message}")
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

private fun handleLogout(viewModel: MainViewModel) {
    Log.d(TAG, "Logout clicked")
    viewModel.onEvent(MainUiEvent.Logout)
}

private fun safeNavigate(navController: NavHostController, route: String) {
    try {
        val currentRoute = navController.currentDestination?.route

        if (currentRoute == route) {
            Log.d("Navigation", "Ya estamos en la ruta $route, no se hace nada")
            return
        }

        Log.d("Navigation", "Navegando de '$currentRoute' a '$route'")

        when (route) {
            Screen.Dashboard.route -> {
                navController.navigate(route) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
            Screen.Login.route -> {
                navController.navigate(route) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
            else -> {
                navController.navigate(route) {
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Navigation", "Error durante la navegación a $route: ${e.message}", e)
        try {
            navController.navigate(Screen.Dashboard.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        } catch (e: Exception) {
            Log.e("Navigation", "Error en navegación de recuperación: ${e.message}", e)
        }
    }
}

private fun safeNavigateToLogin(navController: NavHostController) {
    try {
        Log.d("Navigation", "Navegando a Login con limpieza de backstack")
        navController.navigate(Screen.Login.route) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    } catch (e: Exception) {
        Log.e("Navigation", "Error durante la navegación a Login: ${e.message}", e)
    }
}

private fun safeNavigateBack(navController: NavHostController) {
    try {
        val currentRoute = navController.currentDestination?.route
        Log.d("Navigation", "Navegando hacia atrás desde: $currentRoute")

        if (currentRoute == Screen.Dashboard.route) {
            Log.d("Navigation", "Ya estamos en Dashboard, no hacemos nada")
            return
        }

        if (navController.currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED
            ) == false) {
            Log.d("Navigation", "Navegación en curso, esperando...")
            return
        }

        val canPop = navController.previousBackStackEntry != null

        if (canPop) {
            try {
                val result = navController.popBackStack()
                Log.d("Navigation", "PopBackStack exitoso: $result")
                if (!result) {
                    kotlinx.coroutines.MainScope().launch {
                        kotlinx.coroutines.delay(100)
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Navigation", "Error en popBackStack: ${e.message}", e)
                kotlinx.coroutines.MainScope().launch {
                    kotlinx.coroutines.delay(100)
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        } else {
            Log.d("Navigation", "No se puede hacer pop, navegando a Dashboard")
            navController.navigate(Screen.Dashboard.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    } catch (e: Exception) {
        Log.e("Navigation", "Error durante la navegación hacia atrás: ${e.message}", e)
        kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(100)
            try {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            } catch (e: Exception) {
                Log.e("Navigation", "Error en navegación de recuperación: ${e.message}", e)
            }
        }
    }
}

private suspend fun handleDeepLink(navController: NavHostController, viewModel: MainViewModel) {
    navController.currentBackStackEntry?.arguments?.let { args ->
        val notificationType = args.getString("notificationType")
        val panelId = args.getString("panelId")
        val relayName = args.getString("relayName")

        when (notificationType) {
            "relay_update" -> {
                if (panelId != null && relayName != null) {
                    safeNavigate(navController, Screen.Dashboard.route)
                }
            }
            "event" -> safeNavigate(navController, Screen.Events.route)
            "notification" -> safeNavigate(navController, Screen.NotificationHistory.route)
        }
    }
}