/*
 * Copyright (C) 2024-2026 maxrave-dev (SimpMusic)
 * Ported to Anyfy under GNU General Public License v3.0 (GPL-3.0)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.example.service.simpmusic.extractor

import android.util.Log
import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.ServiceList
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeApiDecoder
import dev.maxrave.pipepipe.extractor.stream.StreamInfo
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe as BraveNewPipe
import org.schabi.newpipe.extractor.ServiceList as BraveServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo as BraveStreamInfo

private const val TAG = "SimpMusicExtractor"
private const val LOCAL_TIER = "local"
private const val REMOTE_TIER = "pipepipe.dev"

/**
 * Port of SimpMusic's Android Extractor engine.
 *
 * Executes a deterministic 3-tier stream resolution:
 * - Tier 1: Local on-device cipher and n-parameter challenge solution via QuickJS (FaradayJsDecoder)
 * - Tier 2: Remote challenge resolution via api.pipepipe.dev (when local decoder fails or URL is 403)
 * - Tier 3: BravePipe fallback extractor
 */
class SimpMusicExtractor(
    baseClient: OkHttpClient = OkHttpClient()
) {
    private val newPipeDownloader = NewPipeDownloaderImpl(proxy = null, baseClient = baseClient)
    private val braveNewPipeDownloader = BraveNewPipeDownloaderImpl(proxy = null, baseClient = baseClient)
    private val faradayDecoder = FaradayJsDecoder(baseClient)

    @Volatile
    private var isInitialized = false

    @Volatile
    var lastSource: String = "none"
        private set

    fun init() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                NewPipe.init(newPipeDownloader)
                BraveNewPipe.init(braveNewPipeDownloader)
                YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
                isInitialized = true
                Log.i(TAG, "SimpMusicExtractor initialized successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize SimpMusicExtractor: ${e.message}", e)
            }
        }
    }

    fun logIn(cookie: String?) {
        try {
            ServiceList.YouTube.tokens = cookie ?: ""
            Log.d(TAG, "YouTube tokens updated (cookie present=${!cookie.isNullOrBlank()})")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to set YouTube tokens: ${e.message}")
        }
    }

    /**
     * Resolves playable streams for the given [videoId] across the 3 SimpMusic tiers.
     * Returns a list of (itag -> streamUrl) pairs.
     */
    fun resolveStreams(videoId: String): List<Pair<Int, String>> {
        init()

        // Tier 1 — Ciphers solved locally with QuickJS
        YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
        val tier1 = pipePipeStreams(videoId, LOCAL_TIER)
        if (!tier1.isNullOrEmpty()) {
            lastSource = "PipePipe · ${faradayDecoder.lastOutcomeLabel}"
            return tier1
        }

        // Tier 2 — PipePipe with remote solver (api.pipepipe.dev)
        YoutubeApiDecoder.setLocalDecoder(null)
        val tier2 = pipePipeStreams(videoId, REMOTE_TIER)
        if (!tier2.isNullOrEmpty()) {
            lastSource = "PipePipe · $REMOTE_TIER"
            return tier2
        }

        // Tier 3 — BravePipe fallback
        val tier3 = braveStreams(videoId)
        if (tier3.isNotEmpty()) {
            lastSource = "BravePipe"
            return tier3
        }

        lastSource = "Extraction Failed"
        return emptyList()
    }

    private fun pipePipeStreams(
        videoId: String,
        tier: String,
    ): List<Pair<Int, String>>? {
        try {
            val streamInfo =
                StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            val pipeResult =
                streamsList.mapNotNull {
                    (it.itagItem?.id ?: return@mapNotNull null) to it.content
                }
            if (!pipeResult.hasRequiredItags()) {
                Log.d(TAG, "PipePipe[$tier] missing required itags for $videoId (got=${pipeResult.map { it.first }})")
                return null
            }
            if (!pipeResult.headCheckRandomStream()) {
                Log.d(TAG, "PipePipe[$tier] stream URL HEAD check failed (non 2xx) for $videoId")
                if (tier == LOCAL_TIER) faradayDecoder.invalidate()
                return null
            }
            Log.d(TAG, "extract source=PipePipe[$tier] itags=${pipeResult.map { it.first }} for $videoId")
            return pipeResult
        } catch (e: Throwable) {
            Log.w(TAG, "PipePipe[$tier] extractor failed for $videoId: ${e.message}")
            return null
        }
    }

    private fun braveStreams(videoId: String): List<Pair<Int, String>> =
        runCatching {
            val streamInfo =
                BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            streamsList.mapNotNull {
                (it.itagItem?.id ?: return@mapNotNull null) to it.content
            }.also {
                Log.d(TAG, "extract source=BravePipe itags=${it.map { pair -> pair.first }} for $videoId")
            }
        }.onFailure {
            Log.w(TAG, "BravePipe extractor failed for $videoId: ${it.message}")
        }.getOrElse { emptyList() }
}

