package com.example.ui.screens.login

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.YouTubeLoginSheet
import com.example.ui.glass.LiquidGlassBackground
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme

@Composable
fun LoginScreen(
    isSigningIn: Boolean,
    errorMessage: String? = null,
    onSignInWithCookie: (cookie: String, onFinished: (Boolean, String) -> Unit) -> Unit,
    onGuestClick: () -> Unit = {},
    onErrorDismiss: () -> Unit = {}
) {
    val contentAlpha = remember { Animatable(0f) }
    val contentScale = remember { Animatable(0.92f) }
    var showYouTubeLoginSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        contentAlpha.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
        contentScale.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
    }

    if (showYouTubeLoginSheet) {
        YouTubeLoginSheet(
            onDismiss = { showYouTubeLoginSheet = false },
            onCookieCaptured = { cookie, onFinished ->
                onSignInWithCookie(cookie) { success, name ->
                    onFinished(success, name)
                    if (success) {
                        showYouTubeLoginSheet = false
                    }
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FlinsDarkBg),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassBackground(
            primaryColorHex = "#7C3AED",
            isPlaying = true,
            bassPulse = 0.25f,
            beatDeformation = 0.15f,
            energyLevel = 0.6f
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .alpha(contentAlpha.value)
                .scale(contentScale.value),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App emblem
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF454545).copy(alpha = 0.40f),
                                    Color(0xFF282828).copy(alpha = 0.20f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Image(
                    painter = painterResource(id = com.example.R.drawable.anyfy_logo),
                    contentDescription = "Anyfy Logo",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // App name
            Text(
                text = "Anyfy",
                style = FlinsTheme.typography.display.copy(
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = FlinsTheme.colors.textPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "LIVING AUDIO ENVIRONMENT",
                style = FlinsTheme.typography.overline,
                color = Color(0xFFA0A0A0)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Sign-in prompt
            Text(
                text = "Sign in with YouTube Music to access your library",
                style = FlinsTheme.typography.body.copy(fontSize = 15.sp),
                color = FlinsTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // YouTube Music Sign-In button
            Surface(
                onClick = { if (!isSigningIn) showYouTubeLoginSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF454545),
                                    Color(0xFF282828)
                                )
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .then(
                            Modifier.background(
                                Color(0xFF202020),
                                shape = RoundedCornerShape(16.dp)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            color = Color(0xFFF0F0F0),
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "▶",
                                color = Color(0xFFF0F0F0),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Sign In with YouTube Music",
                                style = FlinsTheme.typography.body.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                ),
                                color = FlinsTheme.colors.textPrimary
                            )
                        }
                    }
                }
            }

            // Error message
            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = FlinsTheme.typography.caption.copy(fontSize = 13.sp),
                    color = Color(0xFFD0D0D0),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "By continuing, you agree to Anyfy's\nTerms of Service and Privacy Policy",
                style = FlinsTheme.typography.caption.copy(fontSize = 11.sp),
                color = FlinsTheme.colors.textSecondary.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}
