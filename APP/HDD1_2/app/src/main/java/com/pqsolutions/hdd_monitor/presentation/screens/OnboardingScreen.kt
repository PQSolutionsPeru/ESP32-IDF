package com.pqsolutions.hdd_monitor.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.presentation.components.HddButton
import com.pqsolutions.hdd_monitor.presentation.util.Dimensions
import com.pqsolutions.hdd_monitor.presentation.viewmodel.PermissionsViewModel
import com.pqsolutions.hdd_monitor.presentation.viewmodel.PermissionsViewModel.PermissionsState
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    permissionsViewModel: PermissionsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val permissionsState by permissionsViewModel.permissionsState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showPermissionsSnackbar by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsViewModel.handlePermissionResult(permissions)
        if (permissions.containsValue(false)) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = "Se requieren todos los permisos para el funcionamiento correcto",
                    duration = SnackbarDuration.Long,
                    withDismissAction = true
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionsViewModel.checkPermissions()
    }

    val pages = listOf(
        OnboardingPage(
            title = "Bienvenido a HDD Monitor",
            description = "Monitorea y gestiona tus paneles de incendio de manera fácil y eficiente.",
            imageRes = R.drawable.onboarding_image
        ),
        OnboardingPage(
            title = "Alertas en tiempo real",
            description = "Recibe notificaciones instantáneas sobre el estado de tus paneles.",
            imageRes = R.drawable.onboarding_image
        ),
        OnboardingPage(
            title = "Gestión simplificada",
            description = "Administra usuarios, paneles y eventos desde una sola aplicación.",
            imageRes = R.drawable.onboarding_image
        ),
        OnboardingPage(
            title = "Permisos necesarios",
            description = "Para garantizar el monitoreo continuo 24/7, necesitamos algunos permisos importantes.",
            imageRes = R.drawable.onboarding_image
        )
    )

    val pagerState = rememberPagerState { pages.size }

    LaunchedEffect(showPermissionsSnackbar) {
        if (showPermissionsSnackbar) {
            snackbarHostState.showSnackbar(
                message = "Se requieren todos los permisos para continuar",
                actionLabel = "Configurar",
                duration = SnackbarDuration.Long
            ).let { result ->
                if (result == SnackbarResult.ActionPerformed) {
                    when (val state = permissionsState) {
                        is PermissionsState.NeedsPermissions -> {
                            handleAllPermissions(
                                permissions = state.permissions,
                                needsBattery = state.needsBatteryOptimization,
                                onRequestPermissions = { permissions ->
                                    permissionLauncher.launch(permissions.toTypedArray())
                                },
                                onRequestBatteryOptimization = {
                                    context.startActivity(permissionsViewModel.getBatteryOptimizationIntent())
                                }
                            )
                        }
                        else -> {}
                    }
                }
            }
            showPermissionsSnackbar = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                if (page < pages.size - 1) {
                    OnboardingPageContent(pages[page])
                } else {
                    PermissionsPageContent(
                        permissionsState = permissionsState,
                        onRequestPermissions = { permissions ->
                            permissionLauncher.launch(permissions.toTypedArray())
                        },
                        onRequestBatteryOptimization = {
                            context.startActivity(permissionsViewModel.getBatteryOptimizationIntent())
                        }
                    )
                }
            }

            Row(
                Modifier
                    .height(50.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pages.size) { iteration ->
                    val color = if (pagerState.currentPage == iteration)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(8.dp)
                    )
                }
            }

            HddButton(
                onClick = {
                    if (pagerState.currentPage < pages.lastIndex) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        when (val state = permissionsState) {
                            is PermissionsState.AllGranted -> {
                                onFinish()
                            }
                            is PermissionsState.NeedsPermissions -> {
                                handleAllPermissions(
                                    permissions = state.permissions,
                                    needsBattery = state.needsBatteryOptimization,
                                    onRequestPermissions = { permissions ->
                                        permissionLauncher.launch(permissions.toTypedArray())
                                    },
                                    onRequestBatteryOptimization = {
                                        context.startActivity(permissionsViewModel.getBatteryOptimizationIntent())
                                    }
                                )
                            }
                            else -> {}
                        }
                    }
                },
                text = when {
                    pagerState.currentPage == pages.lastIndex &&
                            permissionsState is PermissionsState.AllGranted -> "Empezar"
                    pagerState.currentPage == pages.lastIndex -> "Configurar Todo"
                    else -> "Siguiente"
                },
                modifier = Modifier
                    .padding(Dimensions.paddingMedium)
                    .fillMaxWidth()
            )
        }
    }
}

private fun handleAllPermissions(
    permissions: List<String>,
    needsBattery: Boolean,
    onRequestPermissions: (List<String>) -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    if (permissions.isNotEmpty()) {
        onRequestPermissions(permissions)
    } else if (needsBattery) {
        onRequestBatteryOptimization()
    }
}

@Composable
private fun PermissionsPageContent(
    permissionsState: PermissionsState,
    onRequestPermissions: (List<String>) -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimensions.paddingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.onboarding_image),
            contentDescription = null,
            modifier = Modifier.size(200.dp)
        )

        Spacer(modifier = Modifier.height(Dimensions.spacingLarge))

        Text(
            text = "Últimos Pasos",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(Dimensions.spacingMedium))

        when (permissionsState) {
            is PermissionsState.NeedsPermissions -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "🔧 Configuración Final",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "🔥 Para recibir alertas de incendio 24/7\n⚠️ Se requieren algunos permisos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            is PermissionsState.AllGranted -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "✅ ¡Configuración Completa!",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "El sistema está listo para alertas 24/7",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            PermissionsState.Loading -> {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimensions.paddingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = page.imageRes),
            contentDescription = null,
            modifier = Modifier.size(200.dp)
        )
        Spacer(modifier = Modifier.height(Dimensions.spacingLarge))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Dimensions.spacingMedium))
        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

data class OnboardingPage(
    val title: String,
    val description: String,
    val imageRes: Int
)