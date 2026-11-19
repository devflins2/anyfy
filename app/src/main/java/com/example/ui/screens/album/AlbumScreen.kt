package com.example.ui.screens.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.example.model.Album
import com.example.model.Track
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassButtonVariant
import com.example.ui.components.GlassIconButton
import com.example.ui.components.LoadingSkeleton
import com.example.ui.components.SectionHeader
import com.example.ui.components.SongRow
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme

@Composable
fun AlbumScreen(
    album: Album,
    allTracks: List<Track>,
    likedTrackIds: Set<String>,
    currentPlayingTrack: Track?,
    onTrackClick: (Track, List<Track>) -> Unit,
    onLikeToggle: (String) -> Unit,
    onTrackOptionsClick: (Track) -> Unit,
    onBack: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    var isDownloaded by remember { mutableStateOf(false) }

    val albumTracks = remember(album, allTracks) {
        if (album.tracks.isNotEmpty()) {
            album.tracks
        } else {
            val mappedById = album.trackIds.mapNotNull { tid -> allTracks.find { it.id == tid } }
            if (mappedById.isNotEmpty()) mappedById else allTracks.filter {
                it.albumId == album.id || it.album.equals(album.title, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Back Button & Header
        item(key = "album_back_button_bar") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                GlassIconButton(
                    onClick = onBack,
                    size = 40.dp,
                    testTag = "album_back_button"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Large Artwork & Metadata (Section 16)
        item(key = "album_hero_artwork") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(FlinsTheme.radii.xl))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(FlinsTheme.radii.xl))
                ) {
                    val albumArtUrl = album.displayArtwork.ifBlank { album.bannerArtwork }
                    if (albumArtUrl.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = albumArtUrl,
                            contentDescription = album.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            loading = {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0xFF202020)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = Color(0xFF888888),
                                        strokeWidth = 2.dp
                                    )
                                }
                            },
                            error = {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0xFF202020)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Album,
                                        contentDescription = null,
                                        tint = Color(0xFF666666),
                                        modifier = Modifier.size(56.dp)
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
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = Color(0xFF666666),
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = album.title,
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
                val metadataParts = listOfNotNull(
                    album.artist.takeIf { it.isNotBlank() && it != "Various Artists" } ?: album.artist.takeIf { it.isNotBlank() },
                    album.releaseYear.takeIf { it.isNotBlank() },
                    (album.type.takeIf { it.isNotBlank() && it != "Album" } ?: album.genre).takeIf { it.isNotBlank() }
                )
                Text(
                    text = if (metadataParts.isNotEmpty()) metadataParts.joinToString(" • ") else album.title,
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary,
                    modifier = Modifier.clickable(
                        enabled = onArtistClick != null,
                        onClick = { onArtistClick?.invoke(album.artist) }
                    )
                )
                Spacer(modifier = Modifier.height(18.dp))

                // Actions: Play, Shuffle, Download UI (Section 16)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (albumTracks.isNotEmpty()) {
                        GlassButton(
                            onClick = { onTrackClick(albumTracks.first(), albumTracks) },
                            variant = GlassButtonVariant.PRIMARY,
                            testTag = "album_play_button"
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = FlinsDarkBg,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play", style = FlinsTheme.typography.button, color = FlinsDarkBg)
                        }

                        GlassIconButton(
                            onClick = {
                                val shuffled = albumTracks.shuffled()
                                onTrackClick(shuffled.first(), shuffled)
                            },
                            size = 46.dp,
                            testTag = "album_shuffle_button"
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = Color(0xFFC0C0C0),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Download UI toggle (Section 16: Download UI)
                    GlassIconButton(
                        onClick = { isDownloaded = !isDownloaded },
                        size = 46.dp,
                        testTag = "album_download_button"
                    ) {
                        Icon(
                            imageVector = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                            contentDescription = "Download album",
                            tint = if (isDownloaded) Color(0xFFF0F0F0) else FlinsTheme.colors.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Track list
        item(key = "album_tracklist_header") {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader(
                overline = "Tracklist (${albumTracks.size})",
                title = "Album Tracks"
            )
        }

        if (albumTracks.isEmpty()) {
            item(key = "album_skeleton") {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    LoadingSkeleton(itemCount = 4)
                }
            }
        } else {
            items(albumTracks, key = { "alb_trk_${it.id}" }) { track ->
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                    SongRow(
                        track = track,
                        onTrackClick = { onTrackClick(track, albumTracks) },
                        isCurrentPlaying = track.id == currentPlayingTrack?.id,
                        isLiked = likedTrackIds.contains(track.id),
                        onLikeToggle = { onLikeToggle(track.id) },
                        onMoreClick = { onTrackOptionsClick(track) },
                        onArtistClick = onArtistClick,
                        onAlbumClick = null
                    )
                }
            }
        }
    }
}
