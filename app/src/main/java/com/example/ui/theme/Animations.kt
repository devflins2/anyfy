package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object FlinsAnimations {
    // Cinematic fluid easing
    val FluidEaseInOut: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    val OrganicEaseOut: Easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)
    val AnticipateOvershoot: Easing = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1.0f)

    // Standard durations
    const val DURATION_FAST = 200
    const val DURATION_MEDIUM = 360
    const val DURATION_SLOW = 600
    const val DURATION_AMBIENT = 2400

    fun <T> gentleSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    fun <T> fluidTween(durationMs: Int = DURATION_MEDIUM) = tween<T>(
        durationMillis = durationMs,
        easing = FluidEaseInOut
    )
}
