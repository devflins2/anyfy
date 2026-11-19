package com.example.service.diagnostics

import android.os.SystemClock
import android.util.Log

object PlaybackLatencyTracker {
    private const val TAG = "AnyfyLatency"

    data class Session(
        val requestId: Long,
        val trackId: String,
        val trackTitle: String,
        val selectTimeMs: Long = SystemClock.elapsedRealtime(),
        var metadataLookupStartMs: Long? = null,
        var metadataLookupEndMs: Long? = null,
        var extractionStartMs: Long? = null,
        var extractionEndMs: Long? = null,
        var streamUrlResolvedMs: Long? = null,
        var httpConnectStartMs: Long? = null,
        var httpConnectedMs: Long? = null,
        var exoPlayerPrepareStartMs: Long? = null,
        var stateReadyMs: Long? = null,
        var firstAudioRenderedMs: Long? = null,
        var extractionSource: String? = null
    )

    @Volatile
    private var currentSession: Session? = null

    fun startSession(requestId: Long, trackId: String, trackTitle: String): Session {
        val session = Session(
            requestId = requestId,
            trackId = trackId,
            trackTitle = trackTitle
        )
        currentSession = session
        Log.i(TAG, "[$requestId] ▶ TRACK_SELECTED title=\"$trackTitle\" id=$trackId @ ${session.selectTimeMs}ms")
        return session
    }

    fun markExtractionStart(requestId: Long, source: String = "yt-dlp") {
        val s = currentSession ?: return
        if (s.requestId != requestId) return
        s.extractionStartMs = SystemClock.elapsedRealtime()
        s.extractionSource = source
        val delta = s.extractionStartMs!! - s.selectTimeMs
        Log.d(TAG, "[$requestId] ⏱ EXTRACTION_START source=$source (+${delta}ms from select)")
    }

    fun markExtractionEnd(requestId: Long, success: Boolean, details: String = "") {
        val s = currentSession ?: return
        if (s.requestId != requestId) return
        s.extractionEndMs = SystemClock.elapsedRealtime()
        val duration = s.extractionStartMs?.let { s.extractionEndMs!! - it } ?: -1L
        val totalDelta = s.extractionEndMs!! - s.selectTimeMs
        Log.d(TAG, "[$requestId] ⏱ EXTRACTION_END success=$success duration=${duration}ms (+${totalDelta}ms total) $details")
    }

    fun markStreamResolved(requestId: Long, url: String, source: String) {
        val s = currentSession ?: return
        if (s.requestId != requestId) return
        s.streamUrlResolvedMs = SystemClock.elapsedRealtime()
        s.extractionSource = source
        val totalDelta = s.streamUrlResolvedMs!! - s.selectTimeMs
        val cdn = when {
            url.contains("googlevideo") -> "googlevideo"
            else -> "direct"
        }
        Log.i(TAG, "[$requestId] ⏱ STREAM_RESOLVED source=$source cdn=$cdn (+${totalDelta}ms total)")
    }

    fun markExoPlayerPrepareStart(requestId: Long) {
        val s = currentSession ?: return
        if (s.requestId != requestId) return
        s.exoPlayerPrepareStartMs = SystemClock.elapsedRealtime()
        val delta = s.exoPlayerPrepareStartMs!! - s.selectTimeMs
        Log.d(TAG, "[$requestId] ⏱ EXOPLAYER_PREPARE_START (+${delta}ms from select)")
    }

    fun markHttpConnected(requestId: Long) {
        val s = currentSession ?: return
        if (s.requestId != requestId) return
        s.httpConnectedMs = SystemClock.elapsedRealtime()
        val delta = s.exoPlayerPrepareStartMs?.let { s.httpConnectedMs!! - it } ?: -1L
        Log.d(TAG, "[$requestId] ⏱ HTTP_CONNECTED (connection took ${delta}ms)")
    }

    fun markStateReady(requestId: Long) {
        val s = currentSession ?: return
        if (s.requestId != requestId) return
        s.stateReadyMs = SystemClock.elapsedRealtime()
        val prepDuration = s.exoPlayerPrepareStartMs?.let { s.stateReadyMs!! - it } ?: -1L
        val totalDelta = s.stateReadyMs!! - s.selectTimeMs
        Log.i(TAG, "[$requestId] ⏱ STATE_READY (ExoPlayer prep=${prepDuration}ms, total startup=${totalDelta}ms)")
    }

    fun markFirstAudio(requestId: Long) {
        val s = currentSession ?: return
        if (s.requestId != requestId) return
        if (s.firstAudioRenderedMs != null) return // Already marked
        s.firstAudioRenderedMs = SystemClock.elapsedRealtime()
        val totalStartupMs = s.firstAudioRenderedMs!! - s.selectTimeMs
        val prepTimeMs = s.exoPlayerPrepareStartMs?.let { s.firstAudioRenderedMs!! - it } ?: -1L
        val extractionTimeMs = if (s.extractionStartMs != null && s.extractionEndMs != null) {
            s.extractionEndMs!! - s.extractionStartMs!!
        } else 0L

        Log.i(TAG, """
            ========================================================================
            [ANYFY_LATENCY_REPORT] REQUEST #$requestId: "${s.trackTitle}"
            ------------------------------------------------------------------------
            • Extraction Source:    ${s.extractionSource ?: "direct/cached"}
            • Extraction Time:      ${extractionTimeMs} ms
            • ExoPlayer Prep Time:  ${prepTimeMs} ms
            • TOTAL TIME TO AUDIO:  ${totalStartupMs} ms (${String.format(java.util.Locale.US, "%.2f", totalStartupMs / 1000.0)}s)
            ========================================================================
        """.trimIndent())
    }

    fun getLastSession(): Session? = currentSession
}

