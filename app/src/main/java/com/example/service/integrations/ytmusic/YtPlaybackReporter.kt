package com.example.service.integrations.ytmusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Data holder for playback tracking endpoints parsed from YouTube Music InnerTube /player response.
 */
data class YtPlaybackTracking(
    val playbackUrl: String?,
    val watchtimeUrl: String?,
    val delayplayUrl: String?,
    val atrUrl: String?
)

/**
 * Production playback tracking and listening-history synchronization engine for YouTube Music.
 *
 * Implements genuine telemetry reporting using InnerTube /player playbackTracking metadata:
 * - videostatsPlaybackUrl: Reports playback start milestone
 * - videostatsWatchtimeUrl: Reports periodic playback progress (cmt, state) and playback completion
 *
 * Non-blocking: all network pings execute asynchronously on Dispatchers.IO and never
 * interrupt or block ExoPlayer audio playback.
 * Deduplicated: guards against duplicate reporting for the same playback session.
 */
class YtPlaybackReporter(
    private val client: YtMusicClient = YtMusicClient(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    private val authHeaderProvider: () -> Map<String, String> = { emptyMap() }
) {
    companion object {
        private const val TAG = "YtPlaybackReporter"
        private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

        /**
         * Generates a 16-character pseudo-random alphanumeric Client Playback Nonce (CPN)
         * as required by YouTube's player and tracking servers.
         */
        fun generateCpn(): String {
            return (1..16).map { CPN_ALPHABET.random() }.joinToString("")
        }

        /**
         * Extracts playback tracking URLs from YouTube Music InnerTube player response JSON.
         */
        fun parsePlaybackTracking(playerJson: JSONObject): YtPlaybackTracking? {
            val tracking = playerJson.optJSONObject("playbackTracking") ?: return null
            val playbackUrl = tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")?.ifBlank { null }
            val watchtimeUrl = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl")?.ifBlank { null }
            val delayplayUrl = tracking.optJSONObject("videostatsDelayplayUrl")?.optString("baseUrl")?.ifBlank { null }
            val atrUrl = tracking.optJSONObject("atrUrl")?.optString("baseUrl")?.ifBlank { null }
            return YtPlaybackTracking(playbackUrl, watchtimeUrl, delayplayUrl, atrUrl)
        }
    }

    // Active session tracking URLs cached by session CPN
    private val sessionTrackingCache = ConcurrentHashMap<String, YtPlaybackTracking>()

    // Deduplication sets to prevent duplicate start/end events for the same session CPN
    private val reportedStarts = Collections.synchronizedSet(mutableSetOf<String>())
    private val reportedEnds = Collections.synchronizedSet(mutableSetOf<String>())
    private val lastProgressSec = ConcurrentHashMap<String, Float>()

    /**
     * Checks if current session has authenticated YouTube Music account credentials.
     */
    fun isAuthenticated(): Boolean {
        val headers = authHeaderProvider()
        val cookie = headers["Cookie"] ?: headers["cookie"]
        val auth = headers["Authorization"] ?: headers["authorization"]
        return !cookie.isNullOrBlank() || !auth.isNullOrBlank()
    }

    /**
     * Resolves playback tracking metadata for the given video ID.
     */
    suspend fun resolveTracking(videoId: String, cpn: String): YtPlaybackTracking? = withContext(Dispatchers.IO) {
        sessionTrackingCache[cpn]?.let { return@withContext it }

        val cleanVid = videoId.removePrefix("yt_").trim()
        try {
            val res = client.resolvePlayerStream(cleanVid)
            if (res.isSuccess) {
                val json = res.getOrNull()
                if (json != null) {
                    val tracking = parsePlaybackTracking(json)
                    if (tracking != null) {
                        sessionTrackingCache[cpn] = tracking
                        Log.d(TAG, "[$cpn] Resolved playback tracking for $cleanVid (hasPlaybackUrl=${tracking.playbackUrl != null}, hasWatchtimeUrl=${tracking.watchtimeUrl != null})")
                        return@withContext tracking
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[$cpn] Failed to resolve playback tracking for $cleanVid: ${e.message}")
        }
        null
    }

    /**
     * Reports playback start event to YouTube Music (videostatsPlaybackUrl).
     */
    suspend fun reportPlaybackStart(videoId: String, cpn: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!reportedStarts.add(cpn)) {
            Log.d(TAG, "[$cpn] Playback start already reported, skipping duplicate")
            return@withContext Result.success(true)
        }

        try {
            val tracking = sessionTrackingCache[cpn] ?: resolveTracking(videoId, cpn)
            val baseUrl = tracking?.playbackUrl
            if (baseUrl.isNullOrBlank()) {
                Log.d(TAG, "[$cpn] No videostatsPlaybackUrl available for $videoId, recording local start")
                return@withContext Result.success(false)
            }

            val targetUrl = buildTrackingUrl(baseUrl, mapOf("cpn" to cpn, "fver" to "2"))
            val success = executeTrackingPing(targetUrl)
            Log.i(TAG, "[$cpn] Playback start reported for $videoId: success=$success")
            Result.success(success)
        } catch (e: Throwable) {
            Log.w(TAG, "[$cpn] Error reporting playback start for $videoId: ${e.message}")
            Result.success(false) // Never fail caller
        }
    }

    /**
     * Reports playback progress and watch time (videostatsWatchtimeUrl).
     */
    suspend fun reportPlaybackProgress(
        videoId: String,
        cpn: String,
        currentMediaTimeSec: Float,
        state: String = "playing"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        // Throttle progress updates to avoid flooding stats endpoint (at least 4 seconds between pings unless state changed)
        val lastTime = lastProgressSec[cpn] ?: -10f
        if (state == "playing" && Math.abs(currentMediaTimeSec - lastTime) < 4.0f) {
            return@withContext Result.success(true)
        }
        lastProgressSec[cpn] = currentMediaTimeSec

        try {
            val tracking = sessionTrackingCache[cpn] ?: resolveTracking(videoId, cpn)
            val baseUrl = tracking?.watchtimeUrl
            if (baseUrl.isNullOrBlank()) {
                return@withContext Result.success(false)
            }

            val cmtFormatted = String.format(Locale.US, "%.3f", currentMediaTimeSec)
            val params = mapOf(
                "cpn" to cpn,
                "state" to state,
                "cmt" to cmtFormatted,
                "st" to "0.000",
                "et" to cmtFormatted,
                "fver" to "2"
            )
            val targetUrl = buildTrackingUrl(baseUrl, params)
            val success = executeTrackingPing(targetUrl)
            Log.d(TAG, "[$cpn] Playback progress reported (cmt=$cmtFormatted, state=$state): success=$success")
            Result.success(success)
        } catch (e: Throwable) {
            Log.w(TAG, "[$cpn] Error reporting playback progress: ${e.message}")
            Result.success(false)
        }
    }

    /**
     * Reports playback completion / end event to YouTube Music (videostatsWatchtimeUrl with state=ended).
     */
    suspend fun reportPlaybackCompletion(
        videoId: String,
        cpn: String,
        durationSec: Float
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!reportedEnds.add(cpn)) {
            Log.d(TAG, "[$cpn] Playback completion already reported, skipping duplicate")
            return@withContext Result.success(true)
        }

        try {
            val tracking = sessionTrackingCache[cpn] ?: resolveTracking(videoId, cpn)
            val baseUrl = tracking?.watchtimeUrl
            if (baseUrl.isNullOrBlank()) {
                Log.d(TAG, "[$cpn] No videostatsWatchtimeUrl available for $videoId completion")
                return@withContext Result.success(false)
            }

            val durFormatted = String.format(Locale.US, "%.3f", durationSec)
            val params = mapOf(
                "cpn" to cpn,
                "state" to "ended",
                "cmt" to durFormatted,
                "st" to "0.000",
                "et" to durFormatted,
                "fver" to "2"
            )
            val targetUrl = buildTrackingUrl(baseUrl, params)
            val success = executeTrackingPing(targetUrl)
            Log.i(TAG, "[$cpn] Playback completion reported for $videoId (dur=$durFormatted): success=$success")
            sessionTrackingCache.remove(cpn)
            lastProgressSec.remove(cpn)
            Result.success(success)
        } catch (e: Throwable) {
            Log.w(TAG, "[$cpn] Error reporting playback completion: ${e.message}")
            Result.success(false)
        }
    }

    private fun buildTrackingUrl(baseUrl: String, params: Map<String, String>): String {
        val sb = StringBuilder(baseUrl)
        val separator = if (baseUrl.contains("?")) "&" else "?"
        var isFirst = true
        for ((key, value) in params) {
            if (isFirst) {
                sb.append(separator)
                isFirst = false
            } else {
                sb.append("&")
            }
            sb.append(java.net.URLEncoder.encode(key, "UTF-8"))
            sb.append("=")
            sb.append(java.net.URLEncoder.encode(value, "UTF-8"))
        }
        return sb.toString()
    }

    private fun executeTrackingPing(url: String): Boolean {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", YtMusicClient.USER_AGENT)
                .addHeader("X-YouTube-Client-Name", "67")
                .addHeader("X-YouTube-Client-Version", YtMusicClient.CLIENT_VERSION)
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")

            authHeaderProvider().forEach { (k, v) ->
                if (v.isNotBlank()) requestBuilder.header(k, v)
            }

            httpClient.newCall(requestBuilder.get().build()).execute().use { response ->
                response.isSuccessful || response.code in 200..299
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Tracking ping failed: ${e.message}")
            false
        }
    }
}
