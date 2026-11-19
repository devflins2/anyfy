/*
 * Copyright (C) 2024–2026 Nguyễn Đức Tuấn Minh (maxrave-dev) <ndtminh2608@gmail.com>
 * Adapted from SimpMusic lyricsService for Anyfy.
 *
 * Licensed under the GNU General Public License Version 3.0 (GPL-3.0-only).
 */

package com.example.service.lyrics

import android.content.Context
import android.util.Log
import com.example.model.LyricsLine
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * High-performance lyrics service providing synchronized timed lyrics and plain lyrics.
 * Integrates LrcLib and SimpMusic lyrics endpoints with disk and memory caching.
 */
class LyricsRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LyricsRepository"
        private const val LRCLIB_URL = "https://lrclib.net/api/get"
        private const val LRCLIB_SEARCH_URL = "https://lrclib.net/api/search"
        private const val SIMPMUSIC_LYRICS_URL = "https://api-lyrics.simpmusic.org/v1/lyrics"
        private const val USER_AGENT = "Anyfy/2.0 (Android; SimpMusic-Engine)"

        @Volatile
        private var instance: LyricsRepository? = null

        fun getInstance(context: Context): LyricsRepository {
            return instance ?: synchronized(this) {
                instance ?: LyricsRepository(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Parses standard LRC timed lyrics format: [mm:ss.xx] Lyric text
         * Supports multiple timestamps per line and varied millisecond precisions.
         */
        fun parseLrc(lrcText: String): List<LyricsLine> {
            val lines = mutableListOf<LyricsLine>()
            val tagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:,](\d{1,3}))?\]""")

            for (rawLine in lrcText.lines()) {
                val trimmed = rawLine.trim()
                if (trimmed.isBlank()) continue

                val matches = tagRegex.findAll(trimmed).toList()
                if (matches.isEmpty()) continue

                val lastMatch = matches.last()
                val text = trimmed.substring(lastMatch.range.last + 1).trim()
                if (text.isBlank()) continue

                for (match in matches) {
                    val mins = match.groupValues[1].toLongOrNull() ?: 0L
                    val secs = match.groupValues[2].toLongOrNull() ?: 0L
                    val rawMillis = match.groupValues.getOrNull(3) ?: ""
                    val millis = when (rawMillis.length) {
                        1 -> rawMillis.toLongOrNull()?.times(100) ?: 0L
                        2 -> rawMillis.toLongOrNull()?.times(10) ?: 0L
                        3 -> rawMillis.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    val timeMs = (mins * 60 + secs) * 1000L + millis
                    lines.add(LyricsLine(timeMs = timeMs, text = text))
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        /**
         * Converts plain text lyrics into sequential LyricsLine entries without fake timestamps.
         * timeMs is set to -1L to distinguish plain text lyrics from synchronized lyrics.
         */
        fun parsePlainLyrics(plainText: String, durationSeconds: Int = 180): List<LyricsLine> {
            val rawLines = plainText.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (rawLines.isEmpty()) return emptyList()
            return rawLines.map { text ->
                LyricsLine(timeMs = -1L, text = text)
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val memoryCache = ConcurrentHashMap<String, List<LyricsLine>>()
    private val lyricsDir = File(context.cacheDir, "lyrics_cache").apply { if (!exists()) mkdirs() }

    /**
     * Resolves synchronized timed lyrics for [track].
     * Returns empty list if track is instrumental or lyrics are unavailable.
     */
    suspend fun getLyricsForTrack(track: Track): List<LyricsLine> = withContext(Dispatchers.IO) {
        val cacheKey = "lyr_${track.id.removePrefix("yt_")}"

        // 1. Check in-memory cache
        memoryCache[cacheKey]?.let { return@withContext it }

        // 2. Check disk cache
        loadFromDisk(cacheKey)?.let {
            memoryCache[cacheKey] = it
            return@withContext it
        }

        // Clean query terms
        val cleanTitle = cleanTrackTitle(track.title)
        val cleanArtist = cleanArtistName(track.artist)
        val duration = track.durationSeconds

        var result: List<LyricsLine> = emptyList()

        // 3. Try primary source: LrcLib /api/get
        try {
            val lrcUrl = buildString {
                append(LRCLIB_URL)
                append("?track_name=").append(URLEncoder.encode(cleanTitle, "UTF-8"))
                append("&artist_name=").append(URLEncoder.encode(cleanArtist, "UTF-8"))
                if (duration > 0) {
                    append("&duration=").append(duration)
                }
            }
            val json = executeGetJson(lrcUrl)
            if (json != null) {
                result = parseLrcLibResponse(json, duration)
            }
        } catch (e: Exception) {
            Log.d(TAG, "LrcLib get failed for ${track.title}: ${e.message}")
        }

        // 4. Try secondary source: LrcLib search query
        if (result.isEmpty()) {
            try {
                val searchUrl = "$LRCLIB_SEARCH_URL?q=" + URLEncoder.encode("$cleanTitle $cleanArtist", "UTF-8")
                val jsonArr = executeGetJsonArray(searchUrl)
                if (jsonArr != null && jsonArr.length() > 0) {
                    // Pick candidate closest in duration
                    var bestIdx = 0
                    var minDelta = Long.MAX_VALUE
                    for (i in 0 until jsonArr.length()) {
                        val item = jsonArr.optJSONObject(i) ?: continue
                        val candDur = item.optInt("duration", 0)
                        val delta = if (duration > 0) Math.abs(candDur - duration).toLong() else 0L
                        if (delta < minDelta) {
                            minDelta = delta
                            bestIdx = i
                        }
                    }
                    val bestObj = jsonArr.optJSONObject(bestIdx)
                    if (bestObj != null) {
                        result = parseLrcLibResponse(bestObj, duration)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "LrcLib search failed for ${track.title}: ${e.message}")
            }
        }

        // 5. Try tertiary source: SimpMusic lyrics API by YouTube Video ID
        if (result.isEmpty()) {
            val cleanVid = extractVideoId(track.id)
            if (cleanVid.isNotBlank()) {
                try {
                    val simpUrl = "$SIMPMUSIC_LYRICS_URL?videoId=$cleanVid"
                    val jsonArr = executeGetJsonArray(simpUrl)
                    if (jsonArr != null && jsonArr.length() > 0) {
                        val first = jsonArr.optJSONObject(0)
                        val lrc = first?.optString("syncedLyrics")
                        if (!lrc.isNullOrBlank()) {
                            result = parseLrc(lrc)
                        } else {
                            val plain = first?.optString("plainLyrics")
                            if (!plain.isNullOrBlank()) {
                                result = parsePlainLyrics(plain, duration)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "SimpMusic lyrics failed for $cleanVid: ${e.message}")
                }
            }
        }

        if (result.isNotEmpty()) {
            memoryCache[cacheKey] = result
            saveToDisk(cacheKey, result)
        }

        result
    }

    private fun parseLrcLibResponse(json: JSONObject, durationSeconds: Int): List<LyricsLine> {
        if (json.optBoolean("instrumental", false)) {
            return emptyList()
        }
        val synced = json.optString("syncedLyrics", "")
        if (synced.isNotBlank()) {
            val parsed = parseLrc(synced)
            if (parsed.isNotEmpty()) return parsed
        }
        val plain = json.optString("plainLyrics", "")
        if (plain.isNotBlank()) {
            return parsePlainLyrics(plain, durationSeconds)
        }
        return emptyList()
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("""\s*[\(\[](?:Official|Music|Video|Audio|Lyric|Remix|Live|HD|4K)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .split("•").firstOrNull()
            ?.split(",")?.firstOrNull()
            ?.replace("YouTube Music", "")
            ?.trim() ?: artist
    }

    private fun extractVideoId(trackId: String): String {
        val clean = trackId.removePrefix("yt_")
        return if (clean.length == 11 && clean.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) clean else ""
    }

    private fun executeGetJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return JSONObject(body)
        }
    }

    private fun executeGetJsonArray(url: String): JSONArray? {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return JSONArray(body)
        }
    }

    private fun saveToDisk(key: String, lines: List<LyricsLine>) {
        try {
            val file = File(lyricsDir, "$key.json")
            val arr = JSONArray()
            for (line in lines) {
                arr.put(JSONObject().apply {
                    put("timeMs", line.timeMs)
                    put("text", line.text)
                })
            }
            file.writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun loadFromDisk(key: String): List<LyricsLine>? {
        return try {
            val file = File(lyricsDir, "$key.json")
            if (!file.exists()) return null
            val text = file.readText(Charsets.UTF_8)
            val arr = JSONArray(text)
            val list = mutableListOf<LyricsLine>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val timeMs = obj.optLong("timeMs", 0L)
                val lineText = obj.optString("text", "")
                if (lineText.isNotBlank()) {
                    list.add(LyricsLine(timeMs = timeMs, text = lineText))
                }
            }
            list.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}

