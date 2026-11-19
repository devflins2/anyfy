package com.example.model

/**
 * Supported search categories for Multi-Category Search.
 */
enum class SearchCategory(val label: String) {
    ALL("All"),
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    PLAYLISTS("Playlists");

    companion object {
        fun fromLabel(label: String): SearchCategory {
            return entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: ALL
        }
    }
}

/**
 * Supported sorting options for search results.
 */
enum class SearchSortOrder(val label: String) {
    RELEVANCE("Relevance"),
    ALPHABETICAL("A-Z"),
    RECENT("Recently Released");

    companion object {
        fun fromLabel(label: String): SearchSortOrder {
            return entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: RELEVANCE
        }
    }
}

/**
 * Unified multi-category search result model.
 */
data class UnifiedSearchResult(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val continuationToken: String? = null,
    val category: SearchCategory = SearchCategory.ALL,
    val isFromCache: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()

    fun filterByCategory(category: SearchCategory): UnifiedSearchResult {
        return when (category) {
            SearchCategory.ALL -> this
            SearchCategory.SONGS -> copy(artists = emptyList(), albums = emptyList(), playlists = emptyList())
            SearchCategory.ARTISTS -> copy(tracks = emptyList(), albums = emptyList(), playlists = emptyList())
            SearchCategory.ALBUMS -> copy(tracks = emptyList(), artists = emptyList(), playlists = emptyList())
            SearchCategory.PLAYLISTS -> copy(tracks = emptyList(), artists = emptyList(), albums = emptyList())
        }
    }

    fun applySorting(sortOrder: SearchSortOrder): UnifiedSearchResult {
        return when (sortOrder) {
            SearchSortOrder.RELEVANCE -> this // Preserves intelligent relevance ranking
            SearchSortOrder.ALPHABETICAL -> copy(
                tracks = tracks.sortedBy { it.title.lowercase() },
                artists = artists.sortedBy { it.name.lowercase() },
                albums = albums.sortedBy { it.title.lowercase() },
                playlists = playlists.sortedBy { it.name.lowercase() }
            )
            SearchSortOrder.RECENT -> copy(
                albums = albums.sortedByDescending { it.releaseYear.toIntOrNull() ?: 0 },
                playlists = playlists.sortedByDescending { it.createdAt }
            )
        }
    }
}

