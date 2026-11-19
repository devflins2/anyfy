/*
 * Copyright (C) 2024–2026 Nguyễn Đức Tuấn Minh (maxrave-dev) <ndtminh2608@gmail.com>
 * Adapted from SimpMusic ReturnYouTubeDislike integration for Anyfy.
 *
 * Licensed under the GNU General Public License Version 3.0 (GPL-3.0-only).
 */

package com.example.service.sponsorblock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class VideoVotes(
    val videoId: String,
    val likes: Int,
    val dislikes: Int,
    val rating: Double,
    val viewCount: Long
)

class ReturnYouTubeDislikeRepository private constructor() {

    companion object {
        private const val TAG = "RYDRepository"
        private const val RYD_API = "https://returnyoutubedislikeapi.com/Votes"
        private const val USER_AGENT = "Anyfy/2.0 (Android; SimpMusic-Engine)"

        @Volatile
        private var instance: ReturnYouTubeDislikeRepository? = null

        fun getInstance(): ReturnYouTubeDislikeRepository {
            return instance ?: synchronized(this) {
                instance ?: ReturnYouTubeDislikeRepository().also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, VideoVotes>()

    suspend fun getVotes(videoId: String): VideoVotes? = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (cleanId.isEmpty()) return@withContext null

        cache[cleanId]?.let { return@withContext it }

        try {
            val url = "$RYD_API?videoId=$cleanId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val votes = VideoVotes(
                videoId = cleanId,
                likes = json.optInt("likes", 0),
                dislikes = json.optInt("dislikes", 0),
                rating = json.optDouble("rating", 0.0),
                viewCount = json.optLong("viewCount", 0L)
            )

            cache[cleanId] = votes
            votes
        } catch (e: Exception) {
            Log.d(TAG, "RYD fetch skipped for $cleanId: ${e.message}")
            null
        }
    }
}

