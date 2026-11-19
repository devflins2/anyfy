package com.example.ui.screens.library

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.Track
import com.example.ui.components.AlbumCard
import com.example.ui.components.ArtistCard
import com.example.ui.components.EmptyState
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassButtonVariant
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconButton
import com.example.ui.components.PlaylistCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.SongRow
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsError
import com.example.ui.theme.FlinsPink
import com.example.ui.theme.FlinsTheme
import com.example.ui.theme.FlinsViolet

@Composable
fun LibraryScreen(
    likedTrackIds: Set<String>,
    allTracks: List<Track>,
    playlists: List<Playlist>,
    albums: List<Album>,
    artists: List<Artist>,
    recentlyPlayed: List<Track>,
    currentPlayingTrack: Track?,
    onTrackClick: (Track, List<Track>) -> Unit,
    onLikeToggle: (String) -> Unit,
    onTrackOptionsClick: (Track) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onImportPlaylistClick: () -> Unit = {},
    onDeletePlaylist: (String) -> Unit,
    onSignOut: () -> Unit = {},
    onOpenSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val libraryTabs = listOf("Liked Songs", "Playlists", "Albums", "Artists", "Downloads", "Recently Played")
    var selectedCategory by remember { mutableStateOf("Liked Songs") }

    val likedTracks = remember(likedTrackIds, allTracks) {
        allTracks.filter { likedTrackIds.contains(it.id) }
    }

    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    // Confirmation dialog for deleting playlist (Section 18: Confirm destructive actions)
    if (playlistToDelete != null) {
        val target = playlistToDelete!!
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete Playlist?", style = FlinsTheme.typography.title, color = FlinsTheme.colors.textPrimary) },
            text = { Text("Are you sure you want to remove '${target.name}'? This action cannot be undone.", style = FlinsTheme.typography.bodyMedium, color = FlinsTheme.colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePlaylist(target.id)
                    playlistToDelete = null
                }) {
                    Text("Delete", color = FlinsError, style = FlinsTheme.typography.button)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
                }
            },
            containerColor = FlinsDarkBg,
            shape = RoundedCornerShape(24.dp)
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Library Header & Create Playlist button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PERSONAL COLLECTION",
                        style = FlinsTheme.typography.overline,
                        color = FlinsTheme.colors.textMuted
                    )
                    Text(
                        text = "Your Library",
                        style = FlinsTheme.typography.hero,
                        color = FlinsTheme.colors.textPrimary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Settings button
                    GlassIconButton(
                        onClick = onOpenSettingsClick,
                        size = 44.dp,
                        testTag = "library_settings_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // Import playlist button
                    GlassIconButton(
                        onClick = onImportPlaylistClick,
                        size = 44.dp,
                        testTag = "import_playlist_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Import playlist",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // Create playlist button
                    GlassIconButton(
                        onClick = onCreatePlaylistClick,
                        size = 44.dp,
                        testTag = "create_playlist_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create playlist",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Library Navigation Tabs (Section 17)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(libraryTabs, key = { it }) { tab ->
                    val isSelected = tab == selectedCategory
                    val bg = if (isSelected) Color(0xFF454545) else Color(0xFF202020)
                    val textCol = if (isSelected) Color(0xFFF0F0F0) else FlinsTheme.colors.textSecondary

                    Box(
                        modifier = Modifier
                            .testTag("library_tab_${tab.lowercase().replace(" ", "_")}")
                            .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                            .background(bg)
                            .clickable { selectedCategory = tab }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = tab,
                            style = FlinsTheme.typography.label,
                            color = textCol
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        // Category Content
        when (selectedCategory) {
            "Liked Songs" -> {
                item {
                    // Liked Songs Hero Banner
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        shape = RoundedCornerShape(22.dp),
                        backgroundColor = Color(0xFF202020),
                        borderColor = Color(0xFF383838)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF454545), Color(0xFF282828))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = Color(0xFFF0F0F0),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Liked Songs",
                                    style = FlinsTheme.typography.title,
                                    color = FlinsTheme.colors.textPrimary
                                )
                                Text(
                                    text = "${likedTracks.size} frequencies stored locally",
                                    style = FlinsTheme.typography.bodyMedium,
                                    color = FlinsTheme.colors.textSecondary
                                )
                            }
                            if (likedTracks.isNotEmpty()) {
                                GlassIconButton(
                                    onClick = { onTrackClick(likedTracks.first(), likedTracks) },
                                    size = 44.dp
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play all",
                                        tint = Color(0xFFF0F0F0),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (likedTracks.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No liked frequencies",
                            subtitle = "Tap the heart icon on any track to save it here for instant recall.",
                            icon = Icons.Default.Favorite
                        )
                    }
                } else {
                    items(likedTracks, key = { "lib_liked_${it.id}" }) { track ->
                        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                            SongRow(
                                track = track,
                                onTrackClick = { onTrackClick(track, likedTracks) },
                                isCurrentPlaying = track.id == currentPlayingTrack?.id,
                                isLiked = true,
                                onLikeToggle = { onLikeToggle(track.id) },
                                onMoreClick = { onTrackOptionsClick(track) },
                                onArtistClick = { artistName ->
                                    val foundArtist = artists.find { it.id == track.artistId || it.name.equals(artistName, ignoreCase = true) }
                                        ?: Artist(id = if (track.artistId.isNotBlank() && track.artistId != "art_1") track.artistId else "art_${artistName.lowercase().replace(" ", "_")}", name = artistName, artwork = track.artwork)
                                    onArtistClick(foundArtist)
                                },
                                onAlbumClick = { albumTitle ->
                                    val foundAlbum = albums.find {
                                        (track.albumId.isNotBlank() && !track.albumId.startsWith("alb_") && it.id == track.albumId) ||
                                        it.title.equals(albumTitle, ignoreCase = true) ||
                                        it.title.equals(track.album, ignoreCase = true)
                                    } ?: Album(
                                        id = if (track.albumId.isNotBlank() && !track.albumId.startsWith("alb_")) track.albumId else "alb_${(albumTitle.ifBlank { track.album }).lowercase().replace(" ", "_")}",
                                        title = if (track.album.isNotBlank()) track.album else albumTitle,
                                        artist = track.artist,
                                        artistId = track.artistId,
                                        artwork = track.artwork,
                                        bannerArtwork = track.bannerArtwork
                                    )
                                    onAlbumClick(foundAlbum)
                                }
                            )
                        }
                    }
                }
            }
            "Playlists" -> {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            onClick = onCreatePlaylistClick,
                            modifier = Modifier.weight(1f).height(40.dp),
                            variant = GlassButtonVariant.SECONDARY,
                            shape = RoundedCornerShape(FlinsTheme.radii.pill),
                            testTag = "playlists_tab_create_button"
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFC0C0C0))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create", style = FlinsTheme.typography.label, color = FlinsTheme.colors.textPrimary)
                        }

                        GlassButton(
                            onClick = onImportPlaylistClick,
                            modifier = Modifier.weight(1f).height(40.dp),
                            variant = GlassButtonVariant.PRIMARY,
                            shape = RoundedCornerShape(FlinsTheme.radii.pill),
                            testTag = "playlists_tab_import_button"
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import", style = FlinsTheme.typography.label, color = Color.Black)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (playlists.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No playlists crafted",
                            subtitle = "Create a custom sanctuary or import playlists from Spotify & YouTube Music.",
                            icon = Icons.Default.Add,
                            actionButtonText = "Import Playlist",
                            onActionClick = onImportPlaylistClick
                        )
                    }
                } else {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(playlists, key = { it.id }) { pl ->
                                PlaylistCard(
                                    playlist = pl,
                                    onClick = { onPlaylistClick(pl) }
                                )
                            }
                        }
                    }
                }
            }
            "Albums" -> {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(albums, key = { it.id }) { alb ->
                            AlbumCard(
                                album = alb,
                                onClick = { onAlbumClick(alb) }
                            )
                        }
                    }
                }
            }
            "Artists" -> {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(artists, key = { it.id }) { art ->
                            ArtistCard(
                                artist = art,
                                onClick = { onArtistClick(art) }
                            )
                        }
                    }
                }
            }
            "Downloads" -> {
                item {
                    EmptyState(
                        title = "Downloads Ready",
                        subtitle = "Offline listening architecture is ready. Download tracks from any album view.",
                        icon = Icons.Default.DownloadDone
                    )
                }
            }
            "Recently Played" -> {
                if (recentlyPlayed.isEmpty()) {
                    item {
                        EmptyState(
                            title = "History is clear",
                            subtitle = "Tracks you play will flow into this timeline.",
                            icon = Icons.Default.History
                        )
                    }
                } else {
                    items(recentlyPlayed, key = { it.id }) { track ->
                        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                            SongRow(
                                track = track,
                                onTrackClick = { onTrackClick(track, recentlyPlayed) },
                                isCurrentPlaying = track.id == currentPlayingTrack?.id,
                                isLiked = likedTrackIds.contains(track.id),
                                onLikeToggle = { onLikeToggle(track.id) },
                                onMoreClick = { onTrackOptionsClick(track) },
                                onArtistClick = { artistName ->
                                    val foundArtist = artists.find { it.id == track.artistId || it.name.equals(artistName, ignoreCase = true) }
                                        ?: Artist(id = if (track.artistId.isNotBlank() && track.artistId != "art_1") track.artistId else "art_${artistName.lowercase().replace(" ", "_")}", name = artistName, artwork = track.artwork)
                                    onArtistClick(foundArtist)
                                },
                                onAlbumClick = { albumTitle ->
                                    val foundAlbum = albums.find {
                                        (track.albumId.isNotBlank() && !track.albumId.startsWith("alb_") && it.id == track.albumId) ||
                                        it.title.equals(albumTitle, ignoreCase = true) ||
                                        it.title.equals(track.album, ignoreCase = true)
                                    } ?: Album(
                                        id = if (track.albumId.isNotBlank() && !track.albumId.startsWith("alb_")) track.albumId else "alb_${(albumTitle.ifBlank { track.album }).lowercase().replace(" ", "_")}",
                                        title = if (track.album.isNotBlank()) track.album else albumTitle,
                                        artist = track.artist,
                                        artistId = track.artistId,
                                        artwork = track.artwork,
                                        bannerArtwork = track.bannerArtwork
                                    )
                                    onAlbumClick(foundAlbum)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
