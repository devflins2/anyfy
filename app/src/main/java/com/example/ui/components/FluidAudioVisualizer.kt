package com.example.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsPink
import com.example.ui.theme.FlinsViolet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class VisualizerMode {
    WAVE_RIBBON,
    SPECTRUM_BARS,
    RADIAL_AURA
}

/**
 * High-fidelity Canvas-based audio visualizer that syncs directly with playback dynamics
 * (bassPulse, beatDeformation, energyLevel, and playback phase).
 */
@Composable
fun FluidAudioVisualizer(
    isPlaying: Boolean,
    bassPulse: Float,
    beatDeformation: Float,
    energyLevel: Float,
    modifier: Modifier = Modifier,
    primaryColorHex: String = "#00F0FF",
    bpm: Int = 120,
    initialMode: VisualizerMode = VisualizerMode.WAVE_RIBBON
) {
    var mode by remember { mutableStateOf<VisualizerMode>(initialMode) }

    val themeColor = Color(0xFFBDBDBD)

    // Hardware-accelerated infinite phase transition
    val infiniteTransition = rememberInfiniteTransition(label = "visualizerPhase")
    val durationMs = if (isPlaying) {
        (1500 / ((bpm / 120f) * (1.0f + energyLevel * 0.5f))).toInt().coerceIn(600, 3000)
    } else {
        5000
    }

    val animationPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable {
                mode = when (mode) {
                    VisualizerMode.WAVE_RIBBON -> VisualizerMode.SPECTRUM_BARS
                    VisualizerMode.SPECTRUM_BARS -> VisualizerMode.RADIAL_AURA
                    VisualizerMode.RADIAL_AURA -> VisualizerMode.WAVE_RIBBON
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (mode) {
                VisualizerMode.WAVE_RIBBON -> {
                    drawFluidWaveRibbons(
                        phase = animationPhase,
                        isPlaying = isPlaying,
                        bass = bassPulse,
                        deformation = beatDeformation,
                        energy = energyLevel,
                        color = themeColor
                    )
                }
                VisualizerMode.SPECTRUM_BARS -> {
                    drawSpectrumBars(
                        phase = animationPhase,
                        isPlaying = isPlaying,
                        bass = bassPulse,
                        deformation = beatDeformation,
                        energy = energyLevel,
                        color = themeColor
                    )
                }
                VisualizerMode.RADIAL_AURA -> {
                    drawRadialAura(
                        phase = animationPhase,
                        isPlaying = isPlaying,
                        bass = bassPulse,
                        deformation = beatDeformation,
                        energy = energyLevel,
                        color = themeColor
                    )
                }
            }
        }
    }
}

/**
 * Draws overlapping multi-harmonic fluid sine wave ribbons with glowing gradients
 */
private fun DrawScope.drawFluidWaveRibbons(
    phase: Float,
    isPlaying: Boolean,
    bass: Float,
    deformation: Float,
    energy: Float,
    color: Color
) {
    val width = size.width
    val height = size.height
    val centerY = height * 0.5f
    if (width <= 0f || height <= 0f) return

    val waveLayers = listOf(
        Triple(1.0f, 0.45f, 0f),
        Triple(1.8f, 0.35f, 1.2f),
        Triple(2.6f, 0.25f, 2.4f)
    )

    waveLayers.forEachIndexed { index, (freq, ampScale, phaseOffset) ->
        val path = Path()
        val fillPath = Path()
        val baseAmplitude = (height * 0.35f) * ampScale * (if (isPlaying) (0.4f + bass * 0.6f + deformation * 0.4f) else 0.15f)
        val step = 6f
        var x = 0f

        path.moveTo(0f, centerY)
        fillPath.moveTo(0f, height)
        fillPath.lineTo(0f, centerY)

        while (x <= width) {
            val progress = x / width
            // Windowing envelope to taper edges smoothly
            val envelope = sin(progress * PI.toFloat())
            val wave1 = sin((progress * freq * 2f * PI.toFloat()) + phase + phaseOffset)
            val wave2 = cos((progress * freq * 1.5f * PI.toFloat()) - phase * 0.7f)
            val y = centerY + (wave1 * 0.7f + wave2 * 0.3f) * baseAmplitude * envelope

            path.lineTo(x, y)
            fillPath.lineTo(x, y)
            x += step
        }

        fillPath.lineTo(width, height)
        fillPath.close()

        val layerColor = when (index) {
            0 -> Color(0xFFE0E0E0)
            1 -> Color(0xFFBDBDBD)
            else -> Color(0xFF808080)
        }

        // Draw translucent glow fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    layerColor.copy(alpha = 0.18f * (1f - index * 0.05f)),
                    layerColor.copy(alpha = 0.02f),
                    Color.Transparent
                ),
                startY = centerY - baseAmplitude,
                endY = height
            )
        )

        // Draw monochromatic crest line
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    layerColor.copy(alpha = 0.3f),
                    layerColor.copy(alpha = 0.95f),
                    Color(0xFFE0E0E0).copy(alpha = 0.85f),
                    layerColor.copy(alpha = 0.3f)
                )
            ),
            style = Stroke(
                width = if (index == 0) 2.5f else 1.5f,
                cap = StrokeCap.Round
            )
        )
    }
}

