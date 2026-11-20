package com.example.service.importer

import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.importer.extractor.PlaylistExtractor
import com.example.service.importer.extractor.SpotifyPlaylistExtractor
import com.example.service.importer.extractor.YouTubeMusicPlaylistExtractor
import com.example.service.importer.matcher.SongMatchingEngine
import com.example.service.importer.model.PlatformType
import com.example.service.importer.model.PlaylistImportData
import com.example.service.importer.model.TrackMatchResult
import com.example.service.importer.url.ParsedPlaylistUrl
import com.example.service.importer.url.PlaylistUrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistImportService(
    private val musicRepository: MusicRepository,
    private val spotifyExtractor: PlaylistExtractor = SpotifyPlaylistExtractor(),
    private val ytMusicExtractor: PlaylistExtractor = YouTubeMusicPlaylistExtractor(),
    private val matchingEngine: SongMatchingEngine = SongMatchingEngine(musicRepository)
) {

    /**
     * Parses and validates raw playlist URL.
     */
    fun validateAndParseUrl(rawUrl: String): Result<ParsedPlaylistUrl> {
        return PlaylistUrlParser.parse(rawUrl)
    }

    /**
     * Extracts public playlist data from Spotify or YouTube Music.
     */
    suspend fun extractPlaylist(
        parsed: ParsedPlaylistUrl,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): Result<PlaylistImportData> {
        return when (parsed.platform) {
            PlatformType.SPOTIFY -> spotifyExtractor.extractPlaylist(parsed.playlistId, onProgress)
            PlatformType.YOUTUBE_MUSIC -> ytMusicExtractor.extractPlaylist(parsed.playlistId, onProgress)
        }
    }

    /**
     * Matches all extracted tracks against Anyfy's music catalog.
     */
    suspend fun matchTracks(
        playlistData: PlaylistImportData,
        onTrackMatched: ((current: Int, total: Int, result: TrackMatchResult) -> Unit)? = null
    ): List<TrackMatchResult> = withContext(Dispatchers.IO) {
        val total = playlistData.tracks.size
        val results = mutableListOf<TrackMatchResult>()

        val knownCatalog = musicRepository.getAllKnownTracks()

        playlistData.tracks.forEachIndexed { index, trackItem ->
            val match = matchingEngine.matchTrack(trackItem, knownCatalog)
            results.add(match)
            onTrackMatched?.invoke(index + 1, total, match)
        }

        results
    }

    /**
     * Creates a new Anyfy playlist on YouTube Music and adds all selected/matched tracks.
     */
    suspend fun commitImport(
        playlistName: String,
        playlistDescription: String,
        artworkUrl: String,
        matchedTracks: List<Track>,
        userId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (matchedTracks.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No matched songs to import."))
        }

        try {
            val plArtwork = artworkUrl.ifBlank {
                matchedTracks.firstOrNull()?.artwork ?: ""
            }

            // Deduplicate matched tracks preserving order
            val distinctTracks = matchedTracks.distinctBy { it.id }
            val videoIds = distinctTracks.map { it.id }

            // Ensure all track metadata is registered in repository/Room
            distinctTracks.forEach { musicRepository.registerTrack(it) }

            // Create new playlist on YouTube Music populated with the resolved tracks
            val createResult = musicRepository.createPlaylistResult(
                name = playlistName.trim(),
                description = playlistDescription.trim(),
                artwork = plArtwork,
                privacyStatus = "PRIVATE",
                userId = userId,
                initialTrackIds = videoIds
            )

            if (createResult.isFailure) {
                val err = createResult.exceptionOrNull() ?: IllegalStateException("Failed to create playlist on YouTube Music.")
                return@withContext Result.failure(err)
            }

            val newPlaylistId = createResult.getOrThrow()

            // Sync library cache so it immediately reflects in the UI
            try {
                musicRepository.fetchUserPlaylists(forceRefresh = true)
            } catch (_: Exception) {}

            Result.success(newPlaylistId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

