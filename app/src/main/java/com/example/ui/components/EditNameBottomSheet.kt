package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditNameBottomSheet(
    currentName: String,
    currentGreeting: String = "",
    isCustomGreeting: Boolean = false,
    onSaveName: (String) -> Unit,
    onSaveCustomGreeting: ((String) -> Unit)? = null,
    onRestoreDefault: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var nameInput by remember { mutableStateOf(currentName.ifBlank { "Gaurav" }) }
    var greetingInput by remember { mutableStateOf(currentGreeting) }
    var isCustomMode by remember { mutableStateOf(isCustomGreeting && currentGreeting.isNotBlank()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val timeGreeting = remember(currentHour) {
        when (currentHour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else -> "Good Night"
        }
    }

    val previewText = remember(isCustomMode, nameInput, greetingInput, timeGreeting) {
        if (isCustomMode) {
            greetingInput.trim().ifBlank { "$timeGreeting, ${nameInput.trim().ifBlank { "Gaurav" }}" }
        } else {
            "$timeGreeting, ${nameInput.trim().ifBlank { "Gaurav" }}"
        }
    }

    val isValid = remember(isCustomMode, nameInput, greetingInput) {
        if (isCustomMode) greetingInput.trim().isNotBlank()
        else nameInput.trim().isNotBlank()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FlinsDarkBg.copy(alpha = 0.95f),
        contentColor = FlinsTheme.colors.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag indicator handle
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Personalize Greeting",
                style = FlinsTheme.typography.title,
                color = FlinsTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Customize how Anyfy welcomes you to your listening sanctuary",
                style = FlinsTheme.typography.bodyMedium,
                color = FlinsTheme.colors.textSecondary
            )
            Spacer(modifier = Modifier.height(18.dp))

            // Mode Selector Tabs (Display Name vs Custom Greeting)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Tab 1: Display Name
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isCustomMode) Color(0xFF333333) else Color.Transparent)
                        .clickable { isCustomMode = false }
                        .padding(vertical = 8.dp)
                        .testTag("display_name_tab"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Display Name",
                        style = FlinsTheme.typography.caption.copy(
                            fontWeight = if (!isCustomMode) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (!isCustomMode) Color(0xFFF0F0F0) else Color(0xFF9E9E9E)
                        )
                    )
                }

                // Tab 2: Custom Greeting
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isCustomMode) Color(0xFF333333) else Color.Transparent)
                        .clickable {
                            isCustomMode = true
                            if (greetingInput.isBlank()) {
                                greetingInput = "$timeGreeting, ${nameInput.trim().ifBlank { "Gaurav" }}"
                            }
                        }
                        .padding(vertical = 8.dp)
                        .testTag("custom_greeting_tab"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Custom Greeting",
                        style = FlinsTheme.typography.caption.copy(
                            fontWeight = if (isCustomMode) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCustomMode) Color(0xFFF0F0F0) else Color(0xFF9E9E9E)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Greeting Preview Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Text(
                        text = "LIVE HOME PREVIEW",
                        style = FlinsTheme.typography.overline,
                        color = Color(0xFF888888)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = previewText,
                        style = FlinsTheme.typography.hero.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = FlinsTheme.colors.textPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Glass input container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(FlinsTheme.radii.md))
                    .background(Color(0xFF282828))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (isCustomMode) {
                    BasicTextField(
                        value = greetingInput,
                        onValueChange = { greetingInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_greeting_input"),
                        textStyle = FlinsTheme.typography.titleMedium.copy(color = FlinsTheme.colors.textPrimary),
                        cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                        singleLine = true
                    )
                } else {
                    BasicTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("name_text_input"),
                        textStyle = FlinsTheme.typography.titleMedium.copy(color = FlinsTheme.colors.textPrimary),
                        cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                        singleLine = true
                    )
                }
            }

            // Validation hint if blank
            if (!isValid) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isCustomMode) "Greeting text cannot be empty" else "Display name cannot be empty",
                    style = FlinsTheme.typography.caption.copy(fontSize = 11.sp),
                    color = Color(0xFFE57373)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Restore Default Action
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        nameInput = "Gaurav"
                        greetingInput = ""
                        isCustomMode = false
                        onRestoreDefault?.invoke()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("restore_default_name_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Restore Default (Gaurav)",
                    style = FlinsTheme.typography.caption.copy(
                        color = FlinsCyan,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Cancel and Save actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlassButton(
                    onClick = onDismiss,
                    variant = GlassButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f),
                    testTag = "cancel_edit_name_button"
                ) {
                    Text(
                        text = "Cancel",
                        style = FlinsTheme.typography.button,
                        color = FlinsTheme.colors.textSecondary
                    )
                }
                GlassButton(
                    onClick = {
                        if (!isValid) return@GlassButton
                        if (isCustomMode) {
                            val trimmedGreeting = greetingInput.trim()
                            if (trimmedGreeting.isNotEmpty()) {
                                if (onSaveCustomGreeting != null) {
                                    onSaveCustomGreeting(trimmedGreeting)
                                } else {
                                    onSaveName(trimmedGreeting)
                                }
                            }
                        } else {
                            val trimmed = nameInput.trim()
                            if (trimmed.isNotEmpty()) {
                                onSaveName(trimmed)
                            }
                        }
                    },
                    variant = GlassButtonVariant.PRIMARY,
                    modifier = Modifier.weight(1f),
                    testTag = "save_edit_name_button"
                ) {
                    Text(
                        text = "Save",
                        style = FlinsTheme.typography.button,
                        color = Color.White
                    )
                }
            }
        }
    }
}
