package com.example.service.diagnostics

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Diagnostic logger specifically for song player background banner resolution.
 * Captures:
 * 1. Current song/video ID
 * 2. Associated album ID
 * 3. Available candidate thumbnail count
 * 4. Selected artwork URL (sanitized)
 * 5. Image loading success/failure
 * 6. Exact fallback level used (1 through 5)
 */
object BannerDiagnosticLogger {
    private const val TAG = "AnyfyBannerDiag"
    private const val MAX_HISTORY = 100

    data class BannerLogEntry(
        val timestampMs: Long = System.currentTimeMillis(),
        val trackId: String,
        val albumId: String,
        val availableThumbnailCount: Int,
        val selectedArtworkUrl: String,
        val success: Boolean,
        val fallbackLevel: String,
        val errorMessage: String? = null
    ) {
        override fun toString(): String {
            val status = if (success) "SUCCESS" else "FAILED (${errorMessage ?: "unknown"})"
            return "[BannerDiag] trackId=$trackId, albumId=$albumId, availableCount=$availableThumbnailCount, level=$fallbackLevel, status=$status, url=$selectedArtworkUrl"
        }
    }

    private val logHistory = ConcurrentLinkedQueue<BannerLogEntry>()

    fun logBannerLoaded(
        trackId: String,
        albumId: String,
        availableThumbnailCount: Int,
        selectedArtworkUrl: String,
        success: Boolean,
        fallbackLevel: String,
        errorMessage: String? = null
    ) {
        val sanitizedUrl = sanitizeUrl(selectedArtworkUrl)
        val entry = BannerLogEntry(
            trackId = trackId,
            albumId = albumId,
            availableThumbnailCount = availableThumbnailCount,
            selectedArtworkUrl = sanitizedUrl,
            success = success,
            fallbackLevel = fallbackLevel,
            errorMessage = errorMessage
        )
        logHistory.add(entry)
        while (logHistory.size > MAX_HISTORY) {
            logHistory.poll()
        }

        if (success) {
            Log.i(TAG, entry.toString())
        } else {
            Log.w(TAG, entry.toString())
        }
    }

    fun getHistory(): List<BannerLogEntry> = logHistory.toList()

    fun clear() {
        logHistory.clear()
    }

    private fun sanitizeUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val path = uri.path ?: ""
            val host = uri.host ?: ""
            if (host.isNotBlank()) "$host$path" else url.take(60)
        } catch (_: Throwable) {
            url.take(60)
        }
    }
}
