package com.example.service.update

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : UpdateRepository {

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences("anyfy_update_prefs", Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedManifestMemory: VersionManifest? = null

    override fun isNetworkAvailable(): Boolean {
        if (context == null) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override suspend fun fetchManifest(manifestUrl: String): Result<VersionManifest> = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            return@withContext Result.failure(IllegalStateException("Network is unavailable"))
        }

        if (!manifestUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Manifest URL must use secure HTTPS: $manifestUrl"))
        }

        try {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("User-Agent", "Anyfy-Android-Updater")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Server returned HTTP ${response.code} when fetching update manifest")
                    )
                }

                val bodyString = response.body?.string()
                if (bodyString.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException("Empty response received from manifest URL"))
                }

                val manifest = VersionManifest.fromJson(bodyString)
                if (!manifest.isValid()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid version manifest schema or insecure checksum/APK URL")
                    )
                }

                // Cache in memory and SharedPreferences
                cachedManifestMemory = manifest
                prefs?.edit()
                    ?.putString("cached_manifest_json", bodyString)
                    ?.putLong("last_check_timestamp", System.currentTimeMillis())
                    ?.apply()

                Result.success(manifest)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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
