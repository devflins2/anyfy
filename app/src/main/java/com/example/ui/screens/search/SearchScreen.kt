package com.example.ui.screens.search

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.ui.components.LoadingSkeleton
import com.example.ui.components.PlaylistCard
import com.example.ui.components.SearchBar
import com.example.ui.components.SectionHeader
import com.example.ui.components.SongRow
import com.example.ui.theme.FlinsTheme

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedFilter: String,
    onFilterChange: (String) -> Unit,
    recentSearches: List<String>,
    onSelectRecentSearch: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    allTracks: List<Track>,
    allArtists: List<Artist>,
    allAlbums: List<Album>,
    likedTrackIds: Set<String>,
    currentPlayingTrack: Track?,
    onTrackClick: (Track, List<Track>) -> Unit,
    onLikeToggle: (String) -> Unit,
    onTrackOptionsClick: (Track) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
    searchResults: List<Track> = emptyList(),
    isSearching: Boolean = false,
    // Advanced Music Search 2.0 Parameters
    searchArtists: List<Artist> = emptyList(),
    searchAlbums: List<Album> = emptyList(),
    searchPlaylists: List<Playlist> = emptyList(),
    onPlaylistClick: (Playlist) -> Unit = {},
    selectedSort: String = "Relevance",
    onSortChange: (String) -> Unit = {},
    suggestions: List<String> = emptyList(),
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    hasMoreResults: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {}
) {
    val filters = listOf("All", "Songs", "Artists", "Albums", "Playlists")
    val sortOptions = listOf("Relevance", "A-Z", "Recently Released")

    // Filter tracks dynamically: use live remote searchResults if present, or search in allTracks
    val filteredTracks = remember(query, searchResults, allTracks, selectedSort) {
        if (query.isBlank()) emptyList() else {
            val list = if (searchResults.isNotEmpty()) {
                searchResults
            } else {
                val q = query.trim().lowercase()
                allTracks.filter {
                    it.id.startsWith("yt_") ||
                    it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.genre.lowercase().contains(q)
                }
            }.distinctBy { it.id }
            if (selectedSort == "A-Z") list.sortedBy { it.title.lowercase() } else list
        }
    }

    // Filter artists: use searchArtists if present, or search allArtists
    val filteredArtists = remember(query, searchArtists, allArtists, selectedSort) {
        if (query.isBlank()) emptyList() else {
            val list = if (searchArtists.isNotEmpty()) {
                searchArtists
            } else {
                val q = query.trim().lowercase()
                allArtists.filter { it.name.lowercase().contains(q) || it.bio.lowercase().contains(q) }
            }.distinctBy { it.id }
            if (selectedSort == "A-Z") list.sortedBy { it.name.lowercase() } else list
        }
    }

    // Filter albums: use searchAlbums if present, or search allAlbums
    val filteredAlbums = remember(query, searchAlbums, allAlbums, selectedSort) {
        if (query.isBlank()) emptyList() else {
            val list = if (searchAlbums.isNotEmpty()) {
                searchAlbums
            } else {
                val q = query.trim().lowercase()
                allAlbums.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
            }.distinctBy { it.id }
            when (selectedSort) {
                "A-Z" -> list.sortedBy { it.title.lowercase() }
                "Recently Released" -> list.sortedByDescending { it.releaseYear.toIntOrNull() ?: 0 }
                else -> list
            }
        }
    }

    // Filter playlists: use searchPlaylists
    val filteredPlaylists = remember(query, searchPlaylists, selectedSort) {
        if (query.isBlank()) emptyList() else {
            val list = searchPlaylists.distinctBy { it.id }
            when (selectedSort) {
                "A-Z" -> list.sortedBy { it.name.lowercase() }
                "Recently Released" -> list.sortedByDescending { it.createdAt }
                else -> list
            }
        }
    }

    val hasAnyResult = filteredTracks.isNotEmpty() || filteredArtists.isNotEmpty() ||
            filteredAlbums.isNotEmpty() || filteredPlaylists.isNotEmpty()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Search Bar & Filter Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "DISCOVER",
                    style = FlinsTheme.typography.overline,
                    color = FlinsTheme.colors.textMuted
                )
                Text(
                    text = "Explore Music",
                    style = FlinsTheme.typography.hero,
                    color = FlinsTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                SearchBar(
                    query = query,
                    onQueryChange = onQueryChange
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Multi-Category Filters
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters, key = { it }) { filter ->
                        val isSelected = filter == selectedFilter
                        val bg = if (isSelected) Color(0xFF454545) else Color(0xFF202020)
                        val textCol = if (isSelected) Color(0xFFF0F0F0) else FlinsTheme.colors.textSecondary

                        Box(
                            modifier = Modifier
                                .testTag("filter_chip_$filter")
                                .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                .background(bg)
                                .clickable { onFilterChange(filter) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = filter,
                                style = FlinsTheme.typography.label,
                                color = textCol
                            )
                        }
                    }
                }

                // Sorting Row (when search results are active)
                if (query.isNotBlank() && hasAnyResult) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort order",
                            tint = FlinsTheme.colors.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        sortOptions.forEach { sortOption ->
                            val isSelected = sortOption == selectedSort
                            val bg = if (isSelected) Color(0xFF383838) else Color(0xFF181818)
                            val textCol = if (isSelected) Color(0xFFF0F0F0) else FlinsTheme.colors.textMuted

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(bg)
                                    .clickable { onSortChange(sortOption) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = sortOption,
                                    style = FlinsTheme.typography.metadata,
                                    color = textCol
                                )
                            }
                        }
                    }
                }
            }
        }

        // When query is blank: show recent searches, trending discoveries, and genre landscapes
        if (query.isBlank()) {
            // Recent Searches
            if (recentSearches.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RECENT SEARCHES",
                            style = FlinsTheme.typography.overline,
                            color = FlinsTheme.colors.textMuted
                        )
                        Text(
                            text = "Clear",
                            style = FlinsTheme.typography.metadata,
                            color = Color(0xFFF0F0F0),
                            modifier = Modifier
                                .clickable(onClick = onClearRecentSearches)
                                .padding(4.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        recentSearches.forEach { recent ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectRecentSearch(recent) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = FlinsTheme.colors.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = recent,
                                    style = FlinsTheme.typography.body,
                                    color = FlinsTheme.colors.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onRemoveRecentSearch(recent) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove recent",
                                        tint = FlinsTheme.colors.textMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Trending Searches
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(
                    overline = "Trending",
                    title = "Popular Searches"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val trending = listOf("Arijit Singh", "The Weeknd", "Diljit Dosanjh", "Taylor Swift", "Trending Hits", "Synthwave", "Lo-Fi")
                    items(trending, key = { "trend_$it" }) { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                .background(Color(0xFF202020))
                                .border(0.5.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.pill))
                                .clickable { onQueryChange(tag) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = tag,
                                style = FlinsTheme.typography.label,
                                color = Color(0xFFF0F0F0)
                            )
                        }
                    }
                }
            }

            // Quick Genre Suggestions (Browse Landscapes)
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(
                    overline = "Atmosphere",
                    title = "Browse Landscapes"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val genres = listOf("Ambient", "Synthwave", "Future Bass", "Downtempo", "Lo-Fi", "Cyber Bass", "Bollywood Romantic")
                    items(genres, key = { "genre_$it" }) { g ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(FlinsTheme.radii.md))
                                .background(Color(0xFF202020))
                                .clickable { onQueryChange(g) }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = g,
                                style = FlinsTheme.typography.titleMedium,
                                color = FlinsTheme.colors.textPrimary
                            )
                        }
                    }
                }
            }
        } else {
            // Smart Suggestions (while typing or searching)
            if (suggestions.isNotEmpty() && !isSearching) {
                item {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(suggestions, key = { "sug_$it" }) { suggestion ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                    .background(Color(0xFF282828))
                                    .clickable { onQueryChange(suggestion) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = FlinsTheme.colors.textMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = suggestion,
                                    style = FlinsTheme.typography.metadata,
                                    color = FlinsTheme.colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }

            // Error State
            if (errorMessage != null && !hasAnyResult) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .clip(RoundedCornerShape(FlinsTheme.radii.lg))
                            .background(Color(0xFF202020))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.lg))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Search error",
                            tint = FlinsTheme.colors.textMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = errorMessage,
                            style = FlinsTheme.typography.body,
                            color = FlinsTheme.colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        GlassButton(
                            onClick = onRetry
                        ) {
                            Text(
                                text = "Retry Search",
                                style = FlinsTheme.typography.label,
                                color = Color(0xFFF0F0F0)
                            )
                        }
                    }
                }
            } else if (isSearching) {
                item {
                    LoadingSkeleton(itemCount = 6)
                }
            } else if (!hasAnyResult) {
                item {
                    EmptyState(
                        title = "No echoes found",
                        subtitle = "Try adjusting keywords or selecting another category filter.",
                        icon = Icons.Default.Search
                    )
                }
            } else {
                // Multi-Category Search Results

                // 1. Matching Songs
                if ((selectedFilter == "All" || selectedFilter == "Songs") && filteredTracks.isNotEmpty()) {
                    item(key = "header_songs") {
                        SectionHeader(
                            overline = "Tracks (${filteredTracks.size})",
                            title = "Songs"
                        )
                    }
                    items(filteredTracks, key = { "search_trk_${it.id}" }) { track ->
                        val searchUpcomingContext = remember(track, filteredTracks) {
                            val idx = filteredTracks.indexOfFirst { it.id == track.id }
                            if (idx >= 0) {
                                filteredTracks.subList(idx, filteredTracks.size)
                            } else {
                                listOf(track)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp)
                        ) {
                            SongRow(
                                track = track,
                                onTrackClick = { onTrackClick(track, searchUpcomingContext) },
                                isCurrentPlaying = track.id == currentPlayingTrack?.id,
                                isLiked = likedTrackIds.contains(track.id),
                                onLikeToggle = { onLikeToggle(track.id) },
                                onMoreClick = { onTrackOptionsClick(track) },
                                onArtistClick = { artistName ->
                                    val foundArtist = (searchArtists + allArtists).find { it.id == track.artistId || it.name.equals(artistName, ignoreCase = true) }
                                        ?: Artist(id = track.artistId.ifBlank { "art_${artistName.lowercase().replace(" ", "_")}" }, name = artistName, artwork = track.artwork)
                                    onArtistClick(foundArtist)
                                },
                                onAlbumClick = { albumTitle ->
                                    val foundAlbum = (searchAlbums + allAlbums).find {
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

                // 2. Matching Artists
                if ((selectedFilter == "All" || selectedFilter == "Artists") && filteredArtists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(
                            overline = "Artists (${filteredArtists.size})",
                            title = "Artists"
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(filteredArtists, key = { it.id }) { artist ->
                                ArtistCard(
                                    artist = artist,
                                    onClick = { onArtistClick(artist) }
                                )
                            }
                        }
                    }
                }

                // 3. Matching Albums
                if ((selectedFilter == "All" || selectedFilter == "Albums") && filteredAlbums.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(
                            overline = "Releases (${filteredAlbums.size})",
                            title = "Albums"
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(filteredAlbums, key = { it.id }) { album ->
                                AlbumCard(
                                    album = album,
                                    onClick = { onAlbumClick(album) }
                                )
                            }
                        }
                    }
                }

                // 4. Matching Playlists
                if ((selectedFilter == "All" || selectedFilter == "Playlists") && filteredPlaylists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(
                            overline = "Playlists (${filteredPlaylists.size})",
                            title = "Playlists"
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(filteredPlaylists, key = { it.id }) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist) }
                                )
                            }
                        }
                    }
                }

                // 5. Continuation / Load More Indicator
                if (hasMoreResults) {
                    item(key = "load_more_footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoadingMore) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = FlinsTheme.colors.textPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Loading more YouTube Music echoes...",
                                        style = FlinsTheme.typography.metadata,
                                        color = FlinsTheme.colors.textMuted
                                    )
                                }
                            } else {
                                GlassButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                ) {
                                    Text(
                                        text = "Load More Results",
                                        style = FlinsTheme.typography.label,
                                        color = Color(0xFFF0F0F0)
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
