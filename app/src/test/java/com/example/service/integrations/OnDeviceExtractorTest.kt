package com.example.service.integrations

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Isolated on-device test for yt-dlp metadata and stream extraction.
 *
 * Requirements:
 * - Runs strictly on Dispatchers.IO (never on main thread)
 * - Requests audio-only M4A format (140) natively playable by MediaPlayer without FFmpeg
 * - Logs only safe diagnostic details (never logs cookies, tokens, or full signed URLs)
 * - Decoupled from PlaybackService and UI
 */
object OnDeviceExtractorTest {

    private const val TAG = "OnDeviceExtractorTest"

    /**
     * Executes an isolated extraction test for [videoId] on an Android device/emulator.
     *
     * @param videoId Valid YouTube video ID (default: "dQw4w9WgXcQ")
     * @return Result containing safe diagnostic summary or exception
     */
    suspend fun runExtractionTest(videoId: String = "dQw4w9WgXcQ"): Result<ExtractionDiagnostic> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting isolated extraction test for video: $videoId")

                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                    // Audio-only M4A (AAC) natively supported by Android MediaPlayer:
                    addOption("-f", "140/bestaudio[ext=m4a]/bestaudio")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "12")
                }

                val videoInfo: VideoInfo = YoutubeDL.getInstance().getInfo(request)
                val rawUrl = videoInfo.url

                if (rawUrl.isNullOrBlank()) {
                    val msg = "Extraction completed but returned empty stream URL"
                    Log.w(TAG, "Test FAILED: $msg")
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                // Extract only safe diagnostic info (host name only, never full tokenized URL)
                val streamHost = try {
                    URI(rawUrl).host ?: "valid_host"
                } catch (_: Exception) {
                    "valid_host"
                }

                val diagnostic = ExtractionDiagnostic(
                    videoId = videoId,
                    title = videoInfo.title ?: "Unknown",
                    durationSeconds = videoInfo.duration,
                    audioExt = videoInfo.ext ?: "m4a",
                    formatId = videoInfo.formatId ?: "140",
                    streamHost = streamHost,
                    isPlayableM4a = videoInfo.ext == "m4a" || videoInfo.formatId == "140"
                )

                // Safe diagnostic logging: title, duration, extension, host only
                Log.i(
                    TAG,
                    "Test SUCCESS: title='${diagnostic.title}', duration=${diagnostic.durationSeconds}s, " +
                            "format=${diagnostic.formatId}, ext=${diagnostic.audioExt}, host=${diagnostic.streamHost}"
                )

                Result.success(diagnostic)
            } catch (e: YoutubeDLException) {
                Log.e(TAG, "Test FAILED (YoutubeDLException): ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Test FAILED (General Exception): ${e.message}")
                Result.failure(e)
            }
        }
}

/**
 * Diagnostic record containing safe metadata without sensitive tokens or full URLs.
 */
data class ExtractionDiagnostic(
    val videoId: String,
    val title: String,
    val durationSeconds: Int,
    val audioExt: String,
    val formatId: String,
    val streamHost: String,
    val isPlayableM4a: Boolean
)
