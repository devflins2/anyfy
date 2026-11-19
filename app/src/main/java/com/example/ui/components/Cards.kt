package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsTheme
import com.example.ui.theme.FlinsViolet

@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(FlinsTheme.radii.lg)
    Column(
        modifier = modifier
            .testTag("album_card_${album.id}")
            .width(148.dp)
            .clickable(onClick = onClick)
    ) {
        // Layered Artwork with subtle glass frame
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(cardShape)
                .background(Color(0xFF202020))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.05f))
                    ),
                    shape = cardShape
                )
        ) {
            SubcomposeAsyncImage(
                model = album.displayArtwork.ifBlank { album.artwork.ifBlank { album.bannerArtwork } },
                contentDescription = "${album.title} album cover",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF202020)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            tint = Color(0xFF666666),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            )
            // Bottom glass badge with release year
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                    .background(Color(0xD9101010))
                    .border(0.5.dp, FlinsTheme.colors.glassBorder, RoundedCornerShape(FlinsTheme.radii.pill))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = album.releaseYear,
                    style = FlinsTheme.typography.metadata,
                    color = FlinsTheme.colors.textSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = album.title,
            style = FlinsTheme.typography.titleMedium,
            color = FlinsTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = album.artist,
            style = FlinsTheme.typography.bodyMedium,
            color = FlinsTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ArtistCard(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .testTag("artist_card_${artist.id}")
            .width(112.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circular artwork with radiant gradient rim
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Color(0xFF202020))
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        colors = listOf(Color(0xFF383838), Color(0xFFBDBDBD), Color(0xFF383838))
                    ),
                    shape = CircleShape
                )
                .padding(3.dp)
                .clip(CircleShape)
        ) {
            AsyncImage(
                model = artist.displayArtwork.ifBlank { artist.artwork.ifBlank { artist.bannerArtwork } },
                contentDescription = "${artist.name} artist portrait",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = FlinsTheme.typography.label,
            color = FlinsTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = "${artist.monthlyListeners} monthly",
            style = FlinsTheme.typography.metadata,
            color = FlinsTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(FlinsTheme.radii.lg)
    Column(
        modifier = modifier
            .testTag("playlist_card_${playlist.id}")
            .width(152.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(152.dp)
                .clip(cardShape)
                .background(Color(0xFF202020))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.05f))
                    ),
                    shape = cardShape
                )
        ) {
            AsyncImage(
                model = playlist.artwork,
                contentDescription = "${playlist.name} playlist artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Translucent glass bottom overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xD9101010))
                        )
                    )
                    .padding(8.dp)
            ) {
                Text(
                    text = "${playlist.trackIds.size} tracks",
                    style = FlinsTheme.typography.metadata,
                    color = Color(0xFFBDBDBD)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = FlinsTheme.typography.titleMedium,
            color = FlinsTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (playlist.description.isNotBlank()) {
            Text(
                text = playlist.description,
                style = FlinsTheme.typography.caption,
                color = FlinsTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
