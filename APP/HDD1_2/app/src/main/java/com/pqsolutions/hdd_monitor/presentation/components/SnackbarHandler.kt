package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pqsolutions.hdd_monitor.presentation.util.performHapticFeedback
import com.pqsolutions.hdd_monitor.presentation.util.playSoundEffect
import kotlinx.coroutines.flow.Flow
import com.pqsolutions.hdd_monitor.R
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close


@Composable
fun SnackbarHandler(
    snackbarHostState: SnackbarHostState,
    messageFlow: Flow<String>,
    actionLabel: String? = null,
    withDismissAction: Boolean = true,
    onActionClick: (() -> Unit)? = null,
    duration: SnackbarDuration = SnackbarDuration.Short
) {
    val context = LocalContext.current

    LaunchedEffect(messageFlow) {
        messageFlow.collect { message ->
            performHapticFeedback(context)

            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = withDismissAction,
                duration = duration
            )

            when (result) {
                SnackbarResult.ActionPerformed -> {
                    onActionClick?.invoke()
                }
                SnackbarResult.Dismissed -> {
                    // Opcional: manejar el dismiss si es necesario
                }
            }
        }
    }
}

@Composable
fun rememberSnackbarState(): SnackbarHostState {
    return remember { SnackbarHostState() }
}

@Composable
fun AppSnackbarHost(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
        snackbar = { snackbarData ->
            Snackbar(
                snackbarData = snackbarData,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                actionColor = MaterialTheme.colorScheme.primary,
                dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    )
}

@Composable
fun ErrorSnackbar(
    snackbarHostState: SnackbarHostState,
    errorFlow: Flow<String>,
    onRetry: (() -> Unit)? = null
) {
    SnackbarHandler(
        snackbarHostState = snackbarHostState,
        messageFlow = errorFlow,
        actionLabel = if (onRetry != null) "Reintentar" else null,
        onActionClick = onRetry,
        duration = SnackbarDuration.Long
    )
}

@Composable
fun SuccessSnackbar(
    snackbarHostState: SnackbarHostState,
    messageFlow: Flow<String>
) {
    SnackbarHandler(
        snackbarHostState = snackbarHostState,
        messageFlow = messageFlow,
        duration = SnackbarDuration.Short
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopSnackbar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Snackbar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        dismissAction = {
            IconButton(
                onClick = {
                    performHapticFeedback(context)
                    onDismiss()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    tint = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    ) {
        Text(message)
    }
}

// Extension function para facilitar el uso
suspend fun SnackbarHostState.showSuccessMessage(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = true
) {
    showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = SnackbarDuration.Short
    )
}

suspend fun SnackbarHostState.showErrorMessage(
    message: String,
    actionLabel: String? = "Reintentar",
    withDismissAction: Boolean = true
) {
    showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = SnackbarDuration.Long
    )
}