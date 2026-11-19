/*
 * Copyright (C) 2024-2026 maxrave-dev (SimpMusic)
 * Ported to Anyfy under GNU General Public License v3.0 (GPL-3.0)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.example.service.simpmusic.repository

import android.util.Log
import com.example.service.simpmusic.ITAG
import com.example.service.simpmusic.extractor.SimpMusicExtractor
import com.example.service.simpmusic.extractor.USER_AGENT_WEB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "SimpMusicStreamRepo"

data class StreamFormat(
    val videoId: String,
    val itag: Int,
    val audioUrl: String,
    val expiredTimeMs: Long,
    val source: String
)

/**
 * Port of SimpMusic's StreamRepository.
 *
 * Manages stream resolution, format caching, proactive HTTP HEAD 403 validation,
 * and seamless fallback across audio quality tiers.
 */
class SimpMusicStreamRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val extractor: SimpMusicExtractor = SimpMusicExtractor(client)
) {
    private val formatCache = ConcurrentHashMap<String, StreamFormat>()

    // YouTube URLs typically expire in 6 hours (21600s). We default to 5.5 hours to be safe.
    private val DEFAULT_TTL_MS = 5 * 60 * 60 * 1000L + 30 * 60 * 1000L

    fun logIn(cookie: String?) {
        extractor.logIn(cookie)
    }

    fun getLastSource(): String = extractor.lastSource

    fun isCached(videoId: String): Boolean {
        val format = formatCache[videoId] ?: return false
        val now = System.currentTimeMillis()
        if (now >= format.expiredTimeMs) {
            formatCache.remove(videoId)
            return false
        }
        return true
    }

    /**
     * Proactively checks whether the given stream URL is rejected (HTTP 403 or 4xx) by YouTube CDN.
     * Matches SimpMusic's `is403Url` implementation.
     */
    fun is403Url(url: String): Boolean {
        return runCatching {
            val request = Request.Builder()
                .head()
                .url(url)
                .addHeader("User-Agent", USER_AGENT_WEB)
                .build()
            client.newCall(request).execute().use { response ->
                response.code in 400..499
            }
        }.getOrDefault(false)
    }

    suspend fun getStreamUrl(videoId: String, forceRefresh: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // 1. Check format cache unless forceRefresh
        if (!forceRefresh) {
            val cached = formatCache[videoId]
            if (cached != null) {
                if (now < cached.expiredTimeMs) {
                    // SimpMusic validation: test if URL is still valid and not 403
                    if (!is403Url(cached.audioUrl)) {
                        Log.d(TAG, "Cache HIT for $videoId (source=${cached.source})")
                        return@withContext Result.success(cached.audioUrl)
                    } else {
                        Log.w(TAG, "Cached URL for $videoId returned 403, invalidating format")
                        formatCache.remove(videoId)
                    }
                } else {
                    Log.d(TAG, "Cached URL for $videoId expired, refreshing")
                    formatCache.remove(videoId)
                }
            }
        } else {
            formatCache.remove(videoId)
        }

        // 2. Resolve streams via SimpMusic 3-tier extractor
        try {
            val streams = extractor.resolveStreams(videoId)
            if (streams.isEmpty()) {
                val err = "Extractor returned no playable streams for $videoId (source=${extractor.lastSource})"
                Log.w(TAG, err)
                return@withContext Result.failure(Exception(err))
            }

            // 3. Select best audio format matching SimpMusic priority
            val selected = selectBestAudioStream(streams)
            if (selected == null) {
                val err = "No compatible audio itag found for $videoId among: ${streams.map { it.first }}"
                Log.w(TAG, err)
                return@withContext Result.failure(Exception(err))
            }

            val (itag, url) = selected
            val expiryMs = parseUrlExpiry(url) ?: (now + DEFAULT_TTL_MS)

            val format = StreamFormat(
                videoId = videoId,
                itag = itag,
                audioUrl = url,
                expiredTimeMs = expiryMs,
                source = extractor.lastSource
            )
            formatCache[videoId] = format
            Log.i(TAG, "Successfully resolved stream for $videoId (itag=$itag source=${extractor.lastSource})")
            Result.success(url)
        } catch (e: Throwable) {
            Log.e(TAG, "Stream resolution exception for $videoId: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun selectBestAudioStream(streams: List<Pair<Int, String>>): Pair<Int, String>? {
        val streamMap = streams.toMap()
        for (preferredItag in ITAG.PREFERRED_AUDIO_ITAGS) {
            val url = streamMap[preferredItag]
            if (!url.isNullOrBlank()) {
                return preferredItag to url
            }
        }
        // Fallback: any audio stream in ITAG.AUDIO
        for ((itag, url) in streams) {
            if (itag in ITAG.AUDIO && url.isNotBlank()) {
                return itag to url
            }
        }
        // Final fallback: first available stream
        return streams.firstOrNull { it.second.isNotBlank() }
    }

    private fun parseUrlExpiry(url: String): Long? {
        return runCatching {
            val match = Regex("""[?&]expire=(\d+)""").find(url)
            match?.groupValues?.get(1)?.toLongOrNull()?.let { expSec ->
                // Guard against premature expiration by subtracting 3 minutes buffer
                (expSec * 1000L) - (180 * 1000L)
            }
        }.getOrNull()
    }
}

