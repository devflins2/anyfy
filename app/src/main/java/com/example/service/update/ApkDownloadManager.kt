package com.example.service.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface ApkDownloadManager {
    suspend fun downloadApk(
        manifest: VersionManifest,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progressPercent: Int, speedBytesPerSec: Long) -> Unit
    ): Result<File>
    fun cancelDownload()
    fun getExistingDownloadedApk(manifest: VersionManifest): File?
    fun deleteDownloadedApk(manifest: VersionManifest)
}

class DefaultApkDownloadManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : ApkDownloadManager {

    private val isCancelled = AtomicBoolean(false)
    private var activeCall: okhttp3.Call? = null

    private fun getUpdatesDir(): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getTargetApkFile(manifest: VersionManifest): File {
        val safeName = "Anyfy-v${manifest.versionName}-${manifest.versionCode}.apk"
        return File(getUpdatesDir(), safeName)
    }

    private fun getTempApkFile(manifest: VersionManifest): File {
        val safeName = "Anyfy-v${manifest.versionName}-${manifest.versionCode}.apk.tmp"
        return File(getUpdatesDir(), safeName)
    }

    override fun getExistingDownloadedApk(manifest: VersionManifest): File? {
        val file = getTargetApkFile(manifest)
        return if (file.exists() && file.length() > 0) file else null
    }

    override fun deleteDownloadedApk(manifest: VersionManifest) {
        val file = getTargetApkFile(manifest)
        if (file.exists()) file.delete()
        val tmp = getTempApkFile(manifest)
        if (tmp.exists()) tmp.delete()
    }

    override fun cancelDownload() {
        isCancelled.set(true)
        activeCall?.cancel()
    }

    override suspend fun downloadApk(
        manifest: VersionManifest,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progressPercent: Int, speedBytesPerSec: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled.set(false)

        if (!manifest.apkUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(
                IllegalArgumentException("Insecure APK download URL rejected: ${manifest.apkUrl}")
            )
        }

        val targetFile = getTargetApkFile(manifest)
        val tempFile = getTempApkFile(manifest)

        // Clean up partial files from prior runs
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val request = Request.Builder()
            .url(manifest.apkUrl)
            .header("User-Agent", "Anyfy-Android-Updater")
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code} downloading update APK")
                    )
                }

                val body = response.body ?: return@withContext Result.failure(
                    IOException("Empty HTTP body received for APK download")
                )

                val contentLength = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgressUpdate = System.currentTimeMillis()
                var bytesSinceLastSpeedCalc = 0L
                var currentSpeed = 0L

                outputStream.use { out ->
                    inputStream.use { input ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled.get() || !currentCoroutineContext().isActive) {
                                tempFile.delete()
                                return@withContext Result.failure(IOException("Download cancelled by user"))
                            }

                            out.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            bytesSinceLastSpeedCalc += bytesRead

                            val now = System.currentTimeMillis()
                            val timeDiff = now - lastProgressUpdate
                            if (timeDiff >= 150L) { // update progress every 150ms
                                currentSpeed = if (timeDiff > 0) (bytesSinceLastSpeedCalc * 1000) / timeDiff else 0L
                                val percent = if (contentLength > 0) {
                                    ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                } else -1

                                onProgress(totalBytesRead, contentLength, percent, currentSpeed)
                                lastProgressUpdate = now
                                bytesSinceLastSpeedCalc = 0L
                            }
                        }
                    }
                }

                // Final 100% progress notification
                onProgress(totalBytesRead, totalBytesRead, 100, 0L)

                // Atomic rename to target file
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    // Fallback to copy and delete
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                Result.success(targetFile)
            }
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            Result.failure(e)
        } finally {
            activeCall = null
        }
    }
}
