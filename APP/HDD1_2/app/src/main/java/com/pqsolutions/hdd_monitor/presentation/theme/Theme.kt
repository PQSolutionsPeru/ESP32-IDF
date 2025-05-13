package com.pqsolutions.hdd_monitor.presentation.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = HddRed,
    onPrimary = HddWhite,
    primaryContainer = HddDarkRed,
    onPrimaryContainer = HddWhite,
    secondary = HddGrey,
    onSecondary = HddWhite,
    secondaryContainer = HddLightGrey,
    onSecondaryContainer = HddBlack,
    tertiary = HddBlue,
    onTertiary = HddWhite,
    tertiaryContainer = HddLightBlue,
    onTertiaryContainer = HddWhite,
    error = HddRed,
    onError = HddWhite,
    errorContainer = HddDarkRed,
    onErrorContainer = HddWhite,
    background = HddWhite,
    onBackground = HddBlack,
    surface = HddWhite,
    onSurface = HddBlack
)

data class Spacing(
    val default: Dp = 16.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp
)

// Dimensiones específicas para elementos de la UI
object Dimensions {
    // Padding
    val paddingSmall: Dp = 8.dp
    val paddingMedium: Dp = 16.dp
    val paddingLarge: Dp = 24.dp

    // Elevation
    val elevationSmall: Dp = 2.dp
    val elevationMedium: Dp = 4.dp
    val elevationLarge: Dp = 8.dp

    // Component sizes
    val buttonHeight: Dp = 56.dp
    val iconSize: Dp = 24.dp
    val cardElevation: Dp = 4.dp
    val cardCornerRadius: Dp = 8.dp
}

private val LocalSpacing = staticCompositionLocalOf { Spacing() }

@Composable
fun HDD1_2Theme(
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme  // Siempre usar el esquema de color claro

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    CompositionLocalProvider(
        LocalSpacing provides Spacing()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

// Extensiones útiles
object ThemeUtils {
    val statusBarColor: Color
        @Composable
        get() = MaterialTheme.colorScheme.primary

    val navigationBarColor: Color
        @Composable
        get() = MaterialTheme.colorScheme.surface
}

val MaterialTheme.spacing: Spacing
    @Composable
    get() = LocalSpacing.current

@Composable
fun Color.disabled(): Color = copy(alpha = 0.38f)

@Composable
fun Color.medium(): Color = copy(alpha = 0.74f)

// Estados de colores para componentes
object ComponentState {
    val success = HddGreen
    val warning = HddYellow
    val info = HddBlue
    val disabled = HddLightGrey

    @Composable
    fun getStateColor(
        isSuccess: Boolean = false,
        isWarning: Boolean = false,
        isError: Boolean = false,
        isDisabled: Boolean = false
    ): Color = when {
        isSuccess -> success
        isWarning -> warning
        isError -> MaterialTheme.colorScheme.error
        isDisabled -> disabled
        else -> MaterialTheme.colorScheme.primary
    }
}

// Extensiones para colores por estado
@Composable
fun Color.success(): Color = ComponentState.success

@Composable
fun Color.warning(): Color = ComponentState.warning

@Composable
fun Color.info(): Color = ComponentState.info

// Custom shapes
object HddShapes {
    val small = Shapes().small
    val medium = Shapes().medium
    val large = Shapes().large
}

// Custom elevation
object HddElevation {
    val none: Dp = 0.dp
    val small: Dp = 2.dp
    val medium: Dp = 4.dp
    val large: Dp = 8.dp
    val extraLarge: Dp = 16.dp
}

// Constantes de animación
object AnimationConstants {
    const val DEFAULT_ANIMATION_DURATION = 300
    const val FAST_ANIMATION_DURATION = 150
    const val SLOW_ANIMATION_DURATION = 500
}