package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalFlinsColors = staticCompositionLocalOf { FlinsColors() }
val LocalFlinsTypography = staticCompositionLocalOf { DefaultFlinsTypography }
val LocalFlinsSpacing = staticCompositionLocalOf { FlinsSpacing() }
val LocalFlinsRadii = staticCompositionLocalOf { FlinsRadii() }

// Monochromatic Grey Theme Dark & Light Color Schemes
private val DarkColorScheme = darkColorScheme(
    primary = FlinsProgressIndicator,       // #BDBDBD
    onPrimary = FlinsDarkBg,                 // #101010
    primaryContainer = FlinsSurfaceElevated, // #282828
    onPrimaryContainer = FlinsTextPrimary,   // #F0F0F0
    secondary = FlinsViolet,                 // #808080
    onSecondary = FlinsDarkBg,               // #101010
    background = FlinsDarkBg,                // #101010
    onBackground = FlinsTextPrimary,         // #F0F0F0
    surface = FlinsDarkElevated,             // #181818
    onSurface = FlinsTextPrimary,            // #F0F0F0
    surfaceVariant = FlinsCardBackground,    // #202020
    onSurfaceVariant = FlinsTextSecondary,   // #A0A0A0
    outline = FlinsGlassBorder               // #383838
)

private val LightColorScheme = lightColorScheme(
    primary = FlinsProgressIndicator,       // #BDBDBD
    onPrimary = FlinsDarkBg,                 // #101010
    primaryContainer = FlinsSurfaceElevated, // #282828
    onPrimaryContainer = FlinsTextPrimary,   // #F0F0F0
    secondary = FlinsViolet,                 // #808080
    onSecondary = FlinsDarkBg,               // #101010
    background = FlinsDarkBg,                // #101010
    onBackground = FlinsTextPrimary,         // #F0F0F0
    surface = FlinsDarkElevated,             // #181818
    onSurface = FlinsTextPrimary,            // #F0F0F0
    surfaceVariant = FlinsCardBackground,    // #202020
    onSurfaceVariant = FlinsTextSecondary,   // #A0A0A0
    outline = FlinsGlassBorder               // #383838
)

object FlinsTheme {
    val colors: FlinsColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFlinsColors.current

    val typography: FlinsTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalFlinsTypography.current

    val spacing: FlinsSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalFlinsSpacing.current

    val radii: FlinsRadii
        @Composable
        @ReadOnlyComposable
        get() = LocalFlinsRadii.current
}

@Composable
fun FlinsMusicTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val flinsColors = FlinsColors()
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(
        LocalFlinsColors provides flinsColors,
        LocalFlinsTypography provides DefaultFlinsTypography,
        LocalFlinsSpacing provides FlinsSpacing(),
        LocalFlinsRadii provides FlinsRadii()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTypographyBridge,
            content = content
        )
    }
}
