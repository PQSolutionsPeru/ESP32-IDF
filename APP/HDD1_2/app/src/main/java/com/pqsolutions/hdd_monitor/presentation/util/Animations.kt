package com.pqsolutions.hdd_monitor.presentation.util

import androidx.compose.animation.*
import androidx.compose.animation.core.tween

@ExperimentalAnimationApi
fun enterTransition(): EnterTransition {
    return fadeIn(animationSpec = tween(300)) + slideInHorizontally(
        initialOffsetX = { 300 },
        animationSpec = tween(300)
    )
}

@ExperimentalAnimationApi
fun exitTransition(): ExitTransition {
    return fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
        targetOffsetX = { -300 },
        animationSpec = tween(300)
    )
}