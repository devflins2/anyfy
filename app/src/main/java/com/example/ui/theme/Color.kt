package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Monochromatic Grey Theme Palette
val FlinsDarkBg = Color(0xFF101010)              // Main Background #101010
val FlinsDarkElevated = Color(0xFF181818)        // Secondary Background #181818
val FlinsCardBackground = Color(0xFF202020)      // Card Background #202020
val FlinsSurfaceElevated = Color(0xFF282828)     // Elevated Surface #282828
val FlinsGlassSurface = Color(0x33202020)        // Translucent card surface
val FlinsGlassSurfaceHover = Color(0xFF2A2A2A)   // Subtle surface hover
val FlinsGlassBorder = Color(0xFF383838)         // Borders #383838
val FlinsGlassBorderBright = Color(0xFF484848)   // Active borders
val FlinsGlassInnerHighlight = Color(0x18FFFFFF) // Subtle inner highlight
val FlinsDivider = Color(0xFF303030)             // Dividers #303030
val FlinsSelected = Color(0xFF454545)            // Selected Elements #454545
val FlinsProgressTrack = Color(0xFF383838)       // Progress Track #383838
val FlinsProgressIndicator = Color(0xFFBDBDBD)   // Progress Indicator #BDBDBD
val FlinsIcon = Color(0xFFC0C0C0)                // Icons #C0C0C0

// Monochromatic Accent Tokens (previously colored, now unified grey system)
val FlinsCyan = Color(0xFFBDBDBD)                // Primary Grey Accent #BDBDBD
val FlinsViolet = Color(0xFF808080)              // Mid Grey Accent #808080
val FlinsPurple = Color(0xFF9E9E9E)              // Light Mid Grey #9E9E9E
val FlinsPink = Color(0xFFD0D0D0)                // Highlight Accent #D0D0D0
val FlinsCoral = Color(0xFF888888)               // Subtle Grey #888888
val FlinsEmerald = Color(0xFFA0A0A0)             // Secondary Text Grey #A0A0A0
val FlinsAmber = Color(0xFF707070)               // Muted Grey #707070
val FlinsBlue = Color(0xFFC0C0C0)                // Icon Grey #C0C0C0

// Monochromatic Typography Tokens
val FlinsTextPrimary = Color(0xFFF0F0F0)         // Primary Text #F0F0F0
val FlinsTextSecondary = Color(0xFFA0A0A0)       // Secondary Text #A0A0A0
val FlinsTextMuted = Color(0xFF707070)           // Muted Text #707070
val FlinsTextDisabled = Color(0xFF454545)        // Disabled Text #454545

// Monochromatic State Tokens
val FlinsSuccess = Color(0xFFD0D0D0)             // Subtle Grey Success
val FlinsError = Color(0xFF9E9E9E)               // High-Contrast Grey Error
val FlinsWarning = Color(0xFF808080)             // Mid Grey Warning

data class FlinsColors(
    val background: Color = FlinsDarkBg,
    val surface: Color = FlinsDarkElevated,
    val card: Color = FlinsCardBackground,
    val glassSurface: Color = FlinsCardBackground,
    val glassBorder: Color = FlinsGlassBorder,
    val glassBorderBright: Color = FlinsGlassBorderBright,
    val textPrimary: Color = FlinsTextPrimary,
    val textSecondary: Color = FlinsTextSecondary,
    val textMuted: Color = FlinsTextMuted,
    val accentPrimary: Color = FlinsCyan,
    val accentSecondary: Color = FlinsViolet,
    val accentTertiary: Color = FlinsSelected
)

fun glassLinearGradient(startAlpha: Float = 0.16f, endAlpha: Float = 0.05f): Brush {
    return Brush.verticalGradient(
        colors = listOf(
            Color(0xFF282828).copy(alpha = startAlpha.coerceIn(0f, 1f)),
            Color(0xFF181818).copy(alpha = endAlpha.coerceIn(0f, 1f))
        )
    )
}

fun glassBorderGradient(tint: Color = FlinsGlassBorder): Brush {
    return Brush.linearGradient(
        colors = listOf(
            tint.copy(alpha = 0.40f),
            tint.copy(alpha = 0.12f),
            tint.copy(alpha = 0.25f)
        )
    )
}