/**
 * Draws animated spectrum frequency bars with peak dynamics
 */
private fun DrawScope.drawSpectrumBars(
    phase: Float,
    isPlaying: Boolean,
    bass: Float,
    deformation: Float,
    energy: Float,
    color: Color
) {
    val width = size.width
    val height = size.height
    val centerY = height * 0.5f
    if (width <= 0f || height <= 0f) return

    val barCount = 28
    val totalSpacing = width * 0.25f
    val barWidth = (width - totalSpacing) / barCount
    val spacing = totalSpacing / (barCount - 1)

    for (i in 0 until barCount) {
        val normalizedIndex = i.toFloat() / (barCount - 1)
        val envelope = sin(normalizedIndex * PI.toFloat())
        val waveFactor = sin(normalizedIndex * 6f + phase * 2f) * 0.4f +
                cos(normalizedIndex * 12f - phase * 1.5f) * 0.3f +
                sin(normalizedIndex * 3f + phase) * 0.3f
        val rawHeight = if (isPlaying) {
            val bassBoost = if (normalizedIndex < 0.35f) bass * 1.3f else 0.4f
            val midBoost = if (normalizedIndex in 0.35f..0.75f) deformation * 1.2f else 0.4f
            (height * 0.75f) * envelope * (0.25f + abs(waveFactor) * 0.5f * energy + bassBoost * 0.4f + midBoost * 0.3f)
        } else {
            (height * 0.15f) * (0.4f + 0.3f * sin(phase + i * 0.2f))
        }

        val barH = rawHeight.coerceIn(4f, height * 0.85f)
        val startX = i * (barWidth + spacing)
        val topY = centerY - barH * 0.5f

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFE0E0E0).copy(alpha = 0.95f),
                    Color(0xFFBDBDBD).copy(alpha = 0.85f),
                    Color(0xFF606060).copy(alpha = 0.70f)
                ),
                startY = topY,
                endY = topY + barH
            ),
            topLeft = Offset(startX, topY),
            size = Size(barWidth, barH),
            cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)
        )
    }
}

/**
 * Draws concentric pulsating harmonic frequency aura rings
 */
private fun DrawScope.drawRadialAura(
    phase: Float,
    isPlaying: Boolean,
    bass: Float,
    deformation: Float,
    energy: Float,
    color: Color
) {
    val width = size.width
    val height = size.height
    val center = Offset(width * 0.5f, height * 0.5f)
    val maxRadius = minOf(width, height) * 0.45f
    if (maxRadius <= 0f) return

    val ringCount = 5
    for (i in 0 until ringCount) {
        val ringPhase = (phase + i * (2f * PI.toFloat() / ringCount)) % (2f * PI.toFloat())
        val progress = ringPhase / (2f * PI.toFloat())
        val baseRadius = maxRadius * progress
        val pulseMultiplier = if (isPlaying) (1f + bass * 0.35f + deformation * 0.2f) else 1f
        val currentRadius = (baseRadius * pulseMultiplier).coerceAtLeast(4f)
        val alpha = (1f - progress) * (if (isPlaying) 0.65f else 0.25f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF808080).copy(alpha = alpha * 0.2f),
                    Color(0xFFBDBDBD).copy(alpha = alpha * 0.5f),
                    Color.Transparent
                ),
                center = center,
                radius = currentRadius
            ),
            radius = currentRadius,
            center = center,
            style = Stroke(
                width = 2.5f * (1f - progress * 0.5f),
                cap = StrokeCap.Round
            )
        )
    }
}

/**
 * Compact, lightweight audio equalizer bars for MiniPlayer and SongRow
 */
@Composable
fun MiniAudioEqualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    color: Color = Color(0xFFBDBDBD)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizerAnim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI.toFloat()),
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 800 else 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val barW = width / (barCount * 2f - 1f)
        for (i in 0 until barCount) {
            val factor = if (isPlaying) {
                0.25f + 0.75f * abs(sin((phase + i * 1.3f).toDouble()).toFloat())
            } else {
                0.25f + 0.1f * sin((phase + i * 0.8f).toDouble()).toFloat()
            }
            val barH = (height * factor).coerceIn(3f, height)
            val x = (i * (barW * 2f))
            val y = height - barH

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f)
            )
        }
    }
}
