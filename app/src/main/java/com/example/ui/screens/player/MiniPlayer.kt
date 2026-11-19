package com.example.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.model.Track
import com.example.service.PlayerStatus
import com.example.ui.components.MiniAudioEqualizer
import com.example.ui.theme.FlinsTheme

@Composable
fun MiniPlayer(
    currentTrack: Track,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playerStatus: PlayerStatus = PlayerStatus.PLAYING,
    onExpandFullPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val miniPlayerShape = RoundedCornerShape(22.dp)
    val effectiveDuration = if (durationMs > 0) durationMs else (currentTrack.durationSeconds * 1000L).coerceAtLeast(1000L)
    val progressFraction = (currentPositionMs.toFloat() / effectiveDuration).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .testTag("mini_player")
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(miniPlayerShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF0181818),
                        Color(0xF5101010)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF383838),
                        Color(0xFF282828)
                    )
                ),
                shape = miniPlayerShape
            )
    ) {
        val miniPlayerBanner = currentTrack.displayBannerArtwork.ifBlank { currentTrack.displayArtwork.ifBlank { currentTrack.artwork } }
        if (miniPlayerBanner.isNotBlank()) {
            SubcomposeAsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(miniPlayerBanner)
                    .crossfade(true)
                    .memoryCacheKey("${currentTrack.id}_mini_banner")
                    .diskCacheKey("${currentTrack.id}_mini_banner")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer(alpha = 0.12f)
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Isolated Expandable Track Info Area (Artwork + Title & Artist)
                // Clicking here opens the Full Screen Player
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            indication = ripple(color = Color(0x33FFFFFF)),
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onExpandFullPlayer
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Artwork with rounded corners and multi-tier reliable fallback
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF202020))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val artworkUrl = currentTrack.displayArtwork.ifBlank { currentTrack.artwork }
                        val cleanVid = currentTrack.cleanVideoId
                        val ytFallbackUrl = if (cleanVid != null) "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg" else ""

                        if (artworkUrl.isNotBlank()) {
                            SubcomposeAsyncImage(
                                model = artworkUrl,
                                contentDescription = currentTrack.title,
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
                                                Icon(
                                                    imageVector = Icons.Default.MusicNote,
                                                    contentDescription = null,
                                                    tint = Color(0xFF888888),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = Color(0xFF888888),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            )
                        } else if (ytFallbackUrl.isNotBlank()) {
                            SubcomposeAsyncImage(
                                model = ytFallbackUrl,
                                contentDescription = currentTrack.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                error = {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = Color(0xFF888888),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color(0xFF888888),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Title and Artist
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTrack.title,
                            style = FlinsTheme.typography.titleMedium,
                            color = FlinsTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentTrack.artist,
                            style = FlinsTheme.typography.caption,
                            color = FlinsTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .testTag("mini_player_artist_text")
                                .clickable(
                                    enabled = onArtistClick != null,
                                    indication = ripple(bounded = false, radius = 18.dp, color = Color(0x33FFFFFF)),
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { onArtistClick?.invoke(currentTrack.artistId.ifBlank { currentTrack.artist }) }
                                )
                                .padding(vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 2. Isolated Controls Area (Equalizer, Play/Pause, Next)
                // Completely independent hitboxes - tapping here will NEVER open the Full Player
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mini animated audio equalizer
                    MiniAudioEqualizer(
                        isPlaying = isPlaying && playerStatus == PlayerStatus.PLAYING,
                        modifier = Modifier
                            .size(width = 20.dp, height = 16.dp)
                            .padding(end = 4.dp),
                        color = Color(0xFFBDBDBD)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Play / Pause Control
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("mini_player_play_pause")
                            .clip(CircleShape)
                            .background(Color(0xFF454545))
                    ) {
                        if (playerStatus == PlayerStatus.EXTRACTING ||
                            playerStatus == PlayerStatus.CONNECTING ||
                            playerStatus == PlayerStatus.BUFFERING ||
                            playerStatus == PlayerStatus.LOADING
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color(0xFFF0F0F0),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying && playerStatus == PlayerStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying && playerStatus == PlayerStatus.PLAYING) "Pause" else "Play",
                                tint = Color(0xFFF0F0F0),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Next Track Control
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("mini_player_next")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Continuous Micro-Progress Bar Line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(Color(0xFF383838))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progressFraction)
                        .height(2.5.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFFBDBDBD), Color(0xFF808080))
                            )
                        )
                )
            }
        }
    }
}
