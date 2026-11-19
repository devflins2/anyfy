package com.example.service.importer.extractor

import com.example.service.importer.model.ImportTrackItem
import com.example.service.importer.model.PlatformType
import com.example.service.importer.model.PlaylistImportData
import com.example.service.importer.normalizer.TrackNormalizer
import com.example.service.integrations.ytmusic.YtMusicClient
import com.example.service.integrations.ytmusic.YtMusicParser
import com.example.service.integrations.ytmusic.YtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class YouTubeMusicPlaylistExtractor(
    private val client: YtMusicClient = YtMusicClient()
) : PlaylistExtractor {

    companion object {
        private const val MAX_PAGINATION_LIMIT = 20 // Safeguard against runaway loops (up to ~2000 tracks)
    }

    override suspend fun extractPlaylist(
        playlistId: String,
        onProgress: ((loaded: Int, total: Int) -> Unit)?
    ): Result<PlaylistImportData> = withContext(Dispatchers.IO) {
        val cleanId = playlistId.trim()
        if (cleanId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("YouTube Music playlist ID cannot be empty."))
        }

        try {
            // 1. Initial Browse Request
            val browseId = if (cleanId.startsWith("VL")) cleanId else "VL$cleanId"
            val initialResponse = try {
                client.browse(browseId)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    IOException("Failed to connect to YouTube Music: ${e.message}")
                )
            }

            val initialPlaylist = YtMusicParser.parsePlaylistResponse(initialResponse, cleanId)

            if (initialPlaylist.title.isBlank() && initialPlaylist.tracks.isEmpty()) {
                return@withContext Result.failure(
                    IOException("YouTube Music playlist '$cleanId' not found, empty, or set to private.")
                )
            }

            val allTracks = mutableListOf<YtTrack>()
            allTracks.addAll(initialPlaylist.tracks)

            val totalExpected = if (initialPlaylist.trackCount > 0) initialPlaylist.trackCount else allTracks.size
            onProgress?.invoke(allTracks.size, totalExpected)

            // 2. Pagination Continuation Handling
            var continuationToken = initialPlaylist.continuationToken
            var pageCount = 0

            while (!continuationToken.isNullOrBlank() && pageCount < MAX_PAGINATION_LIMIT) {
                pageCount++
                try {
                    val contResponse = client.continueBrowse(continuationToken)
                    val (pagedTracks, nextCont) = YtMusicParser.parseContinuationTracks(contResponse)

                    if (pagedTracks.isEmpty()) break

                    allTracks.addAll(pagedTracks)
                    continuationToken = nextCont
                    onProgress?.invoke(allTracks.size, maxOf(totalExpected, allTracks.size))
                } catch (_: Exception) {
                    // Gracefully stop pagination on network hiccup and use all retrieved tracks so far
                    break
                }
            }

            if (allTracks.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("This YouTube Music playlist contains no accessible public tracks.")
                )
            }

            // 3. Map into Normalized ImportTrackItem list preserving position
            val importedTracks = allTracks.mapIndexed { index, ytTrack ->
                val artists = TrackNormalizer.splitArtists(ytTrack.artist)
                ImportTrackItem(
                    sourceTrackId = ytTrack.id,
                    title = ytTrack.title,
                    artists = artists,
                    album = ytTrack.album,
                    artwork = ytTrack.artwork.ifBlank { initialPlaylist.artwork },
                    durationMs = (ytTrack.durationSeconds.toLong() * 1000L).coerceAtLeast(10000L),
                    position = index
                )
            }

            val importData = PlaylistImportData(
                source = PlatformType.YOUTUBE_MUSIC,
                sourcePlaylistId = cleanId,
                playlistName = initialPlaylist.title.ifBlank { "YouTube Music Playlist" },
                playlistDescription = initialPlaylist.description.ifBlank { "Imported from YouTube Music (${initialPlaylist.author})" },
                artwork = initialPlaylist.artwork.ifBlank { importedTracks.firstOrNull()?.artwork ?: "" },
                totalTracks = importedTracks.size,
                tracks = importedTracks
            )

            Result.success(importData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

