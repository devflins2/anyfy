package com.example.ui.glass

import android.graphics.Color as AndroidColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.model.VisualQuality
import com.example.ui.theme.FlinsDarkBg
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LiquidGlassBackground(
    modifier: Modifier = Modifier,
    primaryColorHex: String = "#00F0FF",
    isPlaying: Boolean = false,
    bassPulse: Float = 0.1f,
    beatDeformation: Float = 0.05f,
    energyLevel: Float = 0.5f,
    quality: VisualQuality = VisualQuality.BALANCED
) {
    // Monochromatic Grey liquid auras with subtle dynamic depth
    val animatedPrimary by animateColorAsState(
        targetValue = Color(0xFF282828),
        animationSpec = tween(durationMillis = 800),
        label = "animatedGlassPrimary"
    )

    val animatedSecondary by animateColorAsState(
        targetValue = Color(0xFF202020),
        animationSpec = tween(durationMillis = 800),
        label = "animatedGlassSecondary"
    )

    // Pause decorative infinite rendering loop when paused or in battery saver mode to preserve battery and CPU/GPU
    val shouldAnimate = isPlaying && quality != VisualQuality.BATTERY_SAVER
    val cycleDuration = (8000 / (0.8f + energyLevel * 0.6f)).toInt().coerceIn(3000, 10000)

    val progress = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "glassAnimation")
        val animatedProgress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(cycleDuration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "liquidMotionProgress"
        )
        animatedProgress
    } else {
        0f
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val minDim = minOf(width, height)

        // 1. Deep atmospheric background
        drawRect(color = FlinsDarkBg)

        val time = progress * 2f * PI.toFloat()
        val expansion = 1.0f + bassPulse * 0.15f

        // Orb 1: Primary glowing liquid aura (Top Left drift)
        val cx1 = width * 0.28f + sin(time) * (width * 0.12f)
        val cy1 = height * 0.25f + cos(time * 0.8f) * (height * 0.08f)
        val r1 = (minDim * 0.55f * expansion).coerceAtLeast(10f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedPrimary.copy(alpha = 0.32f),
                    animatedPrimary.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = Offset(cx1, cy1),
                radius = r1
            ),
            radius = r1,
            center = Offset(cx1, cy1)
        )

        // Orb 2: Secondary fluid aura (Center Right drift)
        val cx2 = width * 0.75f - cos(time * 0.9f) * (width * 0.10f)
        val cy2 = height * 0.55f + sin(time * 1.1f) * (height * 0.10f)
        val r2 = (minDim * 0.60f * (1.0f + beatDeformation * 0.15f)).coerceAtLeast(10f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedSecondary.copy(alpha = 0.28f),
                    animatedSecondary.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(cx2, cy2),
                radius = r2
            ),
            radius = r2,
            center = Offset(cx2, cy2)
        )

        // Orb 3: Bottom ambient glow
        if (quality != VisualQuality.BATTERY_SAVER) {
            val cx3 = width * 0.45f + sin(time * 0.7f + 1f) * (width * 0.08f)
            val cy3 = height * 0.82f - cos(time * 0.6f) * (height * 0.06f)
            val r3 = (minDim * 0.48f * expansion).coerceAtLeast(10f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedPrimary.copy(alpha = 0.20f),
                        animatedSecondary.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(cx3, cy3),
                    radius = r3
                ),
                radius = r3,
                center = Offset(cx3, cy3)
            )
        }

        // 3. Subtle dark vignette & glass atmospheric sheen
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    FlinsDarkBg.copy(alpha = 0.50f),
                    FlinsDarkBg.copy(alpha = 0.85f)
                ),
                center = Offset(width * 0.5f, height * 0.45f),
                radius = maxOf(width, height) * 0.85f
            )
        )
    }
}
