package com.pqsolutions.hdd_monitor.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Colores principales
val HddRed = Color(0xFFF43131)
val HddDarkRed = Color(0xFFB50000)
val HddGrey = Color(0xFF58595B)
val HddLightGrey = Color(0xFFF1F1F1)

// Colores secundarios
val HddWhite = Color(0xFFFFFFFF)
val HddBlack = Color(0xFF000000)
val HddGreen = Color(0xFF4CAF50)
val HddYellow = Color(0xFFFFC107)

// Colores adicionales para mayor variedad
val HddOrange = Color(0xFFFFA500)
val HddBlue = Color(0xFF2196F3)
val HddLightBlue = Color(0xFF03A9F4)
val HddDarkGrey = Color(0xFF333333)

// Extensiones útiles para colores
fun Color.isDark() = this.luminance() < 0.5f

fun Color.withAlpha(alpha: Float): Color = this.copy(alpha = alpha)

// Estados de colores semánticos
object StatusColors {
    val success = HddGreen
    val warning = HddYellow
    val error = HddRed
    val info = HddBlue

    fun getStatusColor(status: String): Color = when (status.uppercase()) {
        "PROGRAMADO" -> info
        "ACEPTADO" -> success
        "ERROR" -> error
        else -> HddGrey
    }
}

// Constantes de alpha
object Alpha {
    const val Disabled = 0.38f
    const val Medium = 0.74f
    const val Light = 0.12f
    const val Pressed = 0.92f
}