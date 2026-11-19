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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.Playlist
import com.example.model.Track
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistBottomSheet(
    track: Track,
    playlists: List<Playlist>,
    onSelectPlaylist: (playlistId: String) -> Unit,
    onCreateNewPlaylist: (name: String, description: String, privacy: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isCreatingNew by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistDesc by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }
    var duplicatePlaylistTarget by remember { mutableStateOf<Playlist?>(null) }

    if (duplicatePlaylistTarget != null) {
        val targetPlaylist = duplicatePlaylistTarget!!
        AlertDialog(
            onDismissRequest = { duplicatePlaylistTarget = null },
            title = {
                Text(
                    text = "Duplicate Song",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "'${track.title}' is already in '${targetPlaylist.name}'. Do you want to skip or keep the existing song?",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    duplicatePlaylistTarget = null
                    onDismiss()
                }) {
                    Text("Keep Existing", color = FlinsCyan, style = FlinsTheme.typography.button)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    duplicatePlaylistTarget = null
                }) {
                    Text("Skip", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
                }
            },
            containerColor = FlinsDarkBg,
            shape = RoundedCornerShape(24.dp)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FlinsDarkBg.copy(alpha = 0.98f),
        contentColor = FlinsTheme.colors.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            // Drag Handle
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(18.dp))

            // Track Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(FlinsTheme.radii.sm))
                ) {
                    AsyncImage(
                        model = track.artwork,
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add to playlist",
                        style = FlinsTheme.typography.overline,
                        color = FlinsCyan
                    )
                    Text(
                        text = track.title,
                        style = FlinsTheme.typography.title,
                        color = FlinsTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = FlinsTheme.typography.metadata,
                        color = FlinsTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // "+ New Playlist" Button or Inline Input Box
            if (!isCreatingNew) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FlinsTheme.radii.md))
                        .background(Color(0xFF282828))
                        .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                        .clickable { isCreatingNew = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(FlinsCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Playlist",
                            tint = FlinsCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "New playlist",
                        style = FlinsTheme.typography.body,
                        color = FlinsTheme.colors.textPrimary
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FlinsTheme.radii.md))
                        .background(Color(0xFF202020))
                        .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Create Playlist & Add Track",
                        style = FlinsTheme.typography.label,
                        color = Color(0xFFF0F0F0)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                            .background(Color(0xFF282828))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.sm))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (newPlaylistName.isEmpty()) {
                            Text(
                                text = "Playlist name",
                                style = FlinsTheme.typography.bodyMedium,
                                color = FlinsTheme.colors.textMuted
                            )
                        }
                        BasicTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("new_playlist_inline_input"),
                            textStyle = FlinsTheme.typography.body.copy(color = FlinsTheme.colors.textPrimary),
                            cursorBrush = SolidColor(FlinsCyan),
                            singleLine = true
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                            .background(Color(0xFF282828))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.sm))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPrivate) "Private" else "Public",
                            style = FlinsTheme.typography.metadata,
                            color = Color(0xFFF0F0F0)
                        )
                        GlassButton(
                            onClick = { isPrivate = !isPrivate },
                            variant = GlassButtonVariant.GHOST,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (isPrivate) "Set Public" else "Set Private",
                                style = FlinsTheme.typography.overline,
                                color = Color(0xFFBDBDBD)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        GlassButton(
                            onClick = {
                                isCreatingNew = false
                                newPlaylistName = ""
                                newPlaylistDesc = ""
                            },
                            variant = GlassButtonVariant.GHOST,
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text("Cancel", style = FlinsTheme.typography.button, color = FlinsTheme.colors.textSecondary)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        GlassButton(
                            onClick = {
                                val trimmed = newPlaylistName.trim()
                                if (trimmed.isNotBlank()) {
                                    onCreateNewPlaylist(trimmed, newPlaylistDesc.trim(), if (isPrivate) "PRIVATE" else "PUBLIC")
                                }
                            },
                            variant = GlassButtonVariant.PRIMARY,
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text("Create & Add", style = FlinsTheme.typography.button, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // User's Playlists List
            if (playlists.isEmpty() && !isCreatingNew) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = FlinsTheme.colors.textMuted,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No playlists yet",
                            style = FlinsTheme.typography.body,
                            color = FlinsTheme.colors.textSecondary
                        )
                        Text(
                            text = "Tap 'New playlist' above to start your collection",
                            style = FlinsTheme.typography.metadata,
                            color = FlinsTheme.colors.textMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        val isAlreadyInPlaylist = playlist.trackIds.contains(track.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                                .clickable {
                                    if (isAlreadyInPlaylist) {
                                        duplicatePlaylistTarget = playlist
                                    } else {
                                        onSelectPlaylist(playlist.id)
                                    }
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Playlist Thumbnail
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                                    .background(Color(0x22FFFFFF))
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(FlinsTheme.radii.sm)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (playlist.artwork.isNotBlank()) {
                                    AsyncImage(
                                        model = playlist.artwork,
                                        contentDescription = playlist.name,
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = FlinsCyan,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = FlinsTheme.typography.body,
                                    color = if (isAlreadyInPlaylist) FlinsTheme.colors.textSecondary else FlinsTheme.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${playlist.trackIds.size} frequencies",
                                    style = FlinsTheme.typography.metadata,
                                    color = FlinsTheme.colors.textMuted
                                )
                            }

                            if (isAlreadyInPlaylist) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                        .background(FlinsCyan.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Added",
                                        tint = FlinsCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Added",
                                        style = FlinsTheme.typography.metadata,
                                        color = FlinsCyan
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistBottomSheet(
    track: Track,
    playlists: List<Playlist>,
    onSelectPlaylist: (playlistId: String) -> Unit,
    onCreateNewPlaylist: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit
) {
    AddToPlaylistBottomSheet(
        track = track,
        playlists = playlists,
        onSelectPlaylist = onSelectPlaylist,
        onCreateNewPlaylist = { n, d, _ -> onCreateNewPlaylist(n, d) },
        onDismiss = onDismiss
    )
}
