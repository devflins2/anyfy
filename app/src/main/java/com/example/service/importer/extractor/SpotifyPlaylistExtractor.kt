package com.example.service.importer.extractor

import com.example.service.importer.model.ImportTrackItem
import com.example.service.importer.model.PlatformType
import com.example.service.importer.model.PlaylistImportData
import com.example.service.importer.normalizer.TrackNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SpotifyPlaylistExtractor(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : PlaylistExtractor {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val EMBED_BASE_URL = "https://open.spotify.com/embed/playlist"
        private const val OEMBED_BASE_URL = "https://open.spotify.com/oembed"
    }

    override suspend fun extractPlaylist(
        playlistId: String,
        onProgress: ((loaded: Int, total: Int) -> Unit)?
    ): Result<PlaylistImportData> = withContext(Dispatchers.IO) {
        val cleanId = playlistId.trim()
        if (cleanId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Spotify playlist ID cannot be empty."))
        }

        try {
            // 1. Fetch official Spotify embed page which embeds hydration JSON
            val embedUrl = "$EMBED_BASE_URL/$cleanId"
            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    return@withContext Result.failure(
                        IOException("Spotify playlist not found or is set to private.")
                    )
                }
                return@withContext Result.failure(
                    IOException("Failed to connect to Spotify (HTTP ${response.code}). Please verify playlist visibility.")
                )
            }

            val htmlBody = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response received from Spotify."))

            // 2. Parse __NEXT_DATA__ JSON script payload
            val scriptRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">([^<]+)</script>""")
            val match = scriptRegex.find(htmlBody)

            if (match == null) {
                // If __NEXT_DATA__ script is missing, attempt oEmbed fallback to verify existence
                val oEmbedData = fetchOEmbedMetadata(cleanId)
                if (oEmbedData != null) {
                    return@withContext Result.failure(
                        IOException("This Spotify playlist ('${oEmbedData.title}') cannot be imported because Spotify has restricted its embed details.")
                    )
                }
                return@withContext Result.failure(
                    IOException("Could not extract metadata from Spotify playlist. Ensure the playlist is public.")
                )
            }

            val jsonString = match.groupValues[1]
            val nextData = JSONObject(jsonString)

            val pageProps = nextData.optJSONObject("props")?.optJSONObject("pageProps")
            val state = pageProps?.optJSONObject("state")?.optJSONObject("data")
            val entity = state?.optJSONObject("entity")

            if (entity == null) {
                return@withContext Result.failure(
                    IOException("Spotify playlist not found, deleted, or inaccessible without login.")
                )
            }

            val name = entity.optString("name", "").ifBlank {
                entity.optString("title", "Imported Spotify Playlist")
            }
            val subtitle = entity.optString("subtitle", "")
            val description = entity.optString("description", subtitle)

            var artworkUrl = ""
            val coverArtObj = entity.optJSONObject("coverArt")
            val sources = coverArtObj?.optJSONArray("sources")
            if (sources != null && sources.length() > 0) {
                artworkUrl = sources.optJSONObject(0)?.optString("url", "") ?: ""
            }

            val trackListArray = entity.optJSONArray("trackList")
            val totalTracks = trackListArray?.length() ?: 0

            if (totalTracks == 0) {
                return@withContext Result.failure(
                    IllegalStateException("This Spotify playlist contains no public tracks.")
                )
            }

            val tracks = mutableListOf<ImportTrackItem>()
            for (i in 0 until totalTracks) {
                val item = trackListArray?.optJSONObject(i) ?: continue

                val title = item.optString("title", "").trim()
                if (title.isBlank()) continue

                val artistRaw = item.optString("subtitle", "Unknown Artist")
                val artists = TrackNormalizer.splitArtists(artistRaw)

                val uri = item.optString("uri", "")
                val uid = item.optString("uid", "sp_$i")
                val sourceTrackId = uri.ifBlank { uid }

                val durationMs = item.optLong("duration", 180000L)

                // Track artwork fallback to playlist artwork
                var trackArtwork = artworkUrl
                val itemCover = item.optJSONObject("coverArt")?.optJSONArray("sources")
                if (itemCover != null && itemCover.length() > 0) {
                    val candidate = itemCover.optJSONObject(0)?.optString("url", "")
                    if (!candidate.isNullOrBlank()) trackArtwork = candidate
                }

                val trackItem = ImportTrackItem(
                    sourceTrackId = sourceTrackId,
                    title = title,
                    artists = artists,
                    album = "",
                    artwork = trackArtwork,
                    durationMs = durationMs,
                    position = i
                )
                tracks.add(trackItem)
                onProgress?.invoke(tracks.size, totalTracks)
            }

            val importData = PlaylistImportData(
                source = PlatformType.SPOTIFY,
                sourcePlaylistId = cleanId,
                playlistName = name,
                playlistDescription = description,
                artwork = artworkUrl,
                totalTracks = tracks.size,
                tracks = tracks
            )

            Result.success(importData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchOEmbedMetadata(playlistId: String): OEmbedResult? {
        return try {
            val url = "$OEMBED_BASE_URL?url=https://open.spotify.com/playlist/$playlistId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val str = resp.body?.string() ?: return null
                    val json = JSONObject(str)
                    OEmbedResult(
                        title = json.optString("title", "Spotify Playlist"),
                        thumbnailUrl = json.optString("thumbnail_url", "")
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class OEmbedResult(val title: String, val thumbnailUrl: String)
}

