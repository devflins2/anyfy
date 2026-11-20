package com.example.service.cache

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-performance singleton cache manager for Media3 audio streaming.
 * Provides on-disk LRU caching of streaming chunks and background pre-buffering.
 */
object AudioCacheManager {
    private const val TAG = "AudioCacheManager"
    private const val MAX_CACHE_SIZE = 40 * 1024 * 1024L // 40 MB LRU disk cache (holds approx 12-15 full streams / 50+ preloads)
    const val PRELOAD_CHUNK_SIZE = 768 * 1024L // ~768 KB (approx 35-45s of audio for instant zero-delay playback)

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        val appCtx = context.applicationContext
        if (simpleCache == null) {
            val cacheDir = File(appCtx.cacheDir, "media3_audio_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE)
            val dbProvider = databaseProvider ?: StandaloneDatabaseProvider(appCtx).also {
                databaseProvider = it
            }
            simpleCache = SimpleCache(cacheDir, evictor, dbProvider)
            Log.d(TAG, "AudioCache initialized at ${cacheDir.absolutePath} with ${MAX_CACHE_SIZE / (1024 * 1024)}MB capacity")
        }
        return simpleCache!!
    }

    /**
     * Creates a CacheDataSource.Factory that wraps an upstream DataSource.Factory.
     * Automatically wraps non-DefaultDataSource factories with DefaultDataSource.Factory
     * to safely handle local cache file spans and non-HTTP URIs.
     */
    fun createCacheDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory
    ): CacheDataSource.Factory {
        val cache = getCache(context)
        val safeUpstream = if (upstreamFactory is androidx.media3.datasource.DefaultDataSource.Factory) {
            upstreamFactory
        } else {
            androidx.media3.datasource.DefaultDataSource.Factory(context, upstreamFactory)
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(safeUpstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Asynchronously preloads/buffers the initial chunk of the next song into disk cache.
     * Starts playback immediately without waiting for the full track to download.
     */
    suspend fun preloadTrackChunk(
        context: Context,
        upstreamFactory: DataSource.Factory,
        streamUrl: String,
        chunkBytes: Long = PRELOAD_CHUNK_SIZE
    ): Boolean = withContext(Dispatchers.IO) {
        if (streamUrl.isBlank()) return@withContext false
        try {
            val cache = getCache(context)
            val uri = Uri.parse(streamUrl)
            val safeUpstream = if (upstreamFactory is androidx.media3.datasource.DefaultDataSource.Factory) {
                upstreamFactory
            } else {
                androidx.media3.datasource.DefaultDataSource.Factory(context, upstreamFactory)
            }
            val cacheDataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(safeUpstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()

            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setLength(chunkBytes)
                .build()

            val writer = CacheWriter(cacheDataSource, dataSpec, null, null)
            writer.cache()
            Log.i(TAG, "Successfully pre-buffered ${chunkBytes / 1024} KB for next track: ${streamUrl.take(40)}...")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Pre-buffering chunk failed: ${e.message}")
            false
        }
    }

    /**
     * Release cache resources if needed on app termination.
     */
    @Synchronized
    fun release() {
        try {
            simpleCache?.release()
            simpleCache = null
            databaseProvider = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio cache: ${e.message}")
        }
    }
}

