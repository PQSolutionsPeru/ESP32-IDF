package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnimatedNotificationBell(
    hasNewNotifications: Boolean,
    notificationCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // CORRECCIÓN: Solo animar si hay notificaciones sin leer (conteo > 0)
    val shouldAnimate = hasNewNotifications && notificationCount > 0

    val infiniteTransition = rememberInfiniteTransition(label = "bell_transition")

    // Animación de rotación de la campana
    val angle by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bell_rotation"
    )

    // Animación de escala
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bell_scale"
    )

    // Animación del color del badge
    val badgeAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "badge_alpha"
    )

    // Animación del brillo
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    Box(
        modifier = modifier.then(Modifier.size(48.dp)),
        contentAlignment = Alignment.Center
    ) {
        // Efecto de brillo detrás de la campana
        if (shouldAnimate) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer(alpha = 0.99f)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = shimmerAlpha),
                        shape = CircleShape
                    )
            )
        }

        // Campana
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "Notificaciones",
                modifier = Modifier
                    .size(28.dp)
                    .rotate(if (shouldAnimate) angle else 0f)
                    .scale(if (shouldAnimate) scale else 1f),
                tint = if (shouldAnimate) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }

        // Badge con contador
        if (notificationCount > 0) {
            val badgeSize = 20.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(badgeSize)
                    .background(
                        color = if (shouldAnimate)
                            MaterialTheme.colorScheme.error.copy(alpha = badgeAlpha)
                        else
                            MaterialTheme.colorScheme.error,
                        shape = CircleShape
                    )
                    .padding(1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (notificationCount > 99) "99+" else notificationCount.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(2.dp)
                )
            }
        }
    }
}