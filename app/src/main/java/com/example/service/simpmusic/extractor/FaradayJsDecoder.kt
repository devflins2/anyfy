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
import com.example.service.simpmusic.cipher.FaradayCipherEngine
import com.example.service.simpmusic.cipher.InnerTubeLogger
import dev.maxrave.pipepipe.extractor.exceptions.ParsingException
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeApiDecoder
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeJavaScriptDecoder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.Executors

private const val TAG = "FaradayJsDecoder"
private const val JS_THREAD_STACK_BYTES = 32L * 1024L * 1024L

/**
 * Plugs [FaradayCipherEngine] into PipePipe as its local decoder, so signature and `n` challenges
 * are solved on the device instead of at `api.pipepipe.dev`.
 */
internal class FaradayJsDecoder(
    client: OkHttpClient = OkHttpClient()
) : YoutubeJavaScriptDecoder {
    @Volatile
    var lastOutcome: String = "unused"
        private set

    val lastOutcomeLabel: String
        get() =
            when {
                lastOutcome.startsWith("local") -> "local"
                lastOutcome.startsWith("fallback") -> "pipepipe.dev"
                else -> "cached"
            }

    private val jsThread =
        Executors
            .newSingleThreadExecutor { runnable -> Thread(null, runnable, "QuickJs", JS_THREAD_STACK_BYTES) }
            .asCoroutineDispatcher()

    private val engine by lazy { FaradayCipherEngine(client, jsThread, InnerTubeLogger.ANDROID_LOGCAT) }

    override fun getPlayerData(videoId: String): YoutubeJavaScriptDecoder.PlayerData {
        val info =
            runBlocking { engine.playerInfo() }
                ?: run {
                    lastOutcome = "fallback:no-player-metadata"
                    throw ParsingException("Faraday: no player metadata")
                }
        return YoutubeJavaScriptDecoder.PlayerData(info.playerId, info.signatureTimestamp)
    }

    override fun decodeBatch(
        playerId: String,
        signatures: List<String>?,
        throttlingParameters: List<String>?,
    ): YoutubeApiDecoder.BatchDecodeResult {
        val wantedSignatures = signatures.orEmpty().distinct()
        val wantedNParameters = throttlingParameters.orEmpty().distinct()
        val result = runBlocking { engine.decode(playerId, wantedSignatures, wantedNParameters) }

        val missing =
            wantedSignatures.count { it !in result.signatures } +
                wantedNParameters.count { it !in result.nParameters }
        val wanted = wantedSignatures.size + wantedNParameters.size
        if (missing > 0) {
            lastOutcome = "fallback:$missing-of-$wanted-unsolved"
            throw ParsingException("Faraday: $missing of $wanted unsolved")
        }
        lastOutcome = "local:sig=${wantedSignatures.size},n=${wantedNParameters.size}"
        Log.d(TAG, "solved $wanted locally (sig=${wantedSignatures.size} n=${wantedNParameters.size})")
        return YoutubeApiDecoder.BatchDecodeResult(result.signatures, result.nParameters)
    }

    fun invalidate() = runBlocking { engine.invalidate() }
}

