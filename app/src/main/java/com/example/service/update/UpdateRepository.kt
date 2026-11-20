package com.example.service.update

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface UpdateRepository {
    suspend fun fetchManifest(manifestUrl: String): Result<VersionManifest>
    fun getDismissedVersionCode(): Int
    fun setDismissedVersionCode(versionCode: Int)
    fun getLastCheckTimestamp(): Long
    fun setLastCheckTimestamp(timestamp: Long)
    fun getCachedManifest(): VersionManifest?
    fun isNetworkAvailable(): Boolean
}

class DefaultUpdateRepository(
    private val context: Context? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : UpdateRepository {

    companion object {
        private const val TAG = "UpdateRepository"
        val FALLBACK_MANIFEST_URLS = listOf(
            "https://github.com/devflins2/anyfy/releases/latest/download/version.json",
            "https://raw.githubusercontent.com/devflins2/anyfy/main/version.json"
        )
    }

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences("anyfy_update_prefs", Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedManifestMemory: VersionManifest? = null

    override fun isNetworkAvailable(): Boolean {
        if (context == null) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override suspend fun fetchManifest(manifestUrl: String): Result<VersionManifest> = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Network unavailable; skipping manifest fetch")
            return@withContext Result.failure(IllegalStateException("Network is unavailable"))
        }

        val urlsToTry = (listOf(manifestUrl) + FALLBACK_MANIFEST_URLS)
            .distinct()
            .filter { it.startsWith("https://", ignoreCase = true) }

        var lastException: Throwable? = null

        for (url in urlsToTry) {
            try {
                Log.i(TAG, "Fetching update manifest from: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Anyfy-Android-Updater")
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    Log.i(TAG, "HTTP $code response received from $url")
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP $code error from $url")
                        lastException = IllegalStateException("Server returned HTTP $code from $url")
                        return@use
                    }

                    val bodyString = response.body?.string()
                    if (bodyString.isNullOrBlank()) {
                        Log.w(TAG, "Empty response body received from $url")
                        lastException = IllegalStateException("Empty response body from $url")
                        return@use
                    }

                    val manifest = try {
                        VersionManifest.fromJson(bodyString)
                    } catch (e: Exception) {
                        Log.e(TAG, "JSON parsing error for manifest from $url: ${e.message}", e)
                        lastException = e
                        return@use
                    }

                    if (!manifest.isValid()) {
                        Log.w(TAG, "Invalid manifest schema from $url (versionCode=${manifest.versionCode}, sha256=${manifest.sha256}, apkUrl=${manifest.apkUrl})")
                        lastException = IllegalArgumentException("Invalid version manifest schema from $url")
                        return@use
                    }

                    Log.i(TAG, "Update manifest parsed successfully from $url: versionCode=${manifest.versionCode}, versionName=${manifest.versionName}, sha256=${manifest.sha256}, apkUrl=${manifest.apkUrl}")

                    // Cache in memory and SharedPreferences
                    cachedManifestMemory = manifest
                    prefs?.edit()
                        ?.putString("cached_manifest_json", bodyString)
                        ?.putLong("last_check_timestamp", System.currentTimeMillis())
                        ?.apply()

                    return@withContext Result.success(manifest)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception while fetching manifest from $url: ${e.message}", e)
                lastException = e
            }
        }

        Result.failure(lastException ?: IllegalStateException("Failed to fetch update manifest from all endpoints"))
    }

    override fun getDismissedVersionCode(): Int {
        return prefs?.getInt("dismissed_version_code", -1) ?: -1
    }

    override fun setDismissedVersionCode(versionCode: Int) {
        prefs?.edit()?.putInt("dismissed_version_code", versionCode)?.apply()
    }

    override fun getLastCheckTimestamp(): Long {
        return prefs?.getLong("last_check_timestamp", 0L) ?: 0L
    }

    override fun setLastCheckTimestamp(timestamp: Long) {
        prefs?.edit()?.putLong("last_check_timestamp", timestamp)?.apply()
    }

    override fun getCachedManifest(): VersionManifest? {
        if (cachedManifestMemory != null) return cachedManifestMemory
        val json = prefs?.getString("cached_manifest_json", null) ?: return null
        return try {
            val parsed = VersionManifest.fromJson(json)
            if (parsed.isValid()) {
                cachedManifestMemory = parsed
                parsed
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
