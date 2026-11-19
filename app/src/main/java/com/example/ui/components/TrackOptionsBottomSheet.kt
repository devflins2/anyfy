package com.example.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.Track
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsError
import com.example.ui.theme.FlinsPink
import com.example.ui.theme.FlinsTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsBottomSheet(
    track: Track,
    isLiked: Boolean,
    onLikeToggle: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onGoToArtist: () -> Unit,
    onGoToAlbum: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onViewDetails: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
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
                .padding(bottom = 36.dp)
        ) {
            // Track Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                ) {
                    val optArtwork = track.displayArtwork.ifBlank { track.artwork }
                    val cleanVid = track.cleanVideoId
                    val ytFallback = if (cleanVid != null) "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg" else ""
                    AsyncImage(
                        model = optArtwork.ifBlank { ytFallback },
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = FlinsTheme.typography.title,
                        color = FlinsTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.artist} • ${track.album}",
                        style = FlinsTheme.typography.bodyMedium,
                        color = FlinsTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Items
            OptionRow(
                icon = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                title = if (isLiked) "Remove from Liked Songs" else "Save to Liked Songs",
                iconTint = if (isLiked) Color(0xFFF0F0F0) else Color(0xFFC0C0C0),
                onClick = {
                    onLikeToggle()
                    onDismiss()
                }
            )

            OptionRow(
                icon = Icons.Default.PlayArrow,
                title = "Play Next",
                onClick = {
                    onPlayNext()
                    onDismiss()
                }
            )

            OptionRow(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "Add to Queue",
                onClick = {
                    onAddToQueue()
                    onDismiss()
                }
            )

            OptionRow(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "Add to Playlist",
                onClick = {
                    onAddToPlaylist()
                    onDismiss()
                }
            )

            if (onStartRadio != null) {
                OptionRow(
                    icon = Icons.Default.Radio,
                    title = "Start Radio",
                    onClick = {
                        onStartRadio()
                        onDismiss()
                    }
                )
            }

            OptionRow(
                icon = Icons.Default.Person,
                title = "View Artist (${track.artist})",
                onClick = {
                    onGoToArtist()
                    onDismiss()
                }
            )

            OptionRow(
                icon = Icons.Default.Album,
                title = "View Album (${track.album})",
                onClick = {
                    onGoToAlbum()
                    onDismiss()
                }
            )

            if (onViewDetails != null) {
                OptionRow(
                    icon = Icons.Default.Info,
                    title = "View Details",
                    onClick = {
                        onViewDetails()
                        onDismiss()
                    }
                )
            }

            if (onRemoveFromPlaylist != null) {
                OptionRow(
                    icon = Icons.Default.DeleteOutline,
                    title = "Remove from Playlist",
                    iconTint = FlinsError,
                    onClick = {
                        onRemoveFromPlaylist()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    iconTint: Color = Color(0xFFC0C0C0)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = FlinsTheme.typography.body,
            color = FlinsTheme.colors.textPrimary
        )
    }
}
