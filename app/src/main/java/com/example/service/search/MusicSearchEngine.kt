package com.example.service.search

import com.example.data.repository.MusicRepository
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.Track
import com.example.model.UnifiedSearchResult
import com.example.service.importer.normalizer.TrackNormalizer
import com.example.service.integrations.LiveYouTubeService
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.YtMusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MusicSearchEngine(
    private val musicRepository: MusicRepository,
    private val ytMusicService: YtMusicService = YtMusicService.getInstance(),
    private val youTubeService: YouTubeService = LiveYouTubeService(),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        val POPULAR_TAGS = listOf(
            "Arijit Singh", "The Weeknd", "Diljit Dosanjh", "Imagine Dragons",
            "Taylor Swift", "Coldplay", "Trending Hits", "Synthwave", "Lo-Fi",
            "Bollywood Romantic", "Punjabi Pop", "EDM", "Ambient"
        )
    }

    /**
     * Executes intelligent multi-category search across local catalog and remote public sources.
     */
    suspend fun search(
        query: String,
        userId: String = "guest",
        category: com.example.model.SearchCategory = com.example.model.SearchCategory.ALL
    ): UnifiedSearchResult = withContext(ioDispatcher) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext UnifiedSearchResult(
                query = "",
                suggestions = POPULAR_TAGS.take(6),
                category = category
            )
        }

        val normalizedQuery = TrackNormalizer.stripAccents(cleanQuery.lowercase())

        // 1. If a specific filtered category is requested (Songs, Albums, Artists, Playlists),
        // query InnerTube with the genuine category search filter parameters
        if (category != com.example.model.SearchCategory.ALL) {
            return@withContext coroutineScope {
                when (category) {
                    com.example.model.SearchCategory.SONGS -> {
                        val localTracksAsync = async {
                            val known = musicRepository.getAllKnownTracks()
                            scoreAndFilterTracks(normalizedQuery, known)
                        }
                        val ytFilteredAsync = async {
                            try {
                                withTimeoutOrNull(10000L) {
                                    ytMusicService.searchWithFilter(cleanQuery, com.example.model.SearchCategory.SONGS).getOrNull()
                                }
                            } catch (_: Exception) { null }
                        }
                        val localTracks = localTracksAsync.await()
                        val ytResult = ytFilteredAsync.await()
                        val remoteTracks = ytResult?.tracks?.map { it.toTrack() } ?: emptyList()
                        val remoteTrackIds = remoteTracks.map { it.id }.toSet()
                        val scoredTracks = rankAndDeduplicateTracks(normalizedQuery, localTracks + remoteTracks, remoteTrackIds)
                        if (scoredTracks.isNotEmpty()) {
                            musicRepository.registerTracks(scoredTracks)
                        }
                        UnifiedSearchResult(
                            query = cleanQuery,
                            tracks = scoredTracks,
                            continuationToken = ytResult?.continuationToken,
                            category = category,
                            isFromCache = false
                        )
                    }
                    com.example.model.SearchCategory.ALBUMS -> {
                        val localAlbums = musicRepository.albums.filter { it.title.lowercase().contains(normalizedQuery) || it.artist.lowercase().contains(normalizedQuery) }
                        val ytResult = try {
                            withTimeoutOrNull(10000L) {
                                ytMusicService.searchWithFilter(cleanQuery, com.example.model.SearchCategory.ALBUMS).getOrNull()
                            }
                        } catch (_: Exception) { null }
                        val remoteAlbums = ytResult?.albums?.map { it.toAlbum() } ?: emptyList()
                        val remoteAlbumIds = remoteAlbums.map { it.id }.toSet()
                        val scoredAlbums = rankAndDeduplicateAlbums(normalizedQuery, localAlbums + remoteAlbums, remoteAlbumIds)
                        UnifiedSearchResult(
                            query = cleanQuery,
                            albums = scoredAlbums,
                            continuationToken = ytResult?.continuationToken,
                            category = category,
                            isFromCache = false
                        )
                    }
                    com.example.model.SearchCategory.ARTISTS -> {
                        val localArtists = musicRepository.artists.filter { it.name.lowercase().contains(normalizedQuery) }
                        val ytResult = try {
                            withTimeoutOrNull(10000L) {
                                ytMusicService.searchWithFilter(cleanQuery, com.example.model.SearchCategory.ARTISTS).getOrNull()
                            }
                        } catch (_: Exception) { null }
                        val remoteArtists = ytResult?.artists?.map { it.toArtist() } ?: emptyList()
                        val remoteArtistIds = remoteArtists.map { it.id }.toSet()
                        val scoredArtists = rankAndDeduplicateArtists(normalizedQuery, localArtists + remoteArtists, remoteArtistIds)
                        UnifiedSearchResult(
                            query = cleanQuery,
                            artists = scoredArtists,
                            continuationToken = ytResult?.continuationToken,
                            category = category,
                            isFromCache = false
                        )
                    }
                    com.example.model.SearchCategory.PLAYLISTS -> {
                        val localPlaylists = try {
                            musicRepository.getUserPlaylistsDirect(userId).filter { it.name.lowercase().contains(normalizedQuery) }
                        } catch (_: Exception) { emptyList() }
                        val ytResult = try {
                            withTimeoutOrNull(10000L) {
                                ytMusicService.searchWithFilter(cleanQuery, com.example.model.SearchCategory.PLAYLISTS).getOrNull()
                            }
                        } catch (_: Exception) { null }
                        val remotePlaylists = ytResult?.playlists?.map { it.toPlaylist() } ?: emptyList()
                        val remotePlaylistIds = remotePlaylists.map { it.id }.toSet()
                        val scoredPlaylists = rankAndDeduplicatePlaylists(normalizedQuery, localPlaylists + remotePlaylists, remotePlaylistIds)
                        UnifiedSearchResult(
                            query = cleanQuery,
                            playlists = scoredPlaylists,
                            continuationToken = ytResult?.continuationToken,
                            category = category,
                            isFromCache = false
                        )
                    }
                    else -> UnifiedSearchResult(query = cleanQuery, category = category)
                }
            }
        }

        // 2. Default multi-category "All" search
        coroutineScope {
            // 1. Local catalog & known tracks matching (instant)
            val localTracksAsync = async {
                val known = musicRepository.getAllKnownTracks()
                scoreAndFilterTracks(normalizedQuery, known)
            }

            // 2. Local user playlists
            val localPlaylistsAsync = async {
                try {
                    val userPlaylists = musicRepository.getUserPlaylistsDirect(userId)
                    scoreAndFilterPlaylists(normalizedQuery, userPlaylists)
                } catch (_: Exception) {
                    emptyList()
                }
            }

            // 3. YouTube Music InnerTube multi-category search (single source of truth)
            val ytSearchAsync = async {
                try {
                    withTimeoutOrNull(10000L) {
                        val result = ytMusicService.search(cleanQuery)
                        if (result.isSuccess) result.getOrNull() else null
                    }
                } catch (_: Exception) {
                    null
                }
            }

            // 3b. YouTube Music filtered songs search for maximum catalogue depth
            val ytSongSearchAsync = async {
                try {
                    withTimeoutOrNull(8000L) {
                        val result = ytMusicService.searchTracks(cleanQuery)
                        if (result.isSuccess) result.getOrNull() else null
                    }
                } catch (_: Exception) {
                    null
                }
            }

            // 4. Remote live suggestions
            val ytSuggestionsAsync = async {
                try {
                    withTimeoutOrNull(3000L) {
                        val res = ytMusicService.getSearchSuggestions(cleanQuery)
                        if (res.isSuccess) res.getOrNull() else null
                    }
                } catch (_: Exception) {
                    null
                }
            }

            val localTracks = localTracksAsync.await()
            val localPlaylists = localPlaylistsAsync.await()
            val ytResult = ytSearchAsync.await()
            val ytSongTracks = ytSongSearchAsync.await() ?: emptyList()
            val remoteSuggestions = ytSuggestionsAsync.await() ?: emptyList()

            // Map and score artists
            val catalogArtists = musicRepository.artists
            val remoteYtArtists = ytResult?.artists?.map { it.toArtist() } ?: emptyList()
            val remoteArtistIds = remoteYtArtists.map { it.id }.toSet()
            val scoredArtists = rankAndDeduplicateArtists(normalizedQuery, catalogArtists + remoteYtArtists, remoteArtistIds)

            // When searching for an artist, resolve their full catalogue tracks and discography
            val topArtistMatch = scoredArtists.firstOrNull()
            val artistTracksExtra = if (topArtistMatch != null &&
                topArtistMatch.id.startsWith("UC") &&
                (topArtistMatch.name.lowercase().contains(normalizedQuery) || normalizedQuery.contains(topArtistMatch.name.lowercase()))
            ) {
                try {
                    val artistDetail = ytMusicService.getArtist(topArtistMatch.id).getOrNull()
                    val artistTracks = (artistDetail?.topTracks ?: emptyList()).map { it.toTrack() }
                    val artistVideos = (artistDetail?.videos ?: emptyList()).map { it.toTrack() }
                    artistTracks + artistVideos
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            // Map and score tracks with guaranteed YouTube Music relevance preservation
            val remoteYtTracks = ytResult?.tracks?.map { it.toTrack() } ?: emptyList()
            val remoteSongOnlyTracks = ytSongTracks.map { it.toTrack() }
            val combinedRemoteTracks = (remoteYtTracks + remoteSongOnlyTracks).distinctBy { it.id }
            val remoteTrackIds = combinedRemoteTracks.map { it.id }.toSet()

            val allCandidateTracks = (localTracks + combinedRemoteTracks + artistTracksExtra)
            val scoredTracks = rankAndDeduplicateTracks(normalizedQuery, allCandidateTracks, remoteTrackIds)

            // Register newly discovered tracks into repository cache for offline playback
            if (scoredTracks.isNotEmpty()) {
                musicRepository.registerTracks(scoredTracks)
            }

            // Map and score albums
            val catalogAlbums = musicRepository.albums
            val remoteYtAlbums = ytResult?.albums?.map { it.toAlbum() } ?: emptyList()
            val remoteAlbumIds = remoteYtAlbums.map { it.id }.toSet()
            val scoredAlbums = rankAndDeduplicateAlbums(normalizedQuery, catalogAlbums + remoteYtAlbums, remoteAlbumIds)

            // Map and score playlists
            val remoteYtPlaylists = ytResult?.playlists?.map { it.toPlaylist() } ?: emptyList()
            val remotePlaylistIds = remoteYtPlaylists.map { it.id }.toSet()
            val scoredPlaylists = rankAndDeduplicatePlaylists(normalizedQuery, localPlaylists + remoteYtPlaylists, remotePlaylistIds)

            // Generate smart search suggestions
            val localSuggestions = generateSuggestions(cleanQuery, scoredTracks, scoredArtists, scoredAlbums)
            val mergedSuggestions = (remoteSuggestions + localSuggestions).distinct().take(10)

            UnifiedSearchResult(
                query = cleanQuery,
                tracks = scoredTracks,
                artists = scoredArtists,
                albums = scoredAlbums,
                playlists = scoredPlaylists,
                suggestions = mergedSuggestions,
                continuationToken = ytResult?.continuationToken,
                category = category,
                isFromCache = false
            )
        }
    }

    /**
     * Executes continuation search pagination to append the next page of results.
     */
    suspend fun searchContinuation(
        continuationToken: String,
        category: com.example.model.SearchCategory = com.example.model.SearchCategory.ALL
    ): UnifiedSearchResult = withContext(ioDispatcher) {
        if (continuationToken.isBlank()) return@withContext UnifiedSearchResult()
        try {
            val contResult = ytMusicService.searchContinuation(continuationToken).getOrNull()
                ?: return@withContext UnifiedSearchResult()
            val domainTracks = contResult.tracks.map { it.toTrack() }
            if (domainTracks.isNotEmpty()) {
                musicRepository.registerTracks(domainTracks)
            }
            UnifiedSearchResult(
                query = "",
                tracks = domainTracks,
                albums = contResult.albums.map { it.toAlbum() },
                artists = contResult.artists.map { it.toArtist() },
                playlists = contResult.playlists.map { it.toPlaylist() },
                continuationToken = contResult.continuationToken,
                category = category,
                isFromCache = false
            )
        } catch (_: Exception) {
            UnifiedSearchResult()
        }
    }

    /**
     * Generates real-time suggestions based on user query prefix, catalog entities, and tags.
     */
    fun generateSuggestions(
        query: String,
        tracks: List<Track> = emptyList(),
        artists: List<Artist> = emptyList(),
        albums: List<Album> = emptyList(),
        recentSearches: List<String> = emptyList()
    ): List<String> {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return recentSearches.take(6).ifEmpty { POPULAR_TAGS.take(6) }

        val suggestions = linkedSetOf<String>()

        // 1. Matching recent searches
        recentSearches.filter { it.lowercase().contains(clean) }.forEach { suggestions.add(it) }

        // 2. Matching artists
        artists.map { it.name }.filter { it.lowercase().contains(clean) }.take(3).forEach { suggestions.add(it) }

        // 3. Matching track titles
        tracks.map { it.title }.filter { it.lowercase().contains(clean) }.take(4).forEach { suggestions.add(it) }

        // 4. Matching album titles
        albums.map { it.title }.filter { it.lowercase().contains(clean) }.take(2).forEach { suggestions.add(it) }

        // 5. Popular tags matching prefix
        POPULAR_TAGS.filter { it.lowercase().contains(clean) }.take(3).forEach { suggestions.add(it) }

        return suggestions.take(8).toList()
    }

    // =========================================================================
    // SCORING, RANKING & TYPO TOLERANCE
    // =========================================================================

    fun computeLevenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    fun computeTypoSimilarity(s1: String, s2: String): Float {
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        if (s1 == s2) return 1.0f
        val maxLen = maxOf(s1.length, s2.length)
        val dist = computeLevenshteinDistance(s1, s2)
        return (1.0f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    private fun rankAndDeduplicateTracks(query: String, candidates: List<Track>, remoteIds: Set<String> = emptySet()): List<Track> {
        val uniqueCandidates = candidates.distinctBy { it.id }
        val qClean = TrackNormalizer.simplifyForComparison(query)

        // Remote tracks from YouTube Music are authoritative
        val remoteTracks = uniqueCandidates.filter { remoteIds.contains(it.id) }
        val localTracks = uniqueCandidates.filter { !remoteIds.contains(it.id) }

        // Local candidates scored
        val scoredLocal = localTracks.map { it to computeTrackScore(query, it) }
            .filter { it.second > 0.35f }
            .sortedByDescending { it.second }
            .map { it.first }

        // For remote tracks from YouTube Music: YouTube Music's ranking is authoritative and already handles
        // semantic matching (e.g. lyrical query "Sun Mere Humsafar" resolving to official track "Humsafar").
        // We preserve YouTube Music's ranking order directly.
        val prioritizedRemote = remoteTracks

        val combined = (prioritizedRemote + scoredLocal).distinctBy { it.id }

        // If candidates are only local tracks (e.g. offline search or unit tests with empty remoteIds),
        // ensure exact matches rank first according to score
        if (remoteIds.isEmpty()) {
            return combined.sortedByDescending { computeTrackScore(query, it) }
        }

        return combined
    }

    fun computeTrackScore(query: String, track: Track): Float {
        val q = TrackNormalizer.simplifyForComparison(query)
        val title = TrackNormalizer.simplifyForComparison(track.title)
        val artist = TrackNormalizer.simplifyForComparison(track.artist)
        val album = TrackNormalizer.simplifyForComparison(track.album)
        val combined = "$title $artist"

        // Exact matches
        if (title == q) return 1.0f
        if (combined == q || "$artist $title" == q) return 0.98f
        if (artist == q) return 0.92f

        // Prefix matches
        if (title.startsWith(q)) return 0.90f
        if (artist.startsWith(q)) return 0.86f

        // Substring / word boundary matches
        if (title.contains(q) || q.contains(title)) return 0.82f
        if (artist.contains(q) || q.contains(artist)) return 0.78f
        if (combined.contains(q) || q.contains(combined)) return 0.75f
        if (album.contains(q)) return 0.65f

        // Typo tolerance / edit distance similarity
        val titleTypo = computeTypoSimilarity(q, title)
        val artistTypo = computeTypoSimilarity(q, artist)
        val maxTypo = maxOf(titleTypo, artistTypo)
        if (maxTypo >= 0.65f) {
            return 0.50f + (maxTypo * 0.25f)
        }

        // Token overlap fallback
        val tokenSim = maxOf(
            TrackNormalizer.computeSimilarity(q, title),
            TrackNormalizer.computeSimilarity(q, artist)
        )
        if (tokenSim >= 0.60f) {
            return 0.50f + (tokenSim * 0.20f)
        }

        return 0.0f
    }

    private fun rankAndDeduplicateArtists(query: String, candidates: List<Artist>, remoteIds: Set<String> = emptySet()): List<Artist> {
        val q = TrackNormalizer.simplifyForComparison(query)
        val unique = candidates.distinctBy { it.id }
        val remote = unique.filter { remoteIds.contains(it.id) }
        val local = unique.filter { !remoteIds.contains(it.id) }

        val scoredLocal = local.map { it to computeTypoSimilarity(q, TrackNormalizer.simplifyForComparison(it.name)) }
            .filter { it.second > 0.35f }
            .sortedByDescending { it.second }
            .map { it.first }

        val prioritizedRemote = remote

        val combined = (prioritizedRemote + scoredLocal).distinctBy { it.id }
        if (remoteIds.isEmpty()) {
            return combined.sortedByDescending {
                val name = TrackNormalizer.simplifyForComparison(it.name)
                if (name == q) 1.0f else computeTypoSimilarity(q, name)
            }
        }
        return combined
    }

    private fun rankAndDeduplicateAlbums(query: String, candidates: List<Album>, remoteIds: Set<String> = emptySet()): List<Album> {
        val q = TrackNormalizer.simplifyForComparison(query)
        val unique = candidates.distinctBy { it.id }
        val remote = unique.filter { remoteIds.contains(it.id) }
        val local = unique.filter { !remoteIds.contains(it.id) }

        val scoredLocal = local.map { it to computeTypoSimilarity(q, TrackNormalizer.simplifyForComparison(it.title)) }
            .filter { it.second > 0.35f }
            .sortedByDescending { it.second }
            .map { it.first }

        val prioritizedRemote = remote

        val combined = (prioritizedRemote + scoredLocal).distinctBy { it.id }
        if (remoteIds.isEmpty()) {
            return combined.sortedByDescending {
                val title = TrackNormalizer.simplifyForComparison(it.title)
                if (title == q) 1.0f else computeTypoSimilarity(q, title)
            }
        }
        return combined
    }

    private fun rankAndDeduplicatePlaylists(query: String, candidates: List<Playlist>, remoteIds: Set<String> = emptySet()): List<Playlist> {
        val q = TrackNormalizer.simplifyForComparison(query)
        val unique = candidates.distinctBy { it.id }
        val remote = unique.filter { remoteIds.contains(it.id) }
        val local = unique.filter { !remoteIds.contains(it.id) }

        val scoredLocal = local.map { it to computeTypoSimilarity(q, TrackNormalizer.simplifyForComparison(it.name)) }
            .filter { it.second > 0.35f }
            .sortedByDescending { it.second }
            .map { it.first }

        val prioritizedRemote = remote

        val combined = (prioritizedRemote + scoredLocal).distinctBy { it.id }
        if (remoteIds.isEmpty()) {
            return combined.sortedByDescending {
                val name = TrackNormalizer.simplifyForComparison(it.name)
                if (name == q) 1.0f else computeTypoSimilarity(q, name)
            }
        }
        return combined
    }

    private fun scoreAndFilterTracks(query: String, tracks: List<Track>): List<Track> {
        return tracks.filter { computeTrackScore(query, it) > 0.30f }
    }

    private fun scoreAndFilterPlaylists(query: String, playlists: List<Playlist>): List<Playlist> {
        val q = TrackNormalizer.simplifyForComparison(query)
        return playlists.filter {
            val name = TrackNormalizer.simplifyForComparison(it.name)
            name.contains(q) || q.contains(name) || it.description.lowercase().contains(q)
        }
    }
}
