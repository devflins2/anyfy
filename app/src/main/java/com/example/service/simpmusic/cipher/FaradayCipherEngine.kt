/*
 * Copyright (C) 2024-2026 maxrave-dev (SimpMusic)
 * Ported to Anyfy under GNU General Public License v3.0 (GPL-3.0)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.example.service.simpmusic.cipher

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Solves YouTube's signature and `n` challenges locally, using the remote player table published at
 * [PLAYER_CONFIG_URL].
 *
 * The table does NOT contain decoded signatures — it only names the function and class inside
 * YouTube's own player JS that perform the work. The player script is downloaded and executed
 * here in QuickJS.
 */
class FaradayCipherEngine(
    private val httpClient: OkHttpClient,
    private val jsThread: CoroutineDispatcher,
    private val logger: InnerTubeLogger = InnerTubeLogger.NONE,
) {
    data class PlayerInfo(
        val playerId: String,
        val signatureTimestamp: Int,
    )

    data class DecodeResult(
        val signatures: Map<String, String>,
        val nParameters: Map<String, String>,
    )

    private val directClient: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5000L, TimeUnit.MILLISECONDS)
        .readTimeout(8000L, TimeUnit.MILLISECONDS)
        .build()

    // Our table first, Metrolist's to fill the hashes ours has not published yet.
    private val stores =
        listOf(PLAYER_CONFIG_URL, METROLIST_PLAYER_CONFIG_URL).map {
            RemotePlayerConfigStore(httpClient, InMemoryPlayerConfigRepository(it), logger)
        }

    private val operationMutex = Mutex()
    private val mutex = Mutex()
    private var cachedPlayerId: String? = null
    private var cachedPlayerIdAtMs: Long = 0L
    private var cachedSolver: CachedSolver? = null

    private class CachedSolver(
        val playerId: String,
        val configEpoch: Long,
        val solver: ZemerCipherSolver,
    )

    suspend fun playerInfo(): PlayerInfo? = operationMutex.withLock { playerInfoLocked() }

    private suspend fun playerInfoLocked(): PlayerInfo? {
        val playerId = currentPlayerId() ?: return null
        val timestamp =
            stores.firstNotNullOfOrNull { it.getSignatureTimestamp(playerUrlFor(playerId)) } ?: return null
        return PlayerInfo(playerId, timestamp)
    }

    suspend fun decode(
        playerId: String,
        signatures: List<String>,
        nParameters: List<String>,
    ): DecodeResult = operationMutex.withLock { decodeLocked(playerId, signatures, nParameters) }

    private suspend fun decodeLocked(
        playerId: String,
        signatures: List<String>,
        nParameters: List<String>,
    ): DecodeResult {
        if (signatures.isEmpty() && nParameters.isEmpty()) return DecodeResult(emptyMap(), emptyMap())
        val solver = solverFor(playerId) ?: return DecodeResult(emptyMap(), emptyMap())
        return DecodeResult(
            signatures =
                signatures
                    .distinct()
                    .mapNotNull { challenge ->
                        solver.solveSignature(challenge)?.let { challenge to it }
                    }.toMap(),
            nParameters =
                nParameters
                    .distinct()
                    .mapNotNull { challenge ->
                        solver.solveN(challenge)?.let { challenge to it }
                    }.toMap(),
        )
    }

    suspend fun invalidate() = operationMutex.withLock { invalidateLocked() }

    private suspend fun invalidateLocked() {
        mutex.withLock {
            cachedPlayerId = null
            cachedPlayerIdAtMs = 0L
            cachedSolver?.solver?.dispose()
            cachedSolver = null
        }
        stores.forEach { runCatching { it.refreshAfterStreamRejection() } }
    }

    private suspend fun currentPlayerId(): String? {
        val now = System.currentTimeMillis()
        mutex.withLock {
            cachedPlayerId?.takeIf { now - cachedPlayerIdAtMs < PLAYER_ID_TTL_MS }?.let { return it }
        }
        val fetched = fetchPlayerId() ?: return null
        mutex.withLock {
            cachedPlayerId = fetched
            cachedPlayerIdAtMs = System.currentTimeMillis()
        }
        return fetched
    }

    private suspend fun fetchPlayerId(): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(IFRAME_API_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .build()
            directClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (body == null) {
                    logger.w(TAG, "iframe_api returned HTTP ${resp.code}")
                    null
                } else {
                    PLAYER_HASH_REGEX.find(body)?.groupValues?.getOrNull(1)
                        ?: run {
                            logger.w(TAG, "player hash not found in iframe_api")
                            null
                        }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(TAG, "iframe_api fetch failed", details = mapOf("exceptionType" to (error::class.simpleName ?: "Exception")))
            null
        }
    }

    private suspend fun solverFor(playerId: String): ZemerCipherSolver? {
        if (!PLAYER_ID_REGEX.matches(playerId)) return null
        val playerUrl = playerUrlFor(playerId)

        var config = stores.firstNotNullOfOrNull { it.getConfig(playerUrl) }
        if (config == null) {
            stores.forEach { it.forceRefresh(missingHash = playerId) }
            config = stores.firstNotNullOfOrNull { it.getConfig(playerUrl) }
        }
        if (config == null) {
            logger.d(TAG, "player not in remote table", details = mapOf("player" to playerId))
            return null
        }

        val epoch = stores.sumOf { it.configEpoch }
        mutex.withLock {
            cachedSolver
                ?.takeIf { it.playerId == playerId && it.configEpoch == epoch }
                ?.let { return it.solver }
        }

        return try {
            val playerCode = downloadPlayerCode(playerUrl) ?: return null
            val created = ZemerCipherSolver.create(playerCode, config, jsThread)
            val replaced =
                mutex.withLock {
                    val previous = cachedSolver
                    cachedSolver = CachedSolver(playerId, epoch, created)
                    previous
                }
            replaced?.solver?.dispose()
            created
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(
                TAG,
                "solver build failed",
                details = mapOf("player" to playerId, "exceptionType" to (error::class.simpleName ?: "Exception")),
            )
            null
        }
    }

    private suspend fun downloadPlayerCode(playerUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(playerUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Referer", "https://www.youtube.com/")
                .build()
            directClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (body == null) {
                    logger.w(TAG, "player script HTTP ${resp.code}")
                    null
                } else {
                    body
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(TAG, "player script fetch failed", details = mapOf("exceptionType" to (error::class.simpleName ?: "Exception")))
            null
        }
    }

    companion object {
        private const val TAG = "FaradayCipherEngine"

        const val PLAYER_CONFIG_URL: String =
            "https://raw.githubusercontent.com/maxrave-dev/simpmusic-files/main/registry/player_configs.json"

        const val METROLIST_PLAYER_CONFIG_URL: String =
            "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json"

        private const val IFRAME_API_URL = "https://www.youtube.com/iframe_api"
        private const val USER_AGENT = "okhttp/5.4.0"
        private const val PLAYER_ID_TTL_MS = 30 * 60 * 1000L

        private val PLAYER_HASH_REGEX = Regex("""player[/\\]([a-z0-9]{8})[/\\]""")
        private val PLAYER_ID_REGEX = Regex("^[A-Za-z0-9_-]{4,32}$")

        internal fun playerUrlFor(playerId: String): String =
            "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_GB/base.js"
    }
}

internal class InMemoryPlayerConfigRepository(
    private val url: String,
) : PlayerConfigRepository {
    override val enabled: Boolean = true
    override val sourceUrl: String = url
    override val defaultSourceUrl: String = url
    override var cachedJson: String = ""
    override var cachedAtMs: Long = 0L
    override var cachedSourceUrl: String = ""
    override var cachedEtag: String = ""
}

