package com.example.ui.screens.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.FlinsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Data model for lightweight ambient neon particles orbiting the logo.
 */
private data class AnyfyParticle(
    val baseAngle: Float,
    val orbitRadiusDp: Float,
    val speed: Float,
    val sizeDp: Float,
    val color: Color,
    val baseAlpha: Float
)

@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    val exitAlpha = remember { Animatable(1f) }
    val logoScale = remember { Animatable(0.70f) }
    val logoAlpha = remember { Animatable(0f) }
    val glowAlpha = remember { Animatable(0f) }
    val glowScale = remember { Animatable(0.75f) }
    val sweepProgress = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val textOffsetY = remember { Animatable(24f) }
    val lineProgress = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val subtitleOffsetY = remember { Animatable(12f) }

    // Continuous ambient micro-animations for organic fluidity
    val infiniteTransition = rememberInfiniteTransition(label = "ambientParticles")

    val particlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleOrbit"
    )

    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    // Carefully calibrated lightweight particles
    val particles = remember {
        listOf(
            AnyfyParticle(baseAngle = 0.4f, orbitRadiusDp = 64f, speed = 0.75f, sizeDp = 3.5f, color = Color(0xFFF0F0F0), baseAlpha = 0.75f),
            AnyfyParticle(baseAngle = 1.7f, orbitRadiusDp = 76f, speed = -0.60f, sizeDp = 2.5f, color = Color(0xFFBDBDBD), baseAlpha = 0.60f),
            AnyfyParticle(baseAngle = 2.9f, orbitRadiusDp = 68f, speed = 0.85f, sizeDp = 4.0f, color = Color(0xFFA0A0A0), baseAlpha = 0.70f),
            AnyfyParticle(baseAngle = 4.0f, orbitRadiusDp = 82f, speed = -0.50f, sizeDp = 2.0f, color = Color(0xFF808080), baseAlpha = 0.50f),
            AnyfyParticle(baseAngle = 4.8f, orbitRadiusDp = 62f, speed = 0.70f, sizeDp = 3.0f, color = Color(0xFFBDBDBD), baseAlpha = 0.65f),
            AnyfyParticle(baseAngle = 5.7f, orbitRadiusDp = 78f, speed = -0.80f, sizeDp = 2.5f, color = Color(0xFF707070), baseAlpha = 0.55f)
        )
    }

    LaunchedEffect(Unit) {
        // 1. Dark opening: start with a pure black canvas
        delay(100)

        // 2. Logo entrance: scale smoothly from 70% to 100%, fade-in, and expand neon glow
        launch {
            glowAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
            )
        }
        launch {
            glowScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 750, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
            )
        }
        launch {
            logoAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing)
            )
        }
        launch {
            logoScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 750, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
            )
        }

        // 3. Neon animation: gentle light sweep glides across the logo surface & edges
        delay(450)
        launch {
            sweepProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 850, easing = CubicBezierEasing(0.2f, 0.8f, 0.25f, 1f))
            )
        }

        // 4. Brand reveal: "Anyfy" text fades in below with smooth upward motion
        delay(300)
        launch {
            textAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
            )
        }
        launch {
            textOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 550, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
            )
        }

        // Accent line & subtitle reveal
        delay(180)
        launch {
            lineProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
            )
        }
        launch {
            subtitleAlpha.animateTo(
                targetValue = 0.85f,
                animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
            )
        }
        launch {
            subtitleOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
            )
        }

        // 5. Final moment: Hold the complete logo and Anyfy wordmark in full glory
        delay(550)

        // Smooth exit transition into main screen
        exitAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
        )

        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010)) // Pure dark grey background
            .alpha(exitAlpha.value)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onSplashFinished() }, // Tap anywhere to skip instantly
        contentAlignment = Alignment.Center
    ) {
        // Atmospheric radial ambient glow
        Box(
            modifier = Modifier
                .size(340.dp)
                .scale(glowScale.value * glowPulse)
                .alpha(glowAlpha.value * 0.85f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF282828).copy(alpha = 0.5f),
                            Color(0xFF202020).copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo emblem with ambient glow, particle orbit, and neon light sweep
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
                contentAlignment = Alignment.Center
            ) {
                // Secondary tighter aura directly behind squircle
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .scale(glowPulse)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF454545).copy(alpha = 0.35f * glowAlpha.value),
                                    Color(0xFF282828).copy(alpha = 0.20f * glowAlpha.value),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Subtle orbiting neon particles & light trails
                Canvas(modifier = Modifier.size(180.dp)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val densityVal = density

                    for (p in particles) {
                        val angle = p.baseAngle + particlePhase * p.speed
                        val rad = (p.orbitRadiusDp + sin(particlePhase * 2f + p.baseAngle) * 4f) * densityVal
                        val px = cx + cos(angle) * rad
                        val py = cy + sin(angle) * rad
                        val pAlpha = (p.baseAlpha * (0.6f + 0.4f * sin(particlePhase * 3f + p.baseAngle)) * logoAlpha.value).coerceIn(0f, 1f)
                        val particleSizePx = p.sizeDp * densityVal

                        // Outer soft particle halo
                        drawCircle(
                            color = p.color.copy(alpha = pAlpha * 0.35f),
                            radius = particleSizePx * 2.2f,
                            center = Offset(px, py)
                        )
                        // Inner particle core
                        drawCircle(
                            color = Color.White.copy(alpha = pAlpha * 0.9f),
                            radius = particleSizePx * 0.7f,
                            center = Offset(px, py)
                        )
                        drawCircle(
                            color = p.color.copy(alpha = pAlpha),
                            radius = particleSizePx,
                            center = Offset(px, py)
                        )
                    }
                }

                // Official Logo Badge + Neon Light Sweep
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Official Logo Asset (100% untouched)
                    Image(
                        painter = painterResource(id = R.drawable.anyfy_logo),
                        contentDescription = "Anyfy Logo",
                        modifier = Modifier.fillMaxSize()
                    )

                    // Neon Edge & Specular Light Sweep Overlay
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        // Gentle glowing neon rim border
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFE0E0E0).copy(alpha = 0.70f),
                                    Color(0xFFBDBDBD).copy(alpha = 0.45f),
                                    Color(0xFF808080).copy(alpha = 0.75f),
                                    Color(0xFF606060).copy(alpha = 0.45f)
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(w, h)
                            ),
                            cornerRadius = CornerRadius(24.dp.toPx()),
                            style = Stroke(width = 1.6.dp.toPx())
                        )

                        // Gentle flowing specular light sweep across the logo face
                        if (sweepProgress.value > 0f && sweepProgress.value < 1f) {
                            val travel = (w + h) * 1.5f
                            val progressOffset = -w * 0.5f + travel * sweepProgress.value

                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(0x10FFFFFF),
                                        Color(0x30FFFFFF),
                                        Color(0x80FFFFFF),
                                        Color(0x30FFFFFF),
                                        Color(0x10FFFFFF),
                                        Color.Transparent
                                    ),
                                    start = Offset(progressOffset - 40f, progressOffset - 40f),
                                    end = Offset(progressOffset + 40f, progressOffset + 40f)
                                ),
                                cornerRadius = CornerRadius(24.dp.toPx())
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Brand reveal: "Anyfy" wordmark with upward motion & vibrant neon cyan-to-purple gradient
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, textOffsetY.value.dp.roundToPx()) }
                    .alpha(textAlpha.value),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Anyfy",
                    style = FlinsTheme.typography.display.copy(
                        fontSize = 44.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFF0F0F0),
                                Color(0xFFE0E0E0),
                                Color(0xFFBDBDBD),
                                Color(0xFFA0A0A0)
                            )
                        )
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Subtle neon gradient divider line expanding under the wordmark
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width((48 * lineProgress.value).dp)
                    .alpha(lineProgress.value * 0.75f)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFBDBDBD),
                                Color(0xFF808080),
                                Color.Transparent
                            )
                        )
                    )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Subtitle reveal
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, subtitleOffsetY.value.dp.roundToPx()) }
                    .alpha(subtitleAlpha.value)
            ) {
                Text(
                    text = "LIVING AUDIO ENVIRONMENT",
                    style = FlinsTheme.typography.overline.copy(
                        fontSize = 11.sp,
                        letterSpacing = 4.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color(0xFFA0A0A0)
                )
            }
        }
    }
}
