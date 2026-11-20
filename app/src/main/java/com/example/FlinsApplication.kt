package com.example

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Custom Application class for FlinsMusic / Anyfy.
 * Handles non-blocking background initialization and optimized Coil image caching.
 */
class FlinsApplication : Application(), ImageLoaderFactory {

    companion object {
        private const val TAG = "FlinsApplication"

        @Volatile
        var instance: FlinsApplication? = null
            private set
    }

    private val coroutineExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception in application scope: ${throwable.message}", throwable)
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashProtection()
        com.example.service.diagnostics.PlaybackDiagnosticLogger.init(this)
        initFallbackFirebase()
        initOnDeviceYoutubeDl()
        cleanExpiredTemporaryFiles()
    }

    /**
     * Installs application-wide crash protection against background service lifecycle race conditions,
     * such as Android 12/14 ForegroundServiceStartNotAllowedException when receiving playback events
     * while the device is transitioning or locked.
     */
    private fun installCrashProtection() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            val exceptionName = throwable.javaClass.name
            val message = throwable.message ?: ""
            if (throwable is java.util.concurrent.CancellationException ||
                exceptionName.contains("ForegroundServiceStartNotAllowedException") ||
                (throwable is IllegalStateException && message.contains("startForeground"))
            ) {
                Log.w(TAG, "Intercepted and suppressed background service startup exception to protect playback session: $message")
                return@setDefaultUncaughtExceptionHandler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val host = original.url.host
                val builder = original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                if (host.contains("youtube.com") || host.contains("ytimg.com")) {
                    builder.header("Referer", "https://music.youtube.com/")
                }
                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20) // Cap memory usage at 20% of available app heap
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(24L * 1024 * 1024) // 24 MB disk cache for album artwork (approx 800+ albums)
                    .build()
            }
            .respectCacheHeaders(false) // Cache cover artwork reliably across sessions
            .crossfade(true)
            .build()
    }

    /**
     * Initializes a fallback FirebaseApp instance if google-services.json is absent.
     * Prevents downstream IllegalStateException when Firebase components are queried.
     */
    private fun initFallbackFirebase() {
        try {
            if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
                val options = com.google.firebase.FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.flinsmusic.zrqv")
                    .setApiKey("AIzaSyFakeKeyForFlinsMusic")
                    .setProjectId("flinsmusic")
                    .build()
                com.google.firebase.FirebaseApp.initializeApp(this, options)
                Log.i(TAG, "Fallback FirebaseApp initialized successfully.")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Fallback Firebase initialization skipped: ${e.message}")
        }
    }

    /**
     * Initializes the embedded on-device yt-dlp Python runtime asynchronously.
     * Keeps execution strictly off the main thread to prevent UI freezing.
     */
    private fun initOnDeviceYoutubeDl() {
        applicationScope.launch {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                Log.i(TAG, "On-device YoutubeDL runtime initialized successfully.")
            } catch (e: Throwable) {
                // Graceful error logging without exposing secrets or crashing app startup
                Log.e(TAG, "Failed to initialize on-device YoutubeDL: ${e.message}")
            }
        }
    }

    /**
     * Periodically cleans up expired temporary files and incomplete update downloads
     * from cacheDir without touching active media playback or image caches.
     */
    private fun cleanExpiredTemporaryFiles() {
        applicationScope.launch {
            try {
                // 1. Clean up temporary diagnostic share exports
                val diagFile = java.io.File(cacheDir, "anyfy_playback_diagnostics.txt")
                if (diagFile.exists()) diagFile.delete()

                // 2. Clean up orphaned incomplete update downloads in cacheDir/updates/
                val updatesDir = java.io.File(cacheDir, "updates")
                if (updatesDir.exists() && updatesDir.isDirectory) {
                    val files = updatesDir.listFiles() ?: emptyArray()
                    val oneDayAgo = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(1)
                    for (file in files) {
                        if (file.name.endsWith(".tmp") || (file.lastModified() < oneDayAgo)) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Temporary cache cleanup skipped: ${e.message}")
            }
        }
    }
}
