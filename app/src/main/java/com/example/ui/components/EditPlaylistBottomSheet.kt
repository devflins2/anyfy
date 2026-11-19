package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPlaylistBottomSheet(
    currentName: String,
    currentDescription: String,
    currentArtwork: String,
    onSave: (name: String, description: String, artwork: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var description by remember { mutableStateOf(currentDescription) }
    var artwork by remember { mutableStateOf(currentArtwork) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FlinsDarkBg.copy(alpha = 0.96f),
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
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Edit Playlist Details",
                style = FlinsTheme.typography.title,
                color = FlinsTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Update the title, description, and custom cover art",
                style = FlinsTheme.typography.bodyMedium,
                color = FlinsTheme.colors.textSecondary
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Cover Image Preview if present
            if (artwork.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(FlinsTheme.radii.md))
                        .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                ) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = "Cover preview",
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Name field
            Text(
                text = "Playlist Name",
                style = FlinsTheme.typography.label,
                color = Color(0xFFF0F0F0),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(FlinsTheme.radii.md))
                    .background(Color(0xFF282828))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (name.isEmpty()) {
                    Text(
                        text = "Playlist name",
                        style = FlinsTheme.typography.body,
                        color = FlinsTheme.colors.textMuted
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_playlist_name_input"),
                    textStyle = FlinsTheme.typography.body.copy(color = FlinsTheme.colors.textPrimary),
                    cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Description field
            Text(
                text = "Description (optional)",
                style = FlinsTheme.typography.label,
                color = FlinsTheme.colors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(FlinsTheme.radii.md))
                    .background(Color(0xFF282828))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (description.isEmpty()) {
                    Text(
                        text = "Add an optional description",
                        style = FlinsTheme.typography.body,
                        color = FlinsTheme.colors.textMuted
                    )
                }
                BasicTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_playlist_desc_input"),
                    textStyle = FlinsTheme.typography.body.copy(color = FlinsTheme.colors.textPrimary),
                    cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Cover Image URL field
            Text(
                text = "Custom Cover Image URL (optional)",
                style = FlinsTheme.typography.label,
                color = FlinsTheme.colors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(FlinsTheme.radii.md))
                    .background(Color(0xFF282828))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (artwork.isEmpty()) {
                    Text(
                        text = "https://example.com/cover.jpg",
                        style = FlinsTheme.typography.body,
                        color = FlinsTheme.colors.textMuted
                    )
                }
                BasicTextField(
                    value = artwork,
                    onValueChange = { artwork = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_playlist_artwork_input"),
                    textStyle = FlinsTheme.typography.body.copy(color = FlinsTheme.colors.textPrimary),
                    cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlassButton(
                    onClick = onDismiss,
                    variant = GlassButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel", style = FlinsTheme.typography.button, color = FlinsTheme.colors.textSecondary)
                }
                GlassButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            onSave(trimmed, description.trim(), artwork.trim())
                            onDismiss()
                        }
                    },
                    variant = GlassButtonVariant.PRIMARY,
                    modifier = Modifier.weight(1f),
                    testTag = "save_playlist_details_button"
                ) {
                    Text("Save", style = FlinsTheme.typography.button, color = Color.White)
                }
            }
        }
    }
}
