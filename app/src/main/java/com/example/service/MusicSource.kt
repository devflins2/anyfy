package com.example.service

import com.example.data.repository.MusicRepository
import com.example.model.Album
import com.example.model.Artist
import com.example.model.LyricsLine
import com.example.model.Track
import com.example.service.integrations.LiveYouTubeService
import com.example.service.integrations.YouTubeService

/**
 * Clean music API contract adhering to FlinsMusic Architecture.
 * Integrates local catalog and live YouTube streaming backend.
 */
interface MusicSource {
    suspend fun search(query: String): List<Track>
    suspend fun getTrack(id: String): Track?
    suspend fun getArtist(id: String): Artist?
    suspend fun getAlbum(id: String): Album?
    suspend fun getRecommendations(): List<Track>
    suspend fun getLyrics(trackId: String): List<LyricsLine>
}

class FlinsMusicSource(
    private val repository: MusicRepository,
    private val youTubeService: YouTubeService = LiveYouTubeService()
) : MusicSource {
    private val cachedTracks = mutableMapOf<String, Track>()

    override suspend fun search(query: String): List<Track> {
        if (query.isBlank()) return repository.catalogTracks
        val lower = query.lowercase().trim()
        val localMatches = repository.catalogTracks.filter { track ->
            track.title.lowercase().contains(lower) ||
            track.artist.lowercase().contains(lower) ||
            track.album.lowercase().contains(lower) ||
            track.genre.lowercase().contains(lower)
        }

        // Query YouTube & online streaming services
        val remoteResults = try {
            youTubeService.searchTracks(query)
        } catch (_: Exception) {
            emptyList()
        }

        remoteResults.forEach { cachedTracks[it.id] = it }

        // Combine remote results with local matches
        return (remoteResults + localMatches).distinctBy { it.id }
    }

    override suspend fun getTrack(id: String): Track? {
        return cachedTracks[id] ?: repository.getTrackById(id)
    }

    override suspend fun getArtist(id: String): Artist? {
        return repository.getArtistById(id)
    }

    override suspend fun getAlbum(id: String): Album? {
        return repository.getAlbumById(id)
    }

    override suspend fun getRecommendations(): List<Track> {
        return repository.catalogTracks.shuffled().take(5)
    }

    override suspend fun getLyrics(trackId: String): List<LyricsLine> {
        return repository.getTrackById(trackId)?.lyrics ?: emptyList()
    }
}
