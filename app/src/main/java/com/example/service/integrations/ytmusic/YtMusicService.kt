/*
 * Copyright (C) 2024–2026 Nguyễn Đức Tuấn Minh (maxrave-dev) <ndtminh2608@gmail.com>
 * Portions adapted from SimpMusic (commit f037043d714450bac5aaac2f9dbf77e17b019303)
 * and maxrave-dev/core (commit 7d11cf437e59ba4f348ec92f7558732ee3d34c1a).
 *
 * Licensed under the GNU General Public License Version 3.0 (GPL-3.0-only).
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *     https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Modified for FlinsMusic on 2026-10-19:
 * Service facade providing public YouTube Music metadata with safe fallbacks and bounded timeouts.
 */

package com.example.service.integrations.ytmusic

interface YtMusicService {
    suspend fun search(query: String): Result<YtSearchResult>
    suspend fun searchTracks(query: String): Result<List<YtTrack>> = runCatching { search(query).getOrNull()?.tracks ?: emptyList() }
    suspend fun searchWithFilter(query: String, filter: com.example.model.SearchCategory): Result<YtSearchResult> =
        search(query)
    suspend fun searchContinuation(continuationToken: String): Result<YtSearchResult> =
        Result.success(YtSearchResult())
    suspend fun getHomeShelves(): Result<List<YtHomeShelf>>
    suspend fun getArtist(browseId: String): Result<YtArtist>
    suspend fun getAlbum(browseId: String): Result<YtAlbum>
    suspend fun getAlbum(
        browseId: String,
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackArtwork: String = ""
    ): Result<YtAlbum> = getAlbum(browseId)
    suspend fun getPlaylist(playlistId: String, continuationToken: String? = null): Result<YtPlaylist>

    suspend fun getLikedSongs(continuationToken: String? = null): Result<YtPlaylist> =
        Result.success(YtPlaylist(id = "FLLM", title = "Liked Music"))

    suspend fun getUserPlaylists(continuationToken: String? = null): Result<List<YtPlaylist>> =
        Result.success(emptyList())

    suspend fun getUserSavedAlbums(continuationToken: String? = null): Result<List<YtAlbum>> =
        Result.success(emptyList())

    suspend fun getUserFollowedArtists(continuationToken: String? = null): Result<List<YtArtist>> =
        Result.success(emptyList())

    suspend fun getUserHistory(): Result<List<YtTrack>> =
        Result.success(emptyList())

    suspend fun getSearchSuggestions(query: String): Result<List<String>> =
        Result.success(emptyList())

    suspend fun getRadioTracks(videoId: String): Result<List<YtTrack>> =
        Result.success(emptyList())

    suspend fun createPlaylist(title: String, description: String = "", privacyStatus: String = "PRIVATE"): Result<String> =
        Result.success("")

    suspend fun editPlaylist(playlistId: String, newTitle: String? = null, newDescription: String? = null): Result<Boolean> =
        Result.success(true)

    suspend fun addPlaylistItem(playlistId: String, videoId: String): Result<Boolean> =
        Result.success(true)

    suspend fun addPlaylistItems(playlistId: String, videoIds: List<String>): Result<Boolean> =
        Result.success(true)

    suspend fun removePlaylistItem(playlistId: String, videoId: String, setVideoId: String? = null): Result<Boolean> =
        Result.success(true)

    suspend fun removePlaylistItems(playlistId: String, videoIds: List<String>): Result<Boolean> =
        Result.success(true)

    suspend fun reorderPlaylistItem(playlistId: String, setVideoId: String, successorSetVideoId: String? = null): Result<Boolean> =
        Result.success(true)

    suspend fun deletePlaylist(playlistId: String): Result<Boolean> =
        Result.success(true)

    fun invalidatePlaylistCache(playlistId: String? = null) {}
    fun isUserAuthenticated(): Boolean = false

    suspend fun likeSong(videoId: String): Result<Boolean> = Result.success(true)
    suspend fun unlikeSong(videoId: String): Result<Boolean> = Result.success(true)
    suspend fun likePlaylist(playlistId: String): Result<Boolean> = Result.success(true)
    suspend fun unlikePlaylist(playlistId: String): Result<Boolean> = Result.success(true)
    suspend fun subscribeArtist(channelId: String): Result<Boolean> = Result.success(true)
    suspend fun unsubscribeArtist(channelId: String): Result<Boolean> = Result.success(true)

