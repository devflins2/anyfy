package com.example.service.integrations

import com.example.data.remote.SessionCookieManager
import com.example.model.Track
import com.example.service.diagnostics.PlaybackDiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class StreamException(
    val code: String,
    message: String,
    val isRecoverable: Boolean = false
) : Exception(message) {
    companion object {
        const val INVALID_VIDEO_ID = "INVALID_VIDEO_ID"
        const val VIDEO_UNAVAILABLE = "VIDEO_UNAVAILABLE"
        const val EXTRACTION_FAILURE = "EXTRACTION_FAILURE"
        const val NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
        const val RATE_LIMITING = "RATE_LIMITING"
        const val EXPIRED_STREAM_URL = "EXPIRED_STREAM_URL"
        const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
        const val PLAYBACK_HTTP_ERROR = "PLAYBACK_HTTP_ERROR"
        const val STREAM_UNAVAILABLE = "STREAM_UNAVAILABLE"
        const val UNRECOVERABLE = "UNRECOVERABLE"
    }
}

interface YouTubeService {
    fun setContext(context: android.content.Context) {}
    suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean = false): Result<String>
    suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean, requestId: Long?): Result<String> = resolveStreamUrl(videoId, forceRefresh)
    suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean = false): Result<String> = resolveStreamUrl(track.id, forceRefresh)
    suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean, requestId: Long?): Result<String> = resolveTrackStream(track, forceRefresh)
    suspend fun searchTracks(query: String): List<Track>
    fun getSharedOkHttpClient(): OkHttpClient? = null
    fun isStreamCached(trackId: String): Boolean = false
    fun getLastResolutionSource(): String? = null

    companion object {
        fun isValidYouTubeVideoId(id: String?): Boolean {
            if (id.isNullOrBlank()) return false
            val trimmed = id.trim()
            if (trimmed.length != 11) return false
            if (trimmed.startsWith("MPRE") || trimmed.startsWith("VL") || trimmed.startsWith("PL") ||
                trimmed.startsWith("UC") || trimmed.startsWith("RD")
            ) return false
            return trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))
        }

        fun isStreamUrlExpired(url: String?): Boolean {
            if (url.isNullOrBlank()) return true
            return try {
                val match = Regex("""[?&]expire=(\d+)""").find(url)
                if (match != null) {
                    val expireSec = match.groupValues[1].toLongOrNull() ?: return false
                    val currentSec = System.currentTimeMillis() / 1000L
                    currentSec >= (expireSec - 180L)
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun isAudioPreviewUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase()
            return lower.contains("itunes.apple.com") ||
                   lower.contains("audiopreview") ||
                   lower.contains("mzstatic.com") ||
                   lower.contains("/preview.m4a") ||
                   lower.contains("preview-")
        }

        fun cleanArtistForSearch(artist: String?): String {
            if (artist.isNullOrBlank()) return ""
            val parts = artist.split("•").map { it.trim() }.filter { it.isNotBlank() }
            val candidate = parts.firstOrNull { part ->
                val lower = part.lowercase()
                lower != "song" &&
                lower != "album" &&
                lower != "single" &&
                lower != "ep" &&
                lower != "video" &&
                lower != "playlist" &&
                lower != "youtube music" &&
                lower != "various artists" &&
                !lower.contains("views") &&
                !lower.contains("plays") &&
                !part.matches(Regex("""^\d+:\d+$""")) &&
                !part.matches(Regex("""^\d{4}$"""))
            }
            return candidate ?: ""
        }

        private fun normalizeForMatch(s: String): String {
            return s.lowercase()
                .replace(Regex("""\([^)]*\)"""), " ")
                .replace(Regex("""\[[^\]]*\]"""), " ")
                .replace(Regex("""\b(feat|ft|official|audio|video|lyrics|lyric|remix|from|version)\b.*""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""[^a-z0-9\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        fun isPlausibleMatch(
            expectedTitle: String?,
            expectedArtist: String?,
            candidateTitle: String?,
            candidateArtist: String?
        ): Boolean {
            if (expectedTitle.isNullOrBlank() || candidateTitle.isNullOrBlank()) return false

            val normExpTitle = normalizeForMatch(expectedTitle)
            val normCandTitle = normalizeForMatch(candidateTitle)

            if (normExpTitle.isBlank() || normCandTitle.isBlank()) return false

            // Exact match or direct containment
            if (normExpTitle == normCandTitle ||
                normCandTitle.contains(normExpTitle) ||
                normExpTitle.contains(normCandTitle)
            ) {
                return true
            }

            val expWords = normExpTitle.split(" ").filter { it.length > 2 }
            val candWords = normCandTitle.split(" ").filter { it.length > 2 }

            if (expWords.isEmpty() || candWords.isEmpty()) {
                return normExpTitle == normCandTitle
            }

            val matchingWords = expWords.count { candWords.contains(it) }
            val matchRatio = matchingWords.toDouble() / expWords.size.toDouble()

            // If 50% or more significant words match
            if (matchRatio >= 0.5) return true

            // Check artist overlap
            val cleanExp = cleanArtistForSearch(expectedArtist)
            val normExpArt = normalizeForMatch(cleanExp)
            val normCandArt = normalizeForMatch(candidateArtist ?: "")

            val hasArtistOverlap = if (normExpArt.isNotBlank() && normCandArt.isNotBlank()) {
                val expArtWords = normExpArt.split(" ").filter { it.length > 2 }
                val candArtWords = normCandArt.split(" ").filter { it.length > 2 }
                if (expArtWords.isNotEmpty() && candArtWords.isNotEmpty()) {
                    expArtWords.any { candArtWords.contains(it) }
                } else {
                    normCandArt.contains(normExpArt) || normExpArt.contains(normCandArt)
                }
            } else {
                false
            }

            return if (hasArtistOverlap) {
                matchingWords > 0
            } else {
                matchRatio >= 0.75
            }
        }
    }
}

class LiveYouTubeService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            if (original.header("User-Agent") == null) {
                requestBuilder.header("User-Agent", SessionCookieManager.USER_AGENT)
            }
            requestBuilder.header("bypass-tunnel-reminder", "true")
            chain.proceed(requestBuilder.build())
        }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : YouTubeService {
    override fun getSharedOkHttpClient(): OkHttpClient = client

    // Candidate backend hosts (empty by default for pure on-device extraction)
    private val candidateHosts = emptyList<String>()

    private var appContext: android.content.Context? = null

    @Volatile
    private var lastResolutionSource: String? = null

    override fun getLastResolutionSource(): String? = lastResolutionSource

    override fun setContext(context: android.content.Context) {
        this.appContext = context.applicationContext
        syncAuthCookies()
    }

    private val simpMusicRepository by lazy {
        com.example.service.simpmusic.repository.SimpMusicStreamRepository(client)
    }

    private fun syncAuthCookies() {
        appContext?.let { ctx ->
            try {
                val mgr = com.example.service.integrations.ytmusic.YtAccountManager.getInstance(ctx)
                val cookie = mgr.activeAccount.value?.cookie
                simpMusicRepository.logIn(cookie)
            } catch (_: Throwable) {
            }
        }
    }

    private fun hasAuthCookies(): Boolean {
        return appContext?.let { ctx ->
            try {
                val mgr = com.example.service.integrations.ytmusic.YtAccountManager.getInstance(ctx)
                !mgr.activeAccount.value?.cookie.isNullOrBlank()
            } catch (_: Throwable) {
                false
            }
        } ?: false
    }

    // High performance in-memory and persistent disk caches to eliminate repeated network calls
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    // YouTube signed URLs can expire in under an hour in some regions/conditions.
    // 45 min TTL prevents stale cached URLs reaching MediaPlayer with HTTP 403.
    private val STREAM_CACHE_TTL_MS = 45 * 60 * 1000L // 45 minutes

    private fun getDiskCache(): com.example.service.cache.StreamUrlDiskCache? =
        appContext?.let { com.example.service.cache.StreamUrlDiskCache.getInstance(it) }

    override fun isStreamCached(trackId: String): Boolean {
        val cleanId = trackId.removePrefix("yt_").removePrefix("online_").removePrefix("itunes_").trim()
        val cached = streamCache[cleanId] ?: streamCache[trackId]
        if (cached != null) {
            val isExpired = YouTubeService.isStreamUrlExpired(cached.first)
            val isTtlExceeded = (System.currentTimeMillis() - cached.second) >= STREAM_CACHE_TTL_MS
            if (!isExpired && !isTtlExceeded) return true
            streamCache.remove(cleanId)
            streamCache.remove(trackId)
        }
        val diskUrl = getDiskCache()?.get(cleanId)
        if (!diskUrl.isNullOrBlank()) {
            streamCache[cleanId] = Pair(diskUrl, System.currentTimeMillis())
            return true
        }
        return false
    }

    private val catalogVideoIds = emptyMap<String, String>()

    private fun isAudioPreviewUrl(url: String?): Boolean = YouTubeService.isAudioPreviewUrl(url)

    private fun parseTimestampToSeconds(timer: String): Int {
        val parts = timer.split(":")
        return when (parts.size) {
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            1 -> parts[0].toIntOrNull() ?: 0
            else -> 180
        }
    }

    // Timeout for candidate hosts
    private val fastClient = client.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return@withContext emptyList()

        // 0. Instant Cache check (0ms response)
        searchCache[normalizedQuery]?.let { cached ->
            return@withContext cached
        }

        val combinedResults = mutableListOf<Track>()

        // 1. YouTube Music InnerTube search (single source of truth)
        try {
            val ytMusicService = com.example.service.integrations.ytmusic.YtMusicService.getInstance()
            val ytResult = ytMusicService.searchTracks(query)
            if (ytResult.isSuccess) {
                val ytTracks = ytResult.getOrDefault(emptyList()).map { it.toTrack() }
                if (ytTracks.isNotEmpty()) {
                    val finalResults = ytTracks.distinctBy { it.id }
                    searchCache[normalizedQuery] = finalResults
                    return@withContext finalResults
                }
            }
        } catch (_: Exception) {}

        val encoded = URLEncoder.encode(query, "UTF-8")

        // 3. Try candidate backend hosts if configured
        for (host in candidateHosts) {
            try {
                val url = "$host/api/search?q=$encoded"
                val request = Request.Builder().url(url).build()
                fastClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val resultsArray = json.optJSONArray("results")
                        if (resultsArray != null && resultsArray.length() > 0) {
                            for (i in 0 until resultsArray.length()) {
                                val item = resultsArray.getJSONObject(i)
                                val track = mapVideoJsonToTrack(item, host)
                                if (track != null) combinedResults.add(track)
                            }
                        }
                    }
                }
                if (combinedResults.any { it.id.startsWith("yt_") }) {
                    break
                }
            } catch (_: Exception) {
                // Try next host
            }
        }

        val finalResults = combinedResults.distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
        if (finalResults.isNotEmpty()) {
            searchCache[normalizedQuery] = finalResults
        }
        finalResults
    }

    private fun mapVideoJsonToTrack(item: JSONObject, host: String): Track? {
        val title = item.optString("title")
        if (title.isBlank()) return null
        val timer = item.optString("timer", "3:30")
        val thumbnail = item.optString("thumbnail").ifBlank { item.optString("artwork") }
        var downloadUrl = item.optString("download_url").ifBlank { item.optString("audioUrl") }
        val videoId = item.optString("videoId")
        val author = item.optString("author").ifBlank { item.optString("artist", "YouTube Artist") }

        if (downloadUrl.contains("localhost:3000") && host.startsWith("10.0.2.2")) {
            downloadUrl = downloadUrl.replace("localhost:3000", host)
        }

        return Track(
            id = "yt_$videoId",
            title = title,
            artist = author,
            artistId = "art_yt",
            album = "YouTube Stream",
            albumId = "alb_yt",
            artwork = thumbnail,
            durationSeconds = parseTimestampToSeconds(timer).coerceAtLeast(30),
            audioUrl = if (downloadUrl.isNotBlank()) downloadUrl else "$host/api/stream?v=$videoId",
            genre = "YouTube Audio",
            bpm = 124,
            energy = 0.85f,
            mood = "Live",
            paletteColorHex = "#FF0033"
        )
    }

    private suspend fun searchYouTubeMusicTrack(query: String): String? {
        return try {
            val ytMusicService = com.example.service.integrations.ytmusic.YtMusicService.getInstance()
            val results = ytMusicService.searchTracks(query).getOrNull()
            val bestMatch = results?.firstOrNull()?.id?.removePrefix("yt_")?.trim()
            if (YouTubeService.isValidYouTubeVideoId(bestMatch)) bestMatch else null
        } catch (_: Exception) {
            null
        }
    }


    override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> =
        resolveStreamUrl(videoId, forceRefresh, null)

    override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean, requestId: Long?): Result<String> = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (!YouTubeService.isValidYouTubeVideoId(cleanId)) {
            android.util.Log.w("LiveYouTubeService", "[resolveStreamUrl] INVALID_VIDEO_ID vid=${cleanId.take(4)}…")
            return@withContext Result.failure(StreamException(StreamException.INVALID_VIDEO_ID, "Invalid YouTube video ID: $cleanId", isRecoverable = false))
        }

        val effectiveReqId = requestId ?: PlaybackDiagnosticLogger.getActiveRequestId()
        syncAuthCookies()

        // 0. Instant Cache check (Memory + Persistent Disk Cache)
        if (!forceRefresh) {
            val cached = streamCache[cleanId]
            if (cached != null) {
                if ((System.currentTimeMillis() - cached.second) < STREAM_CACHE_TTL_MS && !YouTubeService.isStreamUrlExpired(cached.first)) {
                    lastResolutionSource = "Memory Cache"
                    android.util.Log.d("LiveYouTubeService", "[resolveStreamUrl] CACHE_HIT stage=0 source=memory vid=${cleanId.take(4)}…")
                    return@withContext Result.success(cached.first)
                } else {
                    streamCache.remove(cleanId)
                }
            }
            val diskCached = getDiskCache()?.get(cleanId)
            if (!diskCached.isNullOrBlank() && !YouTubeService.isStreamUrlExpired(diskCached)) {
                streamCache[cleanId] = Pair(diskCached, System.currentTimeMillis())
                lastResolutionSource = "Disk Cache"
                android.util.Log.d("LiveYouTubeService", "[resolveStreamUrl] CACHE_HIT stage=0 source=disk vid=${cleanId.take(4)}…")
                return@withContext Result.success(diskCached)
            }
        } else {
            streamCache.remove(cleanId)
            getDiskCache()?.remove(cleanId)
        }

        // 1. SimpMusic 3-Tier Production Engine (QuickJS on-device cipher solver -> pipepipe.dev -> BravePipe)
        val extractionStart = System.currentTimeMillis()
        try {
            PlaybackDiagnosticLogger.trace4_InnerTubeAttempt(
                effectiveReqId,
                clientName = "SimpMusic-Faraday",
                success = true,
                durationMs = 0L
            )
            val streamResult = simpMusicRepository.getStreamUrl(cleanId, forceRefresh)
            val duration = System.currentTimeMillis() - extractionStart
            if (streamResult.isSuccess) {
                val directUrl = streamResult.getOrThrow()
                lastResolutionSource = simpMusicRepository.getLastSource()
                android.util.Log.i("LiveYouTubeService", "[resolveStreamUrl] OK vid=${cleanId.take(4)} source=$lastResolutionSource duration=${duration}ms")
                streamCache[cleanId] = Pair(directUrl, System.currentTimeMillis())
                getDiskCache()?.put(cleanId, directUrl, lastResolutionSource ?: "simpmusic")
                PlaybackDiagnosticLogger.logExtractionComplete(
                    requestId = effectiveReqId,
                    success = true,
                    url = directUrl,
                    error = null,
                    detailedSource = lastResolutionSource ?: "SimpMusic"
                )
                return@withContext Result.success(directUrl)
            } else {
                val err = streamResult.exceptionOrNull()?.message ?: "Extraction failed"
                android.util.Log.w("LiveYouTubeService", "[resolveStreamUrl] FAIL vid=${cleanId.take(4)} err=$err duration=${duration}ms")
                PlaybackDiagnosticLogger.logExtractionComplete(
                    requestId = effectiveReqId,
                    success = false,
                    url = null,
                    error = err,
                    detailedSource = simpMusicRepository.getLastSource()
                )
                return@withContext Result.failure(StreamException(StreamException.EXTRACTION_FAILURE, err, isRecoverable = true))
            }
        } catch (e: Throwable) {
            val err = e.message ?: "Unknown extraction error"
            android.util.Log.e("LiveYouTubeService", "[resolveStreamUrl] EXCEPTION vid=${cleanId.take(4)}: $err", e)
            PlaybackDiagnosticLogger.logExtractionComplete(
                requestId = effectiveReqId,
                success = false,
                url = null,
                error = err,
                detailedSource = "Exception"
            )
            return@withContext Result.failure(StreamException(StreamException.EXTRACTION_FAILURE, err, isRecoverable = true))
        }
    }

    override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> =
        resolveTrackStream(track, forceRefresh, null)

    override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean, requestId: Long?): Result<String> = withContext(Dispatchers.IO) {
        val cacheKey = track.id.removePrefix("yt_").removePrefix("online_").removePrefix("itunes_").trim()
        val sanitizedTitle = track.title.take(25).replace("\"", "")
        val logTag = "LiveYouTubeService"
        val logId = "req=${System.currentTimeMillis() % 100000} trk=${track.id.take(12)} title=\"$sanitizedTitle\""

        // 0. Instant Cache check
        if (!forceRefresh) {
            val cached = streamCache[cacheKey] ?: streamCache[track.id]
            if (cached != null) {
                if ((System.currentTimeMillis() - cached.second) < STREAM_CACHE_TTL_MS && !YouTubeService.isStreamUrlExpired(cached.first)) {
                    lastResolutionSource = "Memory Cache"
                    android.util.Log.d(logTag, "[$logId] CACHE_HIT stage=0 source=cache")
                    return@withContext Result.success(cached.first)
                } else {
                    streamCache.remove(cacheKey)
                    streamCache.remove(track.id)
                }
            }
        }

        // 1. Direct verified non-preview audioUrl (e.g. catalog track)
        if (!track.audioUrl.isNullOrBlank() && !YouTubeService.isAudioPreviewUrl(track.audioUrl) && !YouTubeService.isStreamUrlExpired(track.audioUrl) && !forceRefresh) {
            lastResolutionSource = "Direct Catalog Track"
            streamCache[cacheKey] = Pair(track.audioUrl, System.currentTimeMillis())
            return@withContext Result.success(track.audioUrl)
        }

        // 2. Direct YouTube video ID resolution via SimpMusic engine
        val candidateVid = track.id.removePrefix("yt_").trim()
        if (YouTubeService.isValidYouTubeVideoId(candidateVid)) {
            val res = resolveStreamUrl(candidateVid, forceRefresh, requestId)
            if (res.isSuccess) {
                val streamUrl = res.getOrThrow()
                streamCache[track.id] = Pair(streamUrl, System.currentTimeMillis())
                streamCache[cacheKey] = Pair(streamUrl, System.currentTimeMillis())
                return@withContext Result.success(streamUrl)
            } else {
                // Video ID is known and genuine, but failed extraction.
                // STRICT: PREVENT WRONG-SONG SUBSTITUTION. Never fall back to title-based search
                // for an explicit YouTube Music track, as that causes unrelated tracks to be played.
                val err = res.exceptionOrNull()?.message ?: "Extraction failed for video ID $candidateVid"
                android.util.Log.w(logTag, "[$logId] Genuine YouTube track resolution failed: $err. Refusing title substitution.")
                streamCache.remove(track.id)
                streamCache.remove(cacheKey)
                lastResolutionSource = "Extraction Failed"
                return@withContext Result.failure(StreamException(StreamException.EXTRACTION_FAILURE, err, isRecoverable = true))
            }
        }

        // 3. Fallback: Catalog known video ID
        catalogVideoIds[track.id]?.let { knownVid ->
            if (YouTubeService.isValidYouTubeVideoId(knownVid)) {
                val res = resolveStreamUrl(knownVid, forceRefresh, requestId)
                if (res.isSuccess) {
                    val streamUrl = res.getOrThrow()
                    streamCache[track.id] = Pair(streamUrl, System.currentTimeMillis())
                    streamCache[cacheKey] = Pair(streamUrl, System.currentTimeMillis())
                    return@withContext Result.success(streamUrl)
                }
            }
        }

        // 4. Fallback: Genuine YouTube Music search for video ID by title + artist
        if (track.title.isNotBlank()) {
            val cleanArtist = YouTubeService.cleanArtistForSearch(track.artist)
            val query = if (cleanArtist.isNotBlank()) "${track.title} $cleanArtist".trim() else track.title.trim()
            val searchedVid = searchYouTubeMusicTrack(query)
            if (!searchedVid.isNullOrBlank() && YouTubeService.isValidYouTubeVideoId(searchedVid)) {
                val res = resolveStreamUrl(searchedVid, forceRefresh, requestId)
                if (res.isSuccess) {
                    val streamUrl = res.getOrThrow()
                    streamCache[track.id] = Pair(streamUrl, System.currentTimeMillis())
                    streamCache[cacheKey] = Pair(streamUrl, System.currentTimeMillis())
                    return@withContext Result.success(streamUrl)
                }
            }
        }

        lastResolutionSource = "All Sources Exhausted"
        android.util.Log.w(logTag, "[$logId] ALL_SOURCES_EXHAUSTED cat=STREAM_UNAVAILABLE")
        Result.failure(StreamException(StreamException.STREAM_UNAVAILABLE, "Could not resolve YouTube Music stream for: ${track.title}"))
    }
}
