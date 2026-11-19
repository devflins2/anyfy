package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.model.Track
import com.example.ui.theme.FlinsTheme

@Composable
fun SongRow(
    track: Track,
    onTrackClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrentPlaying: Boolean = false,
    isLiked: Boolean = false,
    onLikeToggle: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    testTag: String = "song_row_${track.id}"
) {
    val rowShape = RoundedCornerShape(FlinsTheme.radii.md)
    val backgroundBrush = if (isCurrentPlaying) {
        Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF282828),
                Color.Transparent
            )
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(
                Color(0x1AFFFFFF),
                Color.Transparent
            )
        )
    }

    Row(
        modifier = modifier
            .testTag(testTag)
            .fillMaxWidth()
            .clip(rowShape)
            .background(backgroundBrush)
            .then(
                if (isCurrentPlaying) {
                    Modifier.border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFF454545), Color.Transparent)
                        ),
                        shape = rowShape
                    )
                } else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Primary Track Click Area: Artwork + Title + Artist/Album Info
        // Isolated hitbox: Clicking here starts playback of this exact song.
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                .clickable(
                    indication = ripple(color = Color(0x33FFFFFF)),
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onTrackClick
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork with glass border and multi-tier reliable fallback
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                    .background(Color(0xFF202020))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF383838),
                        shape = RoundedCornerShape(FlinsTheme.radii.sm)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val artworkUrl = track.displayArtwork.ifBlank { track.artwork }
                val cleanVid = track.cleanVideoId
                val ytFallbackUrl = if (cleanVid != null) "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg" else ""

                if (artworkUrl.isNotBlank()) {
                    SubcomposeAsyncImage(
                        model = artworkUrl,
                        contentDescription = "${track.title} artwork",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = {
                            if (ytFallbackUrl.isNotBlank() && ytFallbackUrl != artworkUrl) {
                                SubcomposeAsyncImage(
                                    model = ytFallbackUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    error = {
                                        Box(
                                            modifier = Modifier.fillMaxSize().background(Color(0xFF202020)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = Color(0xFF888888),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0xFF202020)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = Color(0xFF888888),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    )
                } else if (ytFallbackUrl.isNotBlank()) {
                    SubcomposeAsyncImage(
                        model = ytFallbackUrl,
                        contentDescription = "${track.title} artwork",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color(0xFF888888),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Song Information: Title & Artist • Album
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.title,
                        style = FlinsTheme.typography.titleMedium,
                        color = if (isCurrentPlaying) Color(0xFFF0F0F0) else FlinsTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isCurrentPlaying) {
                        Spacer(modifier = Modifier.width(8.dp))
                        MiniAudioEqualizer(
                            isPlaying = true,
                            modifier = Modifier.size(width = 16.dp, height = 12.dp),
                            color = Color(0xFFBDBDBD)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = track.artist,
                        style = FlinsTheme.typography.bodyMedium,
                        color = FlinsTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .testTag("artist_text_${track.id}")
                            .clickable(
                                enabled = onArtistClick != null,
                                indication = ripple(bounded = false, radius = 18.dp, color = Color(0x33FFFFFF)),
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { onArtistClick?.invoke(track.artistId.ifBlank { track.artist }) }
                            )
                            .padding(vertical = 2.dp)
                    )
                    if (track.album.isNotBlank()) {
                        Text(
                            text = " • ",
                            style = FlinsTheme.typography.bodyMedium,
                            color = FlinsTheme.colors.textMuted
                        )
                        Text(
                            text = track.album,
                            style = FlinsTheme.typography.bodyMedium,
                            color = FlinsTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .testTag("album_text_${track.id}")
                                .clickable(
                                    enabled = onAlbumClick != null,
                                    indication = ripple(bounded = false, radius = 18.dp, color = Color(0x33FFFFFF)),
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { onAlbumClick?.invoke(track.album) }
                                )
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 2. Trailing Actions Area: Isolated hitboxes for Duration, Like, and More options
        // Completely outside onTrackClick to prevent accidental track launches
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            // Duration
            Text(
                text = track.durationFormatted,
                style = FlinsTheme.typography.metadata,
                color = FlinsTheme.colors.textMuted
            )

            // Like Button (Isolated hitbox)
            if (onLikeToggle != null) {
                IconButton(
                    onClick = onLikeToggle,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("like_button_${track.id}")
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isLiked) "Unlike" else "Like",
                        tint = if (isLiked) Color(0xFFF0F0F0) else FlinsTheme.colors.textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // More Options (Isolated hitbox)
            if (onMoreClick != null) {
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("more_button_${track.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options for ${track.title}",
                        tint = FlinsTheme.colors.textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
