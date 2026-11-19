package com.example.service.cache

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent disk-backed LRU cache for resolved streaming URLs.
 * Eliminates repeated on-device yt-dlp extraction across app restarts and repeat plays.
 * Follows the SimpMusic architecture for URL caching with TTL verification.
 */
class StreamUrlDiskCache private constructor(context: Context) {

    companion object {
        private const val TAG = "StreamUrlDiskCache"
        private const val PREFS_NAME = "anyfy_stream_url_cache"
        private const val KEY_PREFIX_URL = "url_"
        private const val KEY_PREFIX_EXPIRY = "exp_"
        private const val KEY_PREFIX_SOURCE = "src_"
        private const val KEY_PREFIX_CACHED_AT = "cat_"
        private const val DEFAULT_TTL_MS = 4 * 60 * 60 * 1000L // 4 Hours

        @Volatile
        private var instance: StreamUrlDiskCache? = null

        fun getInstance(context: Context): StreamUrlDiskCache {
            return instance ?: synchronized(this) {
                instance ?: StreamUrlDiskCache(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<String, CachedStreamEntry>()

    data class CachedStreamEntry(
        val url: String,
        val expiryMs: Long,
        val source: String,
        val cachedAt: Long
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() >= (expiryMs - 180_000L) // 3 min safety margin
    }

    init {
        // Load active cached entries into memory on startup
        try {
            val all = prefs.all
            val now = System.currentTimeMillis()
            all.keys.filter { it.startsWith(KEY_PREFIX_URL) }.forEach { key ->
                val videoId = key.removePrefix(KEY_PREFIX_URL)
                val url = prefs.getString(key, null)
                val exp = prefs.getLong(KEY_PREFIX_EXPIRY + videoId, 0L)
                val src = prefs.getString(KEY_PREFIX_SOURCE + videoId, "yt-dlp") ?: "yt-dlp"
                val cat = prefs.getLong(KEY_PREFIX_CACHED_AT + videoId, now)
                if (!url.isNullOrBlank() && exp > (now + 180_000L)) {
                    memoryCache[videoId] = CachedStreamEntry(url, exp, src, cat)
                } else if (exp <= now) {
                    // Evict expired entry from disk
                    evict(videoId)
                }
            }
            Log.d(TAG, "StreamUrlDiskCache loaded ${memoryCache.size} active entries into memory")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load cached stream entries: ${e.message}")
        }
    }

    /**
     * Retrieves valid cached stream URL for [videoId].
     * Returns null if missing or expired.
     */
    fun get(videoId: String): String? {
        val cleanId = videoId.removePrefix("yt_").trim()
        val entry = memoryCache[cleanId] ?: loadFromDisk(cleanId) ?: return null
        if (entry.isExpired) {
            evict(cleanId)
            return null
        }
        return entry.url
    }

    /**
     * Stores resolved stream URL for [videoId] with expiration.
     */
    fun put(videoId: String, url: String, source: String = "yt-dlp") {
        if (url.isBlank()) return
        val cleanId = videoId.removePrefix("yt_").trim()
        val expiryMs = parseExpiryFromUrl(url)
        val now = System.currentTimeMillis()
        val entry = CachedStreamEntry(url, expiryMs, source, now)
        memoryCache[cleanId] = entry

        try {
            prefs.edit()
                .putString(KEY_PREFIX_URL + cleanId, url)
                .putLong(KEY_PREFIX_EXPIRY + cleanId, expiryMs)
                .putString(KEY_PREFIX_SOURCE + cleanId, source)
                .putLong(KEY_PREFIX_CACHED_AT + cleanId, now)
                .apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to persist stream URL for $cleanId: ${e.message}")
        }
    }

    fun remove(videoId: String) {
        val cleanId = videoId.removePrefix("yt_").trim()
        evict(cleanId)
    }

    fun clear() {
        memoryCache.clear()
        try {
            prefs.edit().clear().apply()
        } catch (_: Throwable) {}
    }

    private fun evict(videoId: String) {
        memoryCache.remove(videoId)
        try {
            prefs.edit()
                .remove(KEY_PREFIX_URL + videoId)
                .remove(KEY_PREFIX_EXPIRY + videoId)
                .remove(KEY_PREFIX_SOURCE + videoId)
                .remove(KEY_PREFIX_CACHED_AT + videoId)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun loadFromDisk(cleanId: String): CachedStreamEntry? {
        val url = prefs.getString(KEY_PREFIX_URL + cleanId, null) ?: return null
        val exp = prefs.getLong(KEY_PREFIX_EXPIRY + cleanId, 0L)
        val src = prefs.getString(KEY_PREFIX_SOURCE + cleanId, "yt-dlp") ?: "yt-dlp"
        val cat = prefs.getLong(KEY_PREFIX_CACHED_AT + cleanId, System.currentTimeMillis())
        val entry = CachedStreamEntry(url, exp, src, cat)
        if (entry.isExpired) {
            evict(cleanId)
            return null
        }
        memoryCache[cleanId] = entry
        return entry
    }

    private fun parseExpiryFromUrl(url: String): Long {
        return try {
            val match = Regex("""[?&]expire=(\d+)""").find(url)
            if (match != null) {
                val expireSec = match.groupValues[1].toLongOrNull()
                if (expireSec != null) {
                    return expireSec * 1000L
                }
            }
            System.currentTimeMillis() + DEFAULT_TTL_MS
        } catch (_: Throwable) {
            System.currentTimeMillis() + DEFAULT_TTL_MS
        }
    }
}