    suspend fun reportPlaybackStart(videoId: String, cpn: String): Result<Boolean> = Result.success(true)
    suspend fun reportPlaybackProgress(videoId: String, cpn: String, currentMediaTimeSec: Float, state: String = "playing"): Result<Boolean> = Result.success(true)
    suspend fun reportPlaybackCompletion(videoId: String, cpn: String, durationSec: Float): Result<Boolean> = Result.success(true)

    companion object {
        @Volatile
        private var instance: YtMusicService? = null

        fun getInstance(client: YtMusicClient? = null): YtMusicService {
            return instance ?: synchronized(this) {
                instance ?: DefaultYtMusicService(client ?: createDefaultClient()).also { instance = it }
            }
        }

        private fun createDefaultClient(): YtMusicClient {
            return YtMusicClient(authHeaderProvider = {
                com.example.FlinsApplication.instance?.let {
                    YtAccountManager.getInstance(it).getAuthHeaders()
                } ?: emptyMap()
            })
        }
    }
}

class DefaultYtMusicService(
    private val client: YtMusicClient = YtMusicClient(authHeaderProvider = {
        com.example.FlinsApplication.instance?.let {
            YtAccountManager.getInstance(it).getAuthHeaders()
        } ?: emptyMap()
    })
) : YtMusicService {

    override suspend fun search(query: String): Result<YtSearchResult> = runCatching {
        if (query.isBlank()) return@runCatching YtSearchResult()
        val json = client.search(query)
        val initialResult = YtMusicParser.parseSearchResponse(json)

        // Consume continuation token to fetch up to 3 additional pages (~75-100 tracks/items)
        var continuation = initialResult.continuationToken
        val accumulatedTracks = initialResult.tracks.toMutableList()
        val accumulatedAlbums = initialResult.albums.toMutableList()
        val accumulatedArtists = initialResult.artists.toMutableList()
        val accumulatedPlaylists = initialResult.playlists.toMutableList()
        val seenTrackIds = accumulatedTracks.map { it.id }.toMutableSet()
        val seenAlbumIds = accumulatedAlbums.map { it.id }.toMutableSet()
        val seenArtistIds = accumulatedArtists.map { it.id }.toMutableSet()
        val seenPlaylistIds = accumulatedPlaylists.map { it.id }.toMutableSet()

        var page = 0
        while (!continuation.isNullOrBlank() && page < 3) {
            page++
            try {
                val contJson = client.continueSearch(continuation)
                val contResult = YtMusicContinuationParser.parseSearchContinuation(contJson)
                for (t in contResult.tracks) {
                    if (seenTrackIds.add(t.id)) accumulatedTracks.add(t)
                }
                for (a in contResult.albums) {
                    if (seenAlbumIds.add(a.id)) accumulatedAlbums.add(a)
                }
                for (ar in contResult.artists) {
                    if (seenArtistIds.add(ar.id)) accumulatedArtists.add(ar)
                }
                for (p in contResult.playlists) {
                    if (seenPlaylistIds.add(p.id)) accumulatedPlaylists.add(p)
                }
                continuation = contResult.continuationToken
            } catch (_: Exception) {
                break
            }
        }

        initialResult.copy(
            tracks = accumulatedTracks,
            albums = accumulatedAlbums,
            artists = accumulatedArtists,
            playlists = accumulatedPlaylists,
            continuationToken = continuation
        )
    }

    override suspend fun searchTracks(query: String): Result<List<YtTrack>> = runCatching {
        if (query.isBlank()) return@runCatching emptyList()
        val json = client.search(query, YtMusicClient.FILTER_SONGS)
        val initialResult = YtMusicParser.parseSearchResponse(json)

        val accumulatedTracks = initialResult.tracks.toMutableList()
        val seenTrackIds = accumulatedTracks.map { it.id }.toMutableSet()
        var continuation = initialResult.continuationToken

        var page = 0
        while (!continuation.isNullOrBlank() && page < 3) {
            page++
            try {
                val contJson = client.continueSearch(continuation)
                val contResult = YtMusicContinuationParser.parseSearchContinuation(contJson)
                val newTracks = contResult.tracks
                if (newTracks.isEmpty()) break
                for (t in newTracks) {
                    if (seenTrackIds.add(t.id)) accumulatedTracks.add(t)
                }
                continuation = contResult.continuationToken
            } catch (_: Exception) {
                break
            }
        }

        accumulatedTracks
    }

    override suspend fun searchWithFilter(query: String, filter: com.example.model.SearchCategory): Result<YtSearchResult> = runCatching {
        if (query.isBlank()) return@runCatching YtSearchResult()
        val filterParam = when (filter) {
            com.example.model.SearchCategory.ALL -> null
            com.example.model.SearchCategory.SONGS -> YtMusicClient.FILTER_SONGS
            com.example.model.SearchCategory.ALBUMS -> YtMusicClient.FILTER_ALBUMS
            com.example.model.SearchCategory.ARTISTS -> YtMusicClient.FILTER_ARTISTS
            com.example.model.SearchCategory.PLAYLISTS -> YtMusicClient.FILTER_PLAYLISTS
        }
        val json = client.search(query, filterParam)
        YtMusicParser.parseSearchResponse(json)
    }

    override suspend fun searchContinuation(continuationToken: String): Result<YtSearchResult> = runCatching {
        if (continuationToken.isBlank()) return@runCatching YtSearchResult()
        val contJson = client.continueSearch(continuationToken)
        YtMusicContinuationParser.parseSearchContinuation(contJson)
    }

    override suspend fun getHomeShelves(): Result<List<YtHomeShelf>> = runCatching {
        // Query public explore / home browse
        val json = client.browse("FEmusic_home")
        var shelves = YtMusicParser.parseHomeResponse(json)
        if (shelves.isEmpty()) {
            val exploreJson = client.browse("FEmusic_explore")
            shelves = YtMusicParser.parseHomeResponse(exploreJson)
        }
        shelves
    }

    override suspend fun getArtist(browseId: String): Result<YtArtist> = runCatching {
        val cleanId = browseId.trim()
        val json = client.browse(cleanId)
        val initialArtist = YtMusicParser.parseArtistResponse(json, cleanId)

        // Complete Tracks Fetching:
        // If songsBrowseEndpoint is available, fetch all tracks through songs page & pagination
        val allTracks = if (initialArtist.songsBrowseEndpoint != null) {
            val (epBrowseId, epParams) = initialArtist.songsBrowseEndpoint
            val fetchedTracks = fetchCompleteTracks(epBrowseId, epParams)
            if (fetchedTracks.isNotEmpty()) fetchedTracks else initialArtist.topTracks
        } else {
            initialArtist.topTracks
        }

        // Complete Discography Fetching:
        // If albumsBrowseEndpoint is available, fetch all albums through discography page & pagination
        val allAlbums = if (initialArtist.albumsBrowseEndpoint != null) {
            val (epBrowseId, epParams) = initialArtist.albumsBrowseEndpoint
            fetchCompleteDiscography(epBrowseId, epParams, initialArtist.name, isSingle = false)
        } else {
            initialArtist.albums
        }

        // If singlesBrowseEndpoint is available, fetch all singles through discography page & pagination
        val allSingles = if (initialArtist.singlesBrowseEndpoint != null) {
            val (epBrowseId, epParams) = initialArtist.singlesBrowseEndpoint
            fetchCompleteDiscography(epBrowseId, epParams, initialArtist.name, isSingle = true)
        } else {
            initialArtist.singles
        }

        // Merge and deduplicate albums (by id or title+year)
        val mergedAlbums = mergeAlbums(initialArtist.albums, allAlbums)
        val mergedSingles = mergeAlbums(initialArtist.singles, allSingles)
        val mergedTracks = (initialArtist.topTracks + allTracks).distinctBy { it.id }

        initialArtist.copy(
            topTracks = mergedTracks,
            albums = mergedAlbums,
            singles = mergedSingles
        )
    }

    override suspend fun getAlbum(browseId: String): Result<YtAlbum> =
        getAlbum(browseId, "", "", "")

    override suspend fun getAlbum(
        browseId: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackArtwork: String
    ): Result<YtAlbum> = runCatching {
        val cleanId = browseId.trim()
        val json = client.browse(cleanId)
        var album = YtMusicParser.parseAlbumResponse(json, cleanId, fallbackTitle, fallbackArtist, fallbackArtwork)
        // If album has pagination continuation token for tracklist, fetch remaining tracks
        val contToken = YtMusicParser.extractAlbumContinuationToken(json)
        if (!contToken.isNullOrBlank()) {
            try {
                val contJson = client.continueBrowse(contToken)
                val (pagedTracks, _) = YtMusicParser.parseContinuationTracks(contJson)
                if (pagedTracks.isNotEmpty()) {
                    val combinedTracks = (album.tracks + pagedTracks).distinctBy { it.id }
                    album = album.copy(
                        tracks = combinedTracks,
                        trackCount = combinedTracks.size
                    )
                }
            } catch (_: Exception) {}
        }
        album
    }

    private suspend fun fetchCompleteDiscography(
        browseId: String,
        params: String?,
        artistName: String,
        isSingle: Boolean
    ): List<YtAlbum> {
        val resultAlbums = mutableListOf<YtAlbum>()
        val seenIds = mutableSetOf<String>()

        try {
            val pageJson = client.browse(browseId, params)
            var (pageAlbums, nextCont) = YtMusicParser.parseDiscographyPage(pageJson, artistName, isSingle)

            for (alb in pageAlbums) {
                val key = alb.id.ifBlank { "${alb.title}_${alb.year}" }
                if (seenIds.add(key)) {
                    resultAlbums.add(alb)
                }
            }

            var maxPages = 10 // safe bound to prevent infinite pagination loops
            while (!nextCont.isNullOrBlank() && maxPages > 0) {
                maxPages--
                val contJson = client.continueBrowse(nextCont)
                val (nextAlbums, cont) = YtMusicParser.parseDiscographyPage(contJson, artistName, isSingle)
                if (nextAlbums.isEmpty() && cont == null) break
                for (alb in nextAlbums) {
                    val key = alb.id.ifBlank { "${alb.title}_${alb.year}" }
                    if (seenIds.add(key)) {
                        resultAlbums.add(alb)
                    }
                }
                nextCont = cont
            }
        } catch (_: Exception) {}

        return resultAlbums
    }

    private suspend fun fetchCompleteTracks(
        browseId: String,
        params: String?
    ): List<YtTrack> {
        val resultTracks = mutableListOf<YtTrack>()
        val seenIds = mutableSetOf<String>()

        try {
            val pageJson = client.browse(browseId, params)
            var (pageTracks, nextCont) = YtMusicContinuationParser.parseTracksPage(pageJson)

            for (track in pageTracks) {
                if (seenIds.add(track.id)) {
                    resultTracks.add(track)
                }
            }

            var maxPages = 5 // fetch up to ~150 tracks
            while (!nextCont.isNullOrBlank() && maxPages > 0) {
                maxPages--
                val contJson = client.continueBrowse(nextCont)
                val (nextTracks, cont) = YtMusicContinuationParser.parseTracksPage(contJson)
                if (nextTracks.isEmpty() && cont == null) break
                for (track in nextTracks) {
                    if (seenIds.add(track.id)) {
                        resultTracks.add(track)
                    }
                }
                nextCont = cont
            }
        } catch (_: Exception) {}

        return resultTracks
    }

    private fun mergeAlbums(existing: List<YtAlbum>, fetched: List<YtAlbum>): List<YtAlbum> {
        if (fetched.isEmpty()) return existing
        val map = LinkedHashMap<String, YtAlbum>()
        for (alb in existing) {
            val key = alb.id.ifBlank { "${alb.title}_${alb.year}" }
            map[key] = alb
        }
        for (alb in fetched) {
            val key = alb.id.ifBlank { "${alb.title}_${alb.year}" }
            map[key] = alb
        }
        return map.values.toList()
    }

    override suspend fun getPlaylist(playlistId: String, continuationToken: String?): Result<YtPlaylist> = runCatching {
        val cleanId = playlistId.trim()
        if (!continuationToken.isNullOrBlank()) {
            val contJson = client.continueBrowse(continuationToken)
            val (pagedTracks, nextCont) = YtMusicParser.parseContinuationTracks(contJson)
            return@runCatching YtPlaylist(
                id = cleanId,
                title = "Playlist",
                tracks = pagedTracks,
                continuationToken = nextCont
            )
        }

        val browseId = when {
            cleanId.startsWith("VL") || cleanId.startsWith("FL") || cleanId.startsWith("FE") -> cleanId
            else -> "VL$cleanId"
        }
        val json = client.browse(browseId)
        YtMusicParser.parsePlaylistResponse(json, cleanId)
    }

    override suspend fun getLikedSongs(continuationToken: String?): Result<YtPlaylist> = runCatching {
        var playlist = getPlaylist("VLLM", continuationToken).getOrNull()
        if (playlist == null || playlist.tracks.isEmpty()) {
            playlist = getPlaylist("FLLM", continuationToken).getOrNull()
        }
        if (playlist == null || playlist.tracks.isEmpty()) {
            playlist = getPlaylist("LM", continuationToken).getOrNull()
        }
        playlist ?: YtPlaylist(id = "VLLM", title = "Liked Music")
    }

    override suspend fun getUserPlaylists(continuationToken: String?): Result<List<YtPlaylist>> = runCatching {
        val json = client.browse("FEmusic_liked_playlists")
        YtMusicParser.parseLibraryPlaylists(json)
    }

    override suspend fun getUserSavedAlbums(continuationToken: String?): Result<List<YtAlbum>> = runCatching {
        var json = client.browse("FEmusic_library_corpus_albums")
        var albums = YtMusicParser.parseLibraryAlbums(json)
        if (albums.isEmpty()) {
            json = client.browse("FEmusic_library_albums")
            albums = YtMusicParser.parseLibraryAlbums(json)
        }
        albums
    }

    override suspend fun getUserFollowedArtists(continuationToken: String?): Result<List<YtArtist>> = runCatching {
        var json = client.browse("FEmusic_library_corpus_track_artists")
        var artists = YtMusicParser.parseLibraryArtists(json)
        if (artists.isEmpty()) {
            json = client.browse("FEmusic_library_artists")
            artists = YtMusicParser.parseLibraryArtists(json)
        }
        artists
    }

    override suspend fun getUserHistory(): Result<List<YtTrack>> = runCatching {
        val json = client.browse("FEmusic_history")
        val tracks = YtMusicParser.parseHistoryResponse(json)
        if (tracks.isEmpty()) {
            val playlist = YtMusicParser.parsePlaylistResponse(json, "history")
            playlist.tracks
        } else {
            tracks
        }
    }

    override suspend fun getSearchSuggestions(query: String): Result<List<String>> = runCatching {
        if (query.isBlank()) return@runCatching emptyList()
        val json = client.getSearchSuggestions(query)
        YtMusicParser.parseSearchSuggestions(json)
    }

    override suspend fun getRadioTracks(videoId: String): Result<List<YtTrack>> = runCatching {
        val cleanVid = videoId.removePrefix("yt_").trim()
        val json = client.next(cleanVid)
        YtMusicParser.parseRadioTracks(json)
    }

    override suspend fun createPlaylist(title: String, description: String, privacyStatus: String): Result<String> = runCatching {
        if (!client.isAuthenticated()) {
            throw IllegalStateException("YouTube Music account authentication required")
        }
        val id = client.createPlaylist(title, description, privacyStatus)
        if (id.isBlank()) {
            throw java.io.IOException("YouTube Music failed to create playlist '$title'")
        }
        id
    }

    override suspend fun editPlaylist(playlistId: String, newTitle: String?, newDescription: String?): Result<Boolean> = runCatching {
        if (!client.isAuthenticated()) {
            throw IllegalStateException("YouTube Music account authentication required")
        }
        val ok = client.editPlaylist(playlistId, newTitle, newDescription)
        if (!ok) {
            throw java.io.IOException("YouTube Music failed to update playlist")
        }
        true
    }

    override suspend fun addPlaylistItem(playlistId: String, videoId: String): Result<Boolean> = runCatching {
        if (!client.isAuthenticated()) {
            throw IllegalStateException("YouTube Music account authentication required")
        }
        val ok = client.addPlaylistItem(playlistId, videoId)
        if (!ok) {
            throw java.io.IOException("YouTube Music failed to add song to playlist")
        }
        true
    }

    override suspend fun addPlaylistItems(playlistId: String, videoIds: List<String>): Result<Boolean> = runCatching {
        if (!client.isAuthenticated()) {
            throw IllegalStateException("YouTube Music account authentication required")
        }
        val ok = client.addPlaylistItems(playlistId, videoIds)
        if (!ok) {
            throw java.io.IOException("YouTube Music failed to add songs to playlist")
        }
        true
    }

    override suspend fun removePlaylistItem(playlistId: String, videoId: String, setVideoId: String?): Result<Boolean> = runCatching {
        if (!client.isAuthenticated()) {
            throw IllegalStateException("YouTube Music account authentication required")
        }
        val ok = client.removePlaylistItem(playlistId, videoId, setVideoId)
        if (!ok) {
            throw java.io.IOException("YouTube Music failed to remove song from playlist")
        }
        true
    }

    override suspend fun removePlaylistItems(playlistId: String, videoIds: List<String>): Result<Boolean> = runCatching {
        if (!client.isAuthenticated()) {
            throw IllegalStateException("YouTube Music account authentication required")
        }
        val ok = client.removePlaylistItems(playlistId, videoIds)
        if (!ok) {
            throw java.io.IOException("YouTube Music failed to remove songs from playlist")
        }
        true
    }

    override suspend fun reorderPlaylistItem(playlistId: String, setVideoId: String, successorSetVideoId: String?): Result<Boolean> = runCatching {
        client.reorderPlaylistItem(playlistId, setVideoId, successorSetVideoId)
    }

    override suspend fun deletePlaylist(playlistId: String): Result<Boolean> = runCatching {
        if (!client.isAuthenticated()) {
            throw IllegalStateException("YouTube Music account authentication required")
        }
        val ok = client.deletePlaylist(playlistId)
        if (!ok) {
            throw java.io.IOException("YouTube Music failed to delete playlist")
        }
        true
    }

    override fun invalidatePlaylistCache(playlistId: String?) {
        client.invalidatePlaylistCache(playlistId)
    }

    override fun isUserAuthenticated(): Boolean = client.isAuthenticated()

    override suspend fun likeSong(videoId: String): Result<Boolean> = runCatching {
        client.likeSong(videoId)
    }

    override suspend fun unlikeSong(videoId: String): Result<Boolean> = runCatching {
        client.unlikeSong(videoId)
    }

    override suspend fun likePlaylist(playlistId: String): Result<Boolean> = runCatching {
        client.likePlaylist(playlistId)
    }

    override suspend fun unlikePlaylist(playlistId: String): Result<Boolean> = runCatching {
        client.unlikePlaylist(playlistId)
    }

    override suspend fun subscribeArtist(channelId: String): Result<Boolean> = runCatching {
        client.subscribeArtist(channelId)
    }

    override suspend fun unsubscribeArtist(channelId: String): Result<Boolean> = runCatching {
        client.unsubscribeArtist(channelId)
    }

    private val playbackReporter by lazy {
        YtPlaybackReporter(client, authHeaderProvider = {
            com.example.FlinsApplication.instance?.let {
                YtAccountManager.getInstance(it).getAuthHeaders()
            } ?: emptyMap()
        })
    }

    override suspend fun reportPlaybackStart(videoId: String, cpn: String): Result<Boolean> =
        playbackReporter.reportPlaybackStart(videoId, cpn)

    override suspend fun reportPlaybackProgress(
        videoId: String,
        cpn: String,
        currentMediaTimeSec: Float,
        state: String
    ): Result<Boolean> =
        playbackReporter.reportPlaybackProgress(videoId, cpn, currentMediaTimeSec, state)

    override suspend fun reportPlaybackCompletion(
        videoId: String,
        cpn: String,
        durationSec: Float
    ): Result<Boolean> =
        playbackReporter.reportPlaybackCompletion(videoId, cpn, durationSec)
}
