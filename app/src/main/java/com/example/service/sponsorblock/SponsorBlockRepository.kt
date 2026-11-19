/*
 * Copyright (C) 2024–2026 Nguyễn Đức Tuấn Minh (maxrave-dev) <ndtminh2608@gmail.com>
 * Adapted from SimpMusic SponsorBlock integration for Anyfy.
 *
 * Licensed under the GNU General Public License Version 3.0 (GPL-3.0-only).
 */

package com.example.service.sponsorblock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String
)

class SponsorBlockRepository private constructor() {

    companion object {
        private const val TAG = "SponsorBlockRepo"
        private const val SPONSOR_BLOCK_API = "https://sponsor.ajay.app/api/skipSegments/"
        private const val USER_AGENT = "Anyfy/2.0 (Android; SimpMusic-Engine)"

        @Volatile
        private var instance: SponsorBlockRepository? = null

        fun getInstance(): SponsorBlockRepository {
            return instance ?: synchronized(this) {
                instance ?: SponsorBlockRepository().also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    suspend fun getSkipSegments(videoId: String): List<SponsorSegment> = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (cleanId.isEmpty()) return@withContext emptyList()

        cache[cleanId]?.let { return@withContext it }

        try {
            val url = "$SPONSOR_BLOCK_API?videoID=$cleanId&category=sponsor&category=selfpromo&category=interaction&category=intro&category=outro&category=preview&category=music_offtopic&category=filler"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                cache[cleanId] = emptyList()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            val jsonArray = JSONArray(body)
            val segments = mutableListOf<SponsorSegment>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val category = item.optString("category", "sponsor")
                val segArray = item.optJSONArray("segment") ?: continue
                if (segArray.length() >= 2) {
                    val startSec = segArray.optDouble(0, 0.0)
                    val endSec = segArray.optDouble(1, 0.0)
                    val startMs = (startSec * 1000).toLong()
                    val endMs = (endSec * 1000).toLong()

                    if (endMs > startMs && (endMs - startMs) >= 1000L) {
                        segments.add(SponsorSegment(startMs, endMs, category))
                    }
                }
            }

            cache[cleanId] = segments
            segments
        } catch (e: Exception) {
            Log.d(TAG, "SponsorBlock fetch skipped or failed for $cleanId: ${e.message}")
            emptyList()
        }
    }
}

