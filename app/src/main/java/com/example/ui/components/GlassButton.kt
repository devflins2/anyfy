package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsTheme
import com.example.ui.theme.FlinsViolet

enum class GlassButtonVariant {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    GHOST
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: GlassButtonVariant = GlassButtonVariant.PRIMARY,
    shape: Shape = RoundedCornerShape(FlinsTheme.radii.pill),
    testTag: String = "glass_button",
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    val bgBrush = when (variant) {
        GlassButtonVariant.PRIMARY -> Brush.linearGradient(
            colors = listOf(Color(0xFFE0E0E0), Color(0xFFBDBDBD))
        )
        GlassButtonVariant.SECONDARY -> Brush.verticalGradient(
            colors = listOf(Color(0xFF282828), Color(0xFF202020))
        )
        GlassButtonVariant.OUTLINE, GlassButtonVariant.GHOST -> Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Transparent)
        )
    }

    val borderModifier = when (variant) {
        GlassButtonVariant.PRIMARY -> Modifier
        GlassButtonVariant.SECONDARY -> Modifier.border(
            width = 1.dp,
            color = Color(0xFF383838),
            shape = shape
        )
        GlassButtonVariant.OUTLINE -> Modifier.border(
            width = 1.dp,
            color = Color(0xFF383838),
            shape = shape
        )
        GlassButtonVariant.GHOST -> Modifier
    }

    Box(
        modifier = modifier
            .testTag(testTag)
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(brush = bgBrush, shape = shape)
            .then(borderModifier)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White.copy(alpha = 0.3f)),
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = CircleShape,
    backgroundColor: Color = Color(0xFF282828),
    borderColor: Color = Color(0xFF383838),
    testTag: String = "glass_icon_button",
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .testTag(testTag)
            .size(size)
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(backgroundColor, shape)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(borderColor.copy(alpha = 0.3f), borderColor.copy(alpha = 0.08f))
                ),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color.White.copy(alpha = 0.3f)),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
