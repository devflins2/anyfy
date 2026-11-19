package com.example.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.Track
import com.example.ui.components.EditPlaylistBottomSheet
import com.example.ui.components.EmptyState
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassButtonVariant
import com.example.ui.components.GlassIconButton
import com.example.ui.components.SectionHeader
import com.example.ui.components.SongDetailsDialog
import com.example.ui.components.SongRow
import com.example.ui.components.TrackOptionsBottomSheet
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsError
import com.example.ui.theme.FlinsTheme
import com.example.viewmodel.RemovedTrackInfo

enum class PlaylistSortOrder(val label: String) {
    CUSTOM("Custom Order"),
    RECENTLY_ADDED("Recently Added"),
    TITLE("Song Title (A-Z)"),
    ARTIST("Artist Name (A-Z)")
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    allTracks: List<Track>,
    likedTrackIds: Set<String>,
    currentPlayingTrack: Track?,
    onTrackClick: (Track, List<Track>) -> Unit,
    onLikeToggle: (String) -> Unit,
    onTrackOptionsClick: (Track) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onUpdatePlaylist: ((id: String, name: String, description: String, artwork: String) -> Unit)? = null,
    onRemoveTrack: ((Track) -> Unit)? = null,
    onRemoveMultipleTracks: ((List<String>) -> Unit)? = null,
    onReorderTracks: ((List<String>) -> Unit)? = null,
    onPlayNext: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    onAddTrackToPlaylist: ((String, Track) -> Unit)? = null,
    undoRemovalState: RemovedTrackInfo? = null,
    onUndoRemove: (() -> Unit)? = null,
    onDismissUndo: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onBack: () -> Unit,
    onArtistClick: ((Artist) -> Unit)? = null,
    onAlbumClick: ((Album) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    // Modal and dialog states
    var isEditingDetails by remember { mutableStateOf(false) }
    var isDeleteConfirmationOpen by remember { mutableStateOf(false) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedTrackIds = remember { mutableStateListOf<String>() }
    var isConfirmMultiRemoveOpen by remember { mutableStateOf(false) }
    var isReorderMode by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(PlaylistSortOrder.CUSTOM) }
    var isSortMenuOpen by remember { mutableStateOf(false) }
    var isAddSongsSheetOpen by remember { mutableStateOf(false) }
    var viewingTrackDetails by remember { mutableStateOf<Track?>(null) }
    var activeContextTrack by remember { mutableStateOf<Track?>(null) }

    // Map playlist track IDs to tracks
    val playlistTracks = remember(playlist.trackIds, allTracks) {
        playlist.trackIds.map { id ->
            allTracks.find { it.id == id }
                ?: Track(
                    id = id,
                    title = "Saved Song",
                    artist = "Unknown Artist",
                    artistId = "",
                    album = playlist.name,
                    albumId = playlist.id,
                    artwork = playlist.artwork,
                    durationSeconds = 0,
                    audioUrl = null
                )
        }
    }

    // Sorted and filtered tracks
    val displayedTracks = remember(playlistTracks, sortOrder, searchQuery) {
        val sorted = when (sortOrder) {
            PlaylistSortOrder.CUSTOM -> playlistTracks
            PlaylistSortOrder.RECENTLY_ADDED -> playlistTracks.asReversed()
            PlaylistSortOrder.TITLE -> playlistTracks.sortedBy { it.title.lowercase() }
            PlaylistSortOrder.ARTIST -> playlistTracks.sortedBy { it.artist.lowercase() }
        }
        if (searchQuery.isBlank()) {
            sorted
        } else {
            val q = searchQuery.trim().lowercase()
            sorted.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
    }

    // Total duration calculation
    val totalDurationSeconds = remember(playlistTracks) {
        playlistTracks.sumOf { it.durationSeconds }
    }
    val formattedDuration = remember(totalDurationSeconds) {
        val hours = totalDurationSeconds / 3600
        val minutes = (totalDurationSeconds % 3600) / 60
        val seconds = totalDurationSeconds % 60
        when {
            hours > 0 -> "${hours} hr ${minutes} min"
            minutes > 0 -> "${minutes} min ${seconds} sec"
            else -> "${seconds} sec"
        }
    }

    // Delete Playlist Confirmation Dialog
    if (isDeleteConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { isDeleteConfirmationOpen = false },
            title = {
                Text(
                    text = "Delete Playlist?",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete '${playlist.name}'? All songs in this playlist will remain in your library.",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleteConfirmationOpen = false
                        onDeletePlaylist(playlist.id)
                    }
                ) {
                    Text("Delete", color = FlinsError, style = FlinsTheme.typography.button)
                }
            },
            dismissButton = {
                TextButton(onClick = { isDeleteConfirmationOpen = false }) {
                    Text("Cancel", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
                }
            },
            containerColor = FlinsDarkBg,
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Multi-Remove Confirmation Dialog
    if (isConfirmMultiRemoveOpen) {
        val count = selectedTrackIds.size
        AlertDialog(
            onDismissRequest = { isConfirmMultiRemoveOpen = false },
            title = {
                Text(
                    text = "Remove Songs?",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Remove $count ${if (count == 1) "song" else "songs"} from '${playlist.name}'? This will not delete them from your library or Liked Songs.",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmMultiRemoveOpen = false
                        val toRemove = selectedTrackIds.toList()
                        selectedTrackIds.clear()
                        isMultiSelectMode = false
                        onRemoveMultipleTracks?.invoke(toRemove)
                    }
                ) {
                    Text("Remove", color = FlinsError, style = FlinsTheme.typography.button)
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmMultiRemoveOpen = false }) {
                    Text("Cancel", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
                }
            },
            containerColor = FlinsDarkBg,
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Edit Playlist Bottom Sheet
    if (isEditingDetails && onUpdatePlaylist != null) {
        EditPlaylistBottomSheet(
            currentName = playlist.name,
            currentDescription = playlist.description,
            currentArtwork = playlist.artwork,
            onSave = { name, desc, art ->
                onUpdatePlaylist(playlist.id, name, desc, art)
            },
            onDismiss = { isEditingDetails = false }
        )
    }

    // Song Details Dialog
    if (viewingTrackDetails != null) {
        SongDetailsDialog(
            track = viewingTrackDetails!!,
            onDismiss = { viewingTrackDetails = null }
        )
    }

    // Context Menu Bottom Sheet
    if (activeContextTrack != null) {
        val track = activeContextTrack!!
        TrackOptionsBottomSheet(
            track = track,
            isLiked = likedTrackIds.contains(track.id),
            onLikeToggle = { onLikeToggle(track.id) },
            onPlayNext = { onPlayNext?.invoke(track) },
            onAddToQueue = { onAddToQueue?.invoke(track) },
            onAddToPlaylist = { onTrackOptionsClick(track) },
            onGoToArtist = { /* Handled via main navigation if needed */ },
            onGoToAlbum = { /* Handled via main navigation if needed */ },
            onRemoveFromPlaylist = { onRemoveTrack?.invoke(track) },
            onViewDetails = { viewingTrackDetails = track },
            onDismiss = { activeContextTrack = null }
        )
    }

    // Add Songs Picker Bottom Sheet
    if (isAddSongsSheetOpen) {
        AddSongsPickerSheet(
            allTracks = allTracks,
            playlist = playlist,
            onAddTrack = { trackToAdd ->
                onAddTrackToPlaylist?.invoke(playlist.id, trackToAdd)
            },
            onDismiss = { isAddSongsSheetOpen = false }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 150.dp)
        ) {
            // Top Navigation / Action Bar
            item {
                if (isMultiSelectMode) {
                    // Multi-Select Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF202020))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassIconButton(
                                onClick = {
                                    isMultiSelectMode = false
                                    selectedTrackIds.clear()
                                },
                                size = 40.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel Selection",
                                    tint = Color(0xFFC0C0C0),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "${selectedTrackIds.size} selected",
                                style = FlinsTheme.typography.titleMedium,
                                color = Color(0xFFF0F0F0)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    if (selectedTrackIds.size == displayedTracks.size) {
                                        selectedTrackIds.clear()
                                    } else {
                                        selectedTrackIds.clear()
                                        selectedTrackIds.addAll(displayedTracks.map { it.id })
                                    }
                                }
                            ) {
                                Text(
                                    text = if (selectedTrackIds.size == displayedTracks.size) "Clear" else "Select All",
                                    color = FlinsTheme.colors.textPrimary,
                                    style = FlinsTheme.typography.button
                                )
                            }

                            if (selectedTrackIds.isNotEmpty()) {
                                GlassIconButton(
                                    onClick = { isConfirmMultiRemoveOpen = true },
                                    size = 40.dp
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove Selected",
                                        tint = FlinsError,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Standard Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassIconButton(
                            onClick = onBack,
                            size = 40.dp
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Search Toggle
                            GlassIconButton(
                                onClick = {
                                    isSearchActive = !isSearchActive
                                    if (!isSearchActive) searchQuery = ""
                                },
                                size = 40.dp
                            ) {
                                Icon(
                                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = if (isSearchActive) Color(0xFFF0F0F0) else Color(0xFFC0C0C0),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Refresh Playlist Button
                            if (onRefresh != null) {
                                GlassIconButton(
                                    onClick = onRefresh,
                                    size = 40.dp
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh from YouTube Music",
                                        tint = Color(0xFFC0C0C0),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Edit Details Button
                            GlassIconButton(
                                onClick = { isEditingDetails = true },
                                size = 40.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Playlist",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Multi-Select Toggle
                            if (playlistTracks.isNotEmpty()) {
                                GlassIconButton(
                                    onClick = {
                                        isMultiSelectMode = true
                                        isReorderMode = false
                                    },
                                    size = 40.dp
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Select Multiple",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Delete Playlist Button
                            GlassIconButton(
                                onClick = { isDeleteConfirmationOpen = true },
                                size = 40.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Playlist",
                                    tint = FlinsError,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // In-Playlist Search Bar if open
            if (isSearchActive && !isMultiSelectMode) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(FlinsTheme.radii.md))
                            .background(Color(0xFF282828))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color(0xFFC0C0C0),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search in playlist...",
                                        style = FlinsTheme.typography.bodyMedium,
                                        color = FlinsTheme.colors.textMuted
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = FlinsTheme.typography.body.copy(color = FlinsTheme.colors.textPrimary),
                                    cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                                    singleLine = true
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = FlinsTheme.colors.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Playlist Header: Cover, Title, Description, Stats & Action Buttons
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(190.dp)
                            .clip(RoundedCornerShape(FlinsTheme.radii.xl))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(FlinsTheme.radii.xl))
                    ) {
                        AsyncImage(
                            model = playlist.artwork.ifBlank {
                                playlistTracks.firstOrNull()?.artwork ?: ""
                            },
                            contentDescription = playlist.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = playlist.name,
                        style = FlinsTheme.typography.title,
                        color = FlinsTheme.colors.textPrimary
                    )
                    if (playlist.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = playlist.description,
                            style = FlinsTheme.typography.bodyMedium,
                            color = FlinsTheme.colors.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${playlistTracks.size} ${if (playlistTracks.size == 1) "song" else "songs"} • $formattedDuration",
                        style = FlinsTheme.typography.metadata,
                        color = FlinsCyan
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    if (playlistTracks.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassButton(
                                onClick = {
                                    if (displayedTracks.isNotEmpty()) {
                                        onTrackClick(displayedTracks.first(), displayedTracks)
                                    }
                                },
                                variant = GlassButtonVariant.PRIMARY
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = FlinsDarkBg,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play All", style = FlinsTheme.typography.button, color = FlinsDarkBg)
                            }

                            GlassIconButton(
                                onClick = {
                                    if (displayedTracks.isNotEmpty()) {
                                        val shuffled = displayedTracks.shuffled()
                                        onTrackClick(shuffled.first(), shuffled)
                                    }
                                },
                                size = 46.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle Play",
                                    tint = Color(0xFFC0C0C0),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Playlist Content Controls: Filter/Sort Bar, Add Songs, Reorder toggle
            item {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        overline = "Tracks (${displayedTracks.size})",
                        title = if (searchQuery.isNotBlank()) "Search Results" else sortOrder.label
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Reorder toggle (available in Custom Order)
                        if (sortOrder == PlaylistSortOrder.CUSTOM && searchQuery.isBlank() && playlistTracks.size > 1 && !isMultiSelectMode) {
                            GlassIconButton(
                                onClick = { isReorderMode = !isReorderMode },
                                size = 36.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = "Reorder tracks",
                                    tint = if (isReorderMode) Color(0xFFF0F0F0) else Color(0xFFC0C0C0),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Add Songs Button
                        GlassIconButton(
                            onClick = { isAddSongsSheetOpen = true },
                            size = 36.dp
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add songs to playlist",
                                tint = FlinsCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Sort Menu Button
                        Box {
                            GlassIconButton(
                                onClick = { isSortMenuOpen = true },
                                size = 36.dp
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Sort Options",
                                    tint = FlinsTheme.colors.textPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = isSortMenuOpen,
                                onDismissRequest = { isSortMenuOpen = false },
                                modifier = Modifier.background(FlinsDarkBg)
                            ) {
                                PlaylistSortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = order.label,
                                                color = if (sortOrder == order) Color(0xFFF0F0F0) else FlinsTheme.colors.textPrimary,
                                                style = FlinsTheme.typography.bodyMedium
                                            )
                                        },
                                        onClick = {
                                            sortOrder = order
                                            isSortMenuOpen = false
                                            if (order != PlaylistSortOrder.CUSTOM) {
                                                isReorderMode = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Playlist Tracks List or Empty State
            if (displayedTracks.isEmpty()) {
                item {
                    if (playlistTracks.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            EmptyState(
                                title = "Playlist is empty",
                                subtitle = "Add your favorite tracks to start curating your mix."
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            GlassButton(
                                onClick = { isAddSongsSheetOpen = true },
                                variant = GlassButtonVariant.PRIMARY
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = FlinsDarkBg,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Songs", style = FlinsTheme.typography.button, color = FlinsDarkBg)
                            }
                        }
                    } else {
                        EmptyState(
                            title = "No matching songs",
                            subtitle = "No tracks found matching '$searchQuery' in this playlist."
                        )
                    }
                }
            } else {
                itemsIndexed(displayedTracks, key = { _, track -> "${playlist.id}_${track.id}" }) { index, track ->
                    val isSelected = selectedTrackIds.contains(track.id)
                    val isUnavailable = track.audioUrl.isNullOrBlank() && (track.id.startsWith("unavail") || track.durationSeconds <= 0)

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(FlinsTheme.radii.md))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            if (isSelected) selectedTrackIds.remove(track.id)
                                            else selectedTrackIds.add(track.id)
                                        } else {
                                            onTrackClick(track, displayedTracks)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isMultiSelectMode) {
                                            activeContextTrack = track
                                        }
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Multi-Select Checkbox
                            if (isMultiSelectMode) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedTrackIds.add(track.id)
                                        else selectedTrackIds.remove(track.id)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFFBDBDBD),
                                        uncheckedColor = Color(0xFF383838),
                                        checkmarkColor = Color(0xFF101010)
                                    )
                                )
                            }

                            // Reorder Controls (when in reorder mode)
                            if (isReorderMode && sortOrder == PlaylistSortOrder.CUSTOM && searchQuery.isBlank()) {
                                Column(
                                    modifier = Modifier.padding(end = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val mutable = displayedTracks.toMutableList()
                                                val item = mutable.removeAt(index)
                                                mutable.add(index - 1, item)
                                                onReorderTracks?.invoke(mutable.map { it.id })
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowUpward,
                                            contentDescription = "Move Up",
                                            tint = if (index > 0) Color(0xFFF0F0F0) else Color(0xFF707070),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index < displayedTracks.size - 1) {
                                                val mutable = displayedTracks.toMutableList()
                                                val item = mutable.removeAt(index)
                                                mutable.add(index + 1, item)
                                                onReorderTracks?.invoke(mutable.map { it.id })
                                            }
                                        },
                                        enabled = index < displayedTracks.size - 1,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDownward,
                                            contentDescription = "Move Down",
                                            tint = if (index < displayedTracks.size - 1) Color(0xFFF0F0F0) else Color(0xFF707070),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Song Row
                            Box(modifier = Modifier.weight(1f)) {
                                SongRow(
                                    track = track,
                                    onTrackClick = {
                                        if (isMultiSelectMode) {
                                            if (isSelected) selectedTrackIds.remove(track.id)
                                            else selectedTrackIds.add(track.id)
                                        } else {
                                            onTrackClick(track, displayedTracks)
                                        }
                                    },
                                    isCurrentPlaying = track.id == currentPlayingTrack?.id,
                                    isLiked = likedTrackIds.contains(track.id),
                                    onLikeToggle = { onLikeToggle(track.id) },
                                    onMoreClick = {
                                        activeContextTrack = track
                                    },
                                    onArtistClick = if (onArtistClick != null) { { artistName ->
                                        onArtistClick.invoke(Artist(id = "art_${artistName.lowercase().replace(" ", "_")}", name = artistName, artwork = track.artwork))
                                    } } else null,
                                    onAlbumClick = if (onAlbumClick != null) { { albumTitle ->
                                        onAlbumClick.invoke(
                                            Album(
                                                id = if (track.albumId.isNotBlank() && !track.albumId.startsWith("alb_")) track.albumId else "alb_${(albumTitle.ifBlank { track.album }).lowercase().replace(" ", "_")}",
                                                title = if (track.album.isNotBlank()) track.album else albumTitle,
                                                artist = track.artist,
                                                artistId = track.artistId,
                                                artwork = track.artwork,
                                                bannerArtwork = track.bannerArtwork
                                            )
                                        )
                                    } } else null
                                )

                                // Unavailable Song Badge
                                if (isUnavailable) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 8.dp, end = 44.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(FlinsError.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Unavailable",
                                            style = FlinsTheme.typography.metadata,
                                            color = FlinsError
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Undo Removal Banner
        AnimatedVisibility(
            visible = undoRemovalState != null && undoRemovalState.playlistId == playlist.id,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp)
        ) {
            undoRemovalState?.let { undoInfo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FlinsTheme.radii.lg))
                        .background(Color(0xF0181818))
                        .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.lg))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Removed from playlist",
                            style = FlinsTheme.typography.metadata,
                            color = FlinsTheme.colors.textMuted
                        )
                        Text(
                            text = undoInfo.track.title,
                            style = FlinsTheme.typography.bodyMedium,
                            color = FlinsTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onUndoRemove?.invoke() }
                        ) {
                            Text(
                                text = "UNDO",
                                color = Color(0xFFF0F0F0),
                                style = FlinsTheme.typography.button
                            )
                        }
                        IconButton(
                            onClick = { onDismissUndo?.invoke() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss undo",
                                tint = FlinsTheme.colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Picker bottom sheet for adding songs to the playlist from all available songs,
 * with search and duplicate song detection with Skip/Keep Existing options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSongsPickerSheet(
    allTracks: List<Track>,
    playlist: Playlist,
    onAddTrack: (Track) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var duplicateTrackPrompt by remember { mutableStateOf<Track?>(null) }

    // Duplicate Song Confirmation Dialog
    if (duplicateTrackPrompt != null) {
        val dupTrack = duplicateTrackPrompt!!
        AlertDialog(
            onDismissRequest = { duplicateTrackPrompt = null },
            title = {
                Text(
                    text = "Duplicate Song",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "'${dupTrack.title}' is already in '${playlist.name}'. Do you want to skip or keep the existing song?",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        duplicateTrackPrompt = null
                    }
                ) {
                    Text("Keep Existing", color = Color(0xFFF0F0F0), style = FlinsTheme.typography.button)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { duplicateTrackPrompt = null }
                ) {
                    Text("Skip", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
                }
            },
            containerColor = FlinsDarkBg,
            shape = RoundedCornerShape(24.dp)
        )
    }

    val filteredTracks = remember(allTracks, query) {
        if (query.isBlank()) allTracks
        else {
            val q = query.trim().lowercase()
            allTracks.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
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
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Add Songs to '${playlist.name}'",
                style = FlinsTheme.typography.title,
                color = FlinsTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Search input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(FlinsTheme.radii.md))
                    .background(Color(0xFF282828))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.md))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFFC0C0C0),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search tracks to add...",
                                style = FlinsTheme.typography.bodyMedium,
                                color = FlinsTheme.colors.textMuted
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = FlinsTheme.typography.body.copy(color = FlinsTheme.colors.textPrimary),
                            cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                            singleLine = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                items(filteredTracks, key = { it.id }) { track ->
                    val isAlreadyIn = playlist.trackIds.contains(track.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                        ) {
                            AsyncImage(
                                model = track.artwork,
                                contentDescription = track.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = FlinsTheme.typography.body,
                                color = FlinsTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${track.artist} • ${track.album}",
                                style = FlinsTheme.typography.metadata,
                                color = FlinsTheme.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (isAlreadyIn) {
                            IconButton(
                                onClick = { duplicateTrackPrompt = track }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Already in playlist",
                                    tint = Color(0xFFC0C0C0),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { onAddTrack(track) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add to playlist",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
