package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pqsolutions.hdd_monitor.presentation.state.BleState

@Composable
fun ConfigurationProgressSection(
    currentState: BleState,
    onContinueClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Proceso de Configuración",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Pasos de configuración con estado
            ConfigurationStep(
                title = "Conexión Bluetooth",
                description = "Estableciendo conexión con el dispositivo",
                state = getStepState(currentState, 1),
                isActive = isStepActive(currentState, 1)
            )

            ConfigurationStep(
                title = "Configuración WiFi",
                description = "Enviando credenciales de red",
                state = getStepState(currentState, 2),
                isActive = isStepActive(currentState, 2)
            )

            ConfigurationStep(
                title = "Configuración del Panel",
                description = "Asignando panel al dispositivo",
                state = getStepState(currentState, 3),
                isActive = isStepActive(currentState, 3)
            )

            ConfigurationStep(
                title = "Verificación",
                description = "Confirmando funcionamiento del dispositivo",
                state = getStepState(currentState, 4),
                isActive = isStepActive(currentState, 4)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Barra de progreso global
            val progress = calculateOverallProgress(currentState)
            LinearProgressIndicator(
                progress = progress,  // Cambiar esto
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Mensaje de estado actual
            val statusMessage = getStatusMessage(currentState)
            AnimatedVisibility(
                visible = statusMessage.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = if (isErrorState(currentState))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ConfigurationStep(
    title: String,
    description: String,
    state: StepState,
    isActive: Boolean
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = when (state) {
                        StepState.COMPLETED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        StepState.IN_PROGRESS -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                        StepState.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                        StepState.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (state) {
                    StepState.COMPLETED -> Icons.Default.Check
                    StepState.IN_PROGRESS -> Icons.Default.Pending
                    StepState.PENDING -> Icons.Default.Pending
                    StepState.ERROR -> Icons.Default.DoNotDisturb
                },
                contentDescription = null,
                tint = when (state) {
                    StepState.COMPLETED -> MaterialTheme.colorScheme.onPrimary
                    StepState.IN_PROGRESS -> MaterialTheme.colorScheme.onPrimary
                    StepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    StepState.ERROR -> MaterialTheme.colorScheme.onError
                },
                modifier = Modifier.size(24.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class StepState {
    PENDING, IN_PROGRESS, COMPLETED, ERROR
}

private fun getStepState(currentState: BleState, step: Int): StepState {
    return when (step) {
        1 -> when (currentState) {
            is BleState.Initial, is BleState.Scanning, is BleState.RequiresPermission -> StepState.PENDING
            is BleState.Connecting -> StepState.IN_PROGRESS
            is BleState.Error, is BleState.ConfigurationError -> {
                if (isBluetoothError(currentState)) StepState.ERROR else StepState.COMPLETED
            }
            else -> StepState.COMPLETED
        }
        2 -> when (currentState) {
            is BleState.Initial, is BleState.Scanning, is BleState.Connecting,
            is BleState.RequiresPermission -> StepState.PENDING
            is BleState.Connected, is BleState.WifiConfigReceived,
            is BleState.WifiConfiguring -> StepState.IN_PROGRESS
            is BleState.Error, is BleState.ConfigurationError -> {
                if (isWifiConfigError(currentState)) StepState.ERROR else StepState.PENDING
            }
            else -> StepState.COMPLETED
        }
        3 -> when (currentState) {
            is BleState.Initial, is BleState.Scanning, is BleState.Connecting,
            is BleState.Connected, is BleState.WifiConfigReceived,
            is BleState.WifiConfiguring, is BleState.RequiresPermission -> StepState.PENDING
            is BleState.WifiConfigured, is BleState.SelectingClient,
            is BleState.CreatingPanel -> StepState.IN_PROGRESS
            is BleState.Error, is BleState.ConfigurationError -> {
                if (isPanelConfigError(currentState)) StepState.ERROR else StepState.PENDING
            }
            else -> StepState.COMPLETED
        }
        4 -> when (currentState) {
            is BleState.ConfigurationSuccess -> StepState.COMPLETED
            is BleState.WaitingForRunningMode -> StepState.IN_PROGRESS
            is BleState.Error, is BleState.ConfigurationError -> {
                if (isVerificationError(currentState)) StepState.ERROR else StepState.PENDING
            }
            else -> StepState.PENDING
        }
        else -> StepState.PENDING
    }
}

private fun isStepActive(currentState: BleState, step: Int): Boolean {
    return getStepState(currentState, step) == StepState.IN_PROGRESS
}

private fun calculateOverallProgress(currentState: BleState): Float {
    return when (currentState) {
        is BleState.Initial, is BleState.ConfigMethodSelection -> 0.0f
        is BleState.RequiresPermission, is BleState.Scanning -> 0.05f
        is BleState.Connecting -> 0.1f
        is BleState.Connected -> 0.25f
        is BleState.WifiConfigReceived -> 0.3f
        is BleState.WifiConfiguring -> 0.4f
        is BleState.WifiConfigured -> 0.5f
        is BleState.SelectingClient -> 0.6f
        is BleState.CreatingPanel -> 0.7f
        is BleState.WaitingForRunningMode -> 0.85f
        is BleState.ConfigurationSuccess -> 1.0f
        is BleState.Error, is BleState.ConfigurationError -> {
            val errorMessage = if (currentState is BleState.Error)
                currentState.message else (currentState as BleState.ConfigurationError).message
            when {
                isBluetoothError(currentState) -> 0.1f
                isWifiConfigError(currentState) -> 0.4f
                isPanelConfigError(currentState) -> 0.7f
                isVerificationError(currentState) -> 0.85f
                else -> 0.0f
            }
        }
        else -> 0.0f
    }
}

private fun getStatusMessage(currentState: BleState): String {
    return when (currentState) {
        is BleState.Initial -> "Preparando configuración..."
        is BleState.ConfigMethodSelection -> "Seleccione un método de configuración"
        is BleState.Scanning -> "Buscando dispositivos ESP32 cercanos..."
        is BleState.Connecting -> "Estableciendo conexión con el dispositivo..."
        is BleState.Connected -> "Dispositivo conectado. Configure la red WiFi."
        is BleState.WifiConfigReceived -> "Procesando configuración WiFi..."
        is BleState.WifiConfiguring -> "Configurando WiFi en el ESP32..."
        is BleState.WifiConfigured -> "WiFi configurado. Creando panel..."
        is BleState.SelectingClient -> "Seleccione el cliente para el panel"
        is BleState.CreatingPanel -> "Creando y asociando el panel..."
        is BleState.WaitingForRunningMode -> "Verificando funcionamiento del dispositivo..."
        is BleState.ConfigurationSuccess -> "¡Configuración completada exitosamente!"
        is BleState.Error -> currentState.message
        is BleState.ConfigurationError -> currentState.message
        is BleState.LoadingUnassignedDevices -> "Buscando dispositivos disponibles..."
        is BleState.NoUnassignedDevices -> "No hay dispositivos disponibles para reasignar"
        is BleState.UnassignedDevicesFound -> "Dispositivos encontrados. Seleccione uno para configurar."
        else -> ""
    }
}

private fun isErrorState(state: BleState): Boolean {
    return state is BleState.Error || state is BleState.ConfigurationError
}

private fun isBluetoothError(state: BleState): Boolean {
    val errorMessage = when (state) {
        is BleState.Error -> state.message.lowercase()
        is BleState.ConfigurationError -> state.message.lowercase()
        else -> ""
    }

    return errorMessage.contains("bluetooth") ||
            errorMessage.contains("conexión") ||
            errorMessage.contains("conectar") ||
            errorMessage.contains("permisos")
}

private fun isWifiConfigError(state: BleState): Boolean {
    val errorMessage = when (state) {
        is BleState.Error -> state.message.lowercase()
        is BleState.ConfigurationError -> state.message.lowercase()
        else -> ""
    }

    return errorMessage.contains("wifi") ||
            errorMessage.contains("ssid") ||
            errorMessage.contains("contraseña") ||
            errorMessage.contains("credenciales")
}

private fun isPanelConfigError(state: BleState): Boolean {
    val errorMessage = when (state) {
        is BleState.Error -> state.message.lowercase()
        is BleState.ConfigurationError -> state.message.lowercase()
        else -> ""
    }

    return errorMessage.contains("panel") ||
            errorMessage.contains("cliente") ||
            errorMessage.contains("asignado") ||
            errorMessage.contains("asignaci")
}

private fun isVerificationError(state: BleState): Boolean {
    val errorMessage = when (state) {
        is BleState.Error -> state.message.lowercase()
        is BleState.ConfigurationError -> state.message.lowercase()
        else -> ""
    }

    return errorMessage.contains("verifica") ||
            errorMessage.contains("confirma") ||
            errorMessage.contains("timeout") ||
            errorMessage.contains("espera")
}