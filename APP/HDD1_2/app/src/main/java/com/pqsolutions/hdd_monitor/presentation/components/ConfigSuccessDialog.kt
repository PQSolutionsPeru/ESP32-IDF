package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pqsolutions.hdd_monitor.R
import com.pqsolutions.hdd_monitor.esp32.ESP32Device

@Composable
fun ConfigSuccessDialog(
    esp32Device: ESP32Device,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss
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
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Icono de éxito
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Título
                Text(
                    text = "¡Configuración Completada!",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Detalles del dispositivo
                Text(
                    text = "ESP32 #${esp32Device.documentName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Estado del dispositivo
                Text(
                    text = "Estado: ${getESP32StatusLabel(esp32Device.status)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Dirección IP (si está disponible)
                if (esp32Device.IP.isNotBlank()) {
                    Text(
                        text = "IP: ${esp32Device.IP}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Mensaje de confirmación
                Text(
                    text = "El dispositivo ha sido configurado exitosamente y está listo para usarse.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Botón de continuar
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continuar")
                }
            }
        }
    }
}

/**
 * Convierte el estado técnico del ESP32 a un texto más amigable para el usuario
 */
private fun getESP32StatusLabel(status: String): String {
    return when (status) {
        ESP32Device.STATUS_ONLINE -> "En línea"
        ESP32Device.STATUS_RUNNING -> "Operativo"
        ESP32Device.STATUS_CONFIGURED -> "Configurado"
        ESP32Device.STATUS_OFFLINE -> "Fuera de línea"
        ESP32Device.STATUS_AWAITING_CONFIG -> "Esperando configuración"
        ESP32Device.STATUS_WIFI_CONFIG -> "Configurando WiFi"
        ESP32Device.STATUS_PENDING_ASSIGNMENT -> "Pendiente de asignación"
        ESP32Device.STATUS_DISC -> "Desconectado"
        ESP32Device.STATUS_ERROR -> "Error"
        else -> status
    }
}