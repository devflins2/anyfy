package com.example.service.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.media3.common.PlaybackException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * High-precision, safe, and persistent diagnostic logging engine for audio streaming and playback.
 * Captures all critical diagnostic points per playback attempt:
 * 1. Track title, ID, and video ID.
 * 2. Active YouTube Music account/session status (sanitized, zero secrets).
 * 3. Extraction start, duration, and detailed source (InnerTube /player vs on-device yt-dlp).
 * 4. Extraction exception and full cause chain.
 * 5. Playable audio URL status, host, and expiry check.
 * 6. DataSource User-Agent sent and audio format (codec, MIME type, bitrate, itag).
 * 7. HTTP response status and sanitized response headers.
 * 8. Byte-range support (Accept-Ranges / 206 Partial Content).
 * 9. First audio bytes received and whether request was rejected before any bytes arrived.
 * 10. Media3 state transitions and recursive exception cause chain.
 * 11. Stream expiry and auth rejection check.
 *
 * Persists event and session history to disk so logs survive app process restarts.
 * Provides in-app export via clipboard copy and system Share Sheet with FileProvider.
 */
object PlaybackDiagnosticLogger {
    private const val TAG = "AnyfyPlaybackDiag"
    private const val MAX_LOG_ENTRIES = 300
    private const val MAX_PERSISTED_FILE_SIZE = 512 * 1024L // 512 KB
    private const val LOG_FILE_NAME = "anyfy_diagnostic_history.log"
    private const val SESSIONS_FILE_NAME = "anyfy_playback_sessions.log"

    data class DiagnosticSession(
        val requestId: Long,
        val trackId: String,
        val trackTitle: String,
        val videoId: String?,
        var accountStatus: String = "Unknown",
        var hasActiveAccount: Boolean = false,
        var extractionStartedAtMs: Long = 0L,
        var extractionCompletedAtMs: Long = 0L,
        var extractionDurationMs: Long = 0L,
        var extractionSource: String = "none",
        var extractionSourceDetailed: String = "none",
        var isGeneratedByInnerTube: Boolean = false,
        var isGeneratedByYtDlp: Boolean = false,
        var extractionSuccess: Boolean = false,
        var extractionError: String? = null,
        var hasPlayableUrl: Boolean = false,
        var urlHost: String? = null,
        var isUrlExpired: Boolean = false,
        var isAuthRejected: Boolean = false,
        var userAgentSent: String? = null,
        var formatItag: Int? = null,
        var formatMimeType: String? = null,
        var formatCodecs: String? = null,
        var formatBitrate: Int? = null,
        var formatSampleRate: Int? = null,
        var httpStatus: Int? = null,
        var serverSupportsByteRange: Boolean = false,
        var responseHeadersSummary: String? = null,
        var firstAudioBytesLoaded: Long = 0L,
        var firstAudioBytesTimeMs: Long = 0L,
        var rejectedBeforeBytes: Boolean = false,
        var readFailures: String? = null,
        var lastPlayerState: String = "IDLE",
        var exactPlaybackException: String? = null,
        var fullExceptionCauseChain: String? = null,
        val traceEvents: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    )

    private val sessions = ConcurrentHashMap<Long, DiagnosticSession>()
    private val rollingLogs = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileLock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentActiveRequestId: Long = 0L

    fun getActiveRequestId(): Long = currentActiveRequestId

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun getEffectiveContext(): Context? {
        return appContext ?: com.example.FlinsApplication.instance
    }

    private fun getLogFile(): File? {
        val ctx = getEffectiveContext() ?: return null
        return try {
            File(ctx.filesDir, LOG_FILE_NAME)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSessionsFile(): File? {
        val ctx = getEffectiveContext() ?: return null
        return try {
            File(ctx.filesDir, SESSIONS_FILE_NAME)
        } catch (_: Throwable) {
            null
        }
    }

    private fun appendToDisk(logLine: String) {
        synchronized(fileLock) {
            try {
                val file = getLogFile() ?: return
                if (file.exists() && file.length() > MAX_PERSISTED_FILE_SIZE) {
                    val lines = file.readLines(Charsets.UTF_8)
                    val half = lines.takeLast(lines.size / 2)
                    file.writeText(half.joinToString("\n") + "\n", Charsets.UTF_8)
                }
                file.appendText(logLine + "\n", Charsets.UTF_8)
            } catch (_: Throwable) {}
        }
    }

    private fun appendSessionToDisk(reportText: String) {
        synchronized(fileLock) {
            try {
                val file = getSessionsFile() ?: return
                if (file.exists() && file.length() > MAX_PERSISTED_FILE_SIZE) {
                    val lines = file.readLines(Charsets.UTF_8)
                    val half = lines.takeLast(lines.size / 2)
                    file.writeText(half.joinToString("\n") + "\n", Charsets.UTF_8)
                }
                file.appendText(reportText + "\n\n", Charsets.UTF_8)
            } catch (_: Throwable) {}
        }
    }

    private fun recordLog(level: Int, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $message"
        rollingLogs.add(logLine)
        while (rollingLogs.size > MAX_LOG_ENTRIES) {
            rollingLogs.poll()
        }
        appendToDisk(logLine)
        when (level) {
            Log.ERROR -> Log.e(tag, message)
            Log.WARN -> Log.w(tag, message)
            else -> Log.i(tag, message)
        }
    }

    /**
     * Recursively traverses and sanitizes an exception cause chain (up to 10 depth).
     * Extracts exact exception classes, messages, and HTTP status codes.
     */
    fun buildFullCauseChain(t: Throwable?): String {
        if (t == null) return "None"
        val sb = StringBuilder()
        var current: Throwable? = t
        var depth = 0
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (current != null && depth < 10) {
            if (!seen.add(current)) {
                sb.append(" -> [Cyclic reference to ${current.javaClass.simpleName}]")
                break
            }
            if (depth > 0) sb.append(" -> ")
            val simpleName = current.javaClass.simpleName.ifBlank { current.javaClass.name }
            val msg = current.message?.replace("\n", " ")?.take(140) ?: "no message"
            sb.append("[$simpleName: $msg]")
            if (current is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                sb.append(" (HTTP ${current.responseCode})")
            }
            current = current.cause
            depth++
        }
        return sb.toString()
    }

    /**
     * 1. Selected song title and video ID
     */
    fun startSession(requestId: Long, trackId: String, title: String, videoId: String?) {
        currentActiveRequestId = requestId
        val session = DiagnosticSession(
            requestId = requestId,
            trackId = trackId,
            trackTitle = title.take(60),
            videoId = videoId
        )
        sessions[requestId] = session
        recordLog(Log.INFO, TAG, "[#$requestId] [1. TRACK] Selected title=\"${session.trackTitle}\", id=\"$trackId\", videoId=\"$videoId\"")
    }

    /**
     * 2. Active YouTube Music account/session status (strictly sanitized, zero secrets)
     */
    fun logAccountStatus(requestId: Long, hasAccount: Boolean, accountName: String?, hasCookies: Boolean) {
        val session = sessions[requestId]
        val statusStr = if (hasAccount) "Authenticated (name=\"${accountName ?: "User"}\", cookiesPresent=$hasCookies)" else "Anonymous / Guest"
        session?.accountStatus = statusStr
        session?.hasActiveAccount = hasAccount
        recordLog(Log.INFO, TAG, "[#$requestId] [2. ACCOUNT] Status: $statusStr")
    }

    /**
     * 3. Extraction start and initial source
     */
    fun logExtractionStart(requestId: Long, source: String) {
        val session = sessions[requestId]
        session?.extractionStartedAtMs = System.currentTimeMillis()
        session?.extractionSource = source
        recordLog(Log.INFO, TAG, "[#$requestId] [3. EXTRACTION START] Source=$source")
    }

    /**
     * 3, 4, 5. Extraction completion, detailed source check (InnerTube vs yt-dlp), and playable URL check
     */
    fun logExtractionComplete(
        requestId: Long,
        success: Boolean,
        url: String?,
        error: String?,
        detailedSource: String? = null
    ) {
        val session = sessions[requestId]
        val duration = if (session != null && session.extractionStartedAtMs > 0) System.currentTimeMillis() - session.extractionStartedAtMs else 0L
        session?.extractionCompletedAtMs = System.currentTimeMillis()
        session?.extractionDurationMs = duration
        session?.extractionSuccess = success
        session?.extractionError = error

        val effectiveDetailedSource = detailedSource ?: session?.extractionSource ?: "unknown"
        session?.extractionSourceDetailed = effectiveDetailedSource

        val isInnerTube = effectiveDetailedSource.contains("InnerTube", ignoreCase = true) ||
                effectiveDetailedSource.contains("innertube", ignoreCase = true)
        val isYtDlp = effectiveDetailedSource.contains("yt-dlp", ignoreCase = true) ||
                effectiveDetailedSource.contains("ytsearch", ignoreCase = true)

        session?.isGeneratedByInnerTube = isInnerTube
        session?.isGeneratedByYtDlp = isYtDlp

        if (success && !url.isNullOrBlank()) {
            session?.hasPlayableUrl = true
            val host = try { java.net.URI(url).host ?: "direct" } catch (_: Throwable) { "direct" }
            session?.urlHost = host
            recordLog(Log.INFO, TAG, "[#$requestId] [3. EXTRACTION DONE] Duration=${duration}ms, Source=$effectiveDetailedSource (InnerTube=$isInnerTube, yt-dlp=$isYtDlp), Host=$host")
            recordLog(Log.INFO, TAG, "[#$requestId] [5. PLAYABLE URL] Returned=true, Host=$host, Length=${url.length}")
        } else {
            session?.hasPlayableUrl = false
            recordLog(Log.WARN, TAG, "[#$requestId] [3. EXTRACTION FAILED] Duration=${duration}ms, Source=$effectiveDetailedSource, Error=${error ?: "none"}")
            recordLog(Log.WARN, TAG, "[#$requestId] [4. EXTRACTION EXCEPTION] Sanitized error: ${error ?: "Unknown failure"}")
            recordLog(Log.WARN, TAG, "[#$requestId] [5. PLAYABLE URL] Returned=false")
        }
    }

    /**
     * Request User-Agent and outgoing request headers (sanitized, zero tokens)
     */
    fun logUserAgentSent(requestId: Long, userAgent: String?, headers: Map<String, String>?) {
        val session = sessions[requestId]
        session?.userAgentSent = userAgent
        val safeHeaders = headers?.filterKeys {
            !it.equals("cookie", ignoreCase = true) &&
            !it.equals("authorization", ignoreCase = true) &&
            !it.equals("sapisid", ignoreCase = true)
        }
        recordLog(Log.INFO, TAG, "[#$requestId] [REQUEST CONFIG] User-Agent=\"$userAgent\", Headers=$safeHeaders")
    }

    /**
     * 6. Selected audio format, codec, MIME type, bitrate, sample rate
     */
    fun logFormatDetails(requestId: Long, itag: Int?, mimeType: String?, codecs: String?, bitrate: Int?, sampleRate: Int?) {
        val session = sessions[requestId]
        session?.formatItag = itag
        session?.formatMimeType = mimeType
        session?.formatCodecs = codecs
        session?.formatBitrate = bitrate
        session?.formatSampleRate = sampleRate
        recordLog(Log.INFO, TAG, "[#$requestId] [6. FORMAT] itag=$itag, mimeType=$mimeType, codecs=$codecs, bitrate=${bitrate?.div(1000)}kbps, sampleRate=${sampleRate}Hz")
    }

    /**
     * 7, 8, 11. HTTP response status, sanitized response headers, byte-range support, and auth rejection check
     */
    fun logHttpResponse(requestId: Long, statusCode: Int, headers: Map<String, List<String>>?) {
        val session = sessions[requestId]
        session?.httpStatus = statusCode

        val acceptRanges = headers?.get("accept-ranges")?.firstOrNull() ?: headers?.get("Accept-Ranges")?.firstOrNull()
        val contentRange = headers?.get("content-range")?.firstOrNull() ?: headers?.get("Content-Range")?.firstOrNull()
        val contentType = headers?.get("content-type")?.firstOrNull() ?: headers?.get("Content-Type")?.firstOrNull()
        val contentLength = headers?.get("content-length")?.firstOrNull() ?: headers?.get("Content-Length")?.firstOrNull()
        val server = headers?.get("server")?.firstOrNull() ?: headers?.get("Server")?.firstOrNull()

        val supportsByteRange = statusCode == 206 || acceptRanges?.contains("bytes", ignoreCase = true) == true || contentRange != null
        session?.serverSupportsByteRange = supportsByteRange

        val summary = "status=$statusCode, contentType=$contentType, contentLength=$contentLength, acceptRanges=$acceptRanges, contentRange=$contentRange, server=$server"
        session?.responseHeadersSummary = summary

        recordLog(Log.INFO, TAG, "[#$requestId] [7. HTTP RESPONSE] Status=$statusCode, Headers={$summary}")
        recordLog(Log.INFO, TAG, "[#$requestId] [8. BYTE RANGE] Server supports byte-range requests: $supportsByteRange")

        if (statusCode == 401 || statusCode == 403) {
            session?.isAuthRejected = true
            recordLog(Log.ERROR, TAG, "[#$requestId] [11. AUTH REJECTION] HTTP $statusCode Forbidden / Unauthorized from stream server")
        }
    }

    /**
     * 9. First audio bytes received
     */
    fun logFirstBytes(requestId: Long, bytesLoaded: Long) {
        val session = sessions[requestId]
        session?.firstAudioBytesLoaded = bytesLoaded
        session?.firstAudioBytesTimeMs = System.currentTimeMillis()
        session?.rejectedBeforeBytes = false
        recordLog(Log.INFO, TAG, "[#$requestId] [9. FIRST AUDIO BYTES] Received $bytesLoaded bytes from stream")
    }

    /**
     * 9. Stream read failure and rejectedBeforeBytes flag
     */
    fun logStreamReadFailure(requestId: Long, error: String, rejectedBeforeBytes: Boolean? = null) {
        val session = sessions[requestId]
        session?.readFailures = error
        if (rejectedBeforeBytes != null) {
            session?.rejectedBeforeBytes = rejectedBeforeBytes
        } else if (session != null && session.firstAudioBytesLoaded == 0L) {
            session.rejectedBeforeBytes = true
        }
        recordLog(Log.ERROR, TAG, "[#$requestId] [9. STREAM READ FAILURE] Error: $error, rejectedBeforeBytes=${session?.rejectedBeforeBytes}")
    }

    /**
     * 10. Media3/ExoPlayer state transitions
     */
    fun logStateTransition(requestId: Long, oldState: String, newState: String) {
        val session = sessions[requestId]
        session?.lastPlayerState = newState
        recordLog(Log.INFO, TAG, "[#$requestId] [10. STATE TRANSITION] $oldState -> $newState")
    }

    /**
     * 10. Exact PlaybackException and recursive cause chain
     */
    fun logPlaybackException(requestId: Long, error: PlaybackException) {
        val session = sessions[requestId]
        val causeChain = buildFullCauseChain(error)
        val errorStr = "${error.errorCodeName} (${error.errorCode}): ${error.message} | CauseChain: $causeChain"
        session?.exactPlaybackException = errorStr
        session?.fullExceptionCauseChain = causeChain
        if (session != null && session.firstAudioBytesLoaded == 0L) {
            session.rejectedBeforeBytes = true
        }
        recordLog(Log.ERROR, TAG, "[#$requestId] [10. PLAYBACK EXCEPTION] Exact error: $errorStr")
    }

    /**
     * 11. Stream URL expiration check
     */
    fun logUrlExpiryCheck(requestId: Long, url: String?, isExpired: Boolean) {
        val session = sessions[requestId]
        session?.isUrlExpired = isExpired
        recordLog(Log.INFO, TAG, "[#$requestId] [11. URL EXPIRY] Stream URL isExpired=$isExpired (urlPresent=${!url.isNullOrBlank()})")
    }

    /**
     * 10-Point Core Execution Trace Logging (Priority 1)
     */
    fun logTraceStep(
        requestId: Long,
        stepIndex: Int,
        stepName: String,
        method: String,
        status: String,
        durationMs: Long = 0L,
        details: String? = null,
        error: String? = null
    ) {
        val s = sessions[requestId]
        val durStr = if (durationMs > 0) " (${durationMs}ms)" else ""
        val detStr = if (!details.isNullOrBlank()) " | $details" else ""
        val errStr = if (!error.isNullOrBlank()) " | Error: $error" else ""
        val traceEntry = "[STEP $stepIndex: $stepName] $status via $method$durStr$detStr$errStr"
        s?.traceEvents?.add(traceEntry)
        recordLog(if (status == "FAILED") Log.WARN else Log.INFO, TAG, "[#$requestId] $traceEntry")
    }

    fun trace1_SongSelection(requestId: Long, trackId: String, title: String) {
        logTraceStep(requestId, 1, "Song Selection Callback", "onTrackSelected", "COMPLETED", details = "title=\"$title\", id=$trackId")
    }

    fun trace2_PlayRequest(requestId: Long, trackId: String, isRemote: Boolean) {
        logTraceStep(requestId, 2, "PlaybackService Play Request", "playTrack", "COMPLETED", details = "trackId=$trackId, remote=$isRemote")
    }

    fun trace3_StreamResolutionStart(requestId: Long, trackId: String) {
        logTraceStep(requestId, 3, "Stream Resolution Invocation", "prepareAudioSource", "STARTED", details = "trackId=$trackId")
    }

    fun trace4_InnerTubeAttempt(requestId: Long, clientName: String, success: Boolean, durationMs: Long, error: String? = null) {
        val status = if (success) "COMPLETED" else "FAILED"
        logTraceStep(requestId, 4, "InnerTube Extraction Attempt", "YtMusicClient.resolveDirectAudioStreamUrl", status, durationMs, details = "client=$clientName", error = error)
    }

    fun trace5_YtDlpAttempt(requestId: Long, success: Boolean, durationMs: Long, error: String? = null) {
        val status = if (success) "COMPLETED" else "FAILED"
        logTraceStep(requestId, 5, "On-Device yt-dlp Fallback Invocation", "YoutubeDL.execute", status, durationMs, error = error)
    }

    fun trace6_ExtractionComplete(requestId: Long, source: String, success: Boolean, durationMs: Long, error: String? = null) {
        val status = if (success) "COMPLETED" else "FAILED"
        logTraceStep(requestId, 6, "Extraction Completion Callback", "resolveTrackStream", status, durationMs, details = "source=$source", error = error)
    }

    fun trace7_PlayableUrlValidation(requestId: Long, isValid: Boolean, isExpired: Boolean, isPreview: Boolean, host: String?) {
        val status = if (isValid) "COMPLETED" else "FAILED"
        logTraceStep(requestId, 7, "Playable URL Validation", "validateAudioUrl", status, details = "valid=$isValid, expired=$isExpired, preview=$isPreview, host=$host")
    }

    fun trace8_MediaItemCreation(requestId: Long, mediaId: String, userAgent: String?) {
        logTraceStep(requestId, 8, "Media3 MediaItem Creation", "DefaultMediaSourceFactory", "COMPLETED", details = "mediaId=$mediaId, ua=$userAgent")
    }

    fun trace9_ExoPlayerPrepare(requestId: Long) {
        logTraceStep(requestId, 9, "ExoPlayer setMediaSource & Prepare", "exoPlayer.prepare", "COMPLETED")
    }

    fun trace10_PlaybackState(requestId: Long, state: String, isPlaying: Boolean, positionMs: Long) {
        logTraceStep(requestId, 10, "STATE_READY and Actual Playback", "Player.Listener", "STATE_$state", details = "playing=$isPlaying, pos=${positionMs}ms")
    }

    private fun formatSessionReport(s: DiagnosticSession): String {
        val sb = StringBuilder()
        sb.appendLine("1. Track: '${s.trackTitle}' (id=${s.trackId}, vid=${s.videoId})")
        sb.appendLine("2. Account Status: ${s.accountStatus}")
        val detailedStr = if (s.extractionSourceDetailed != "none") s.extractionSourceDetailed else s.extractionSource
        sb.appendLine("3. On-Device Extraction: ${s.extractionSource}, Detailed=$detailedStr, Duration=${s.extractionDurationMs}ms, Success=${s.extractionSuccess}")
        sb.appendLine("   - InnerTube: ${s.isGeneratedByInnerTube} | yt-dlp: ${s.isGeneratedByYtDlp}")
        sb.appendLine("4. Extraction Error: ${s.extractionError ?: "None"}")
        sb.appendLine("5. Playable URL Returned: ${s.hasPlayableUrl} (Host=${s.urlHost ?: "N/A"})")
        sb.appendLine("6. Selected Format: itag=${s.formatItag}, mime=${s.formatMimeType}, codecs=${s.formatCodecs}, bitrate=${s.formatBitrate}")
        sb.appendLine("7. HTTP Status: ${s.httpStatus ?: "N/A"}, Headers: ${s.responseHeadersSummary ?: "N/A"}")
        sb.appendLine("8. Byte Range Support: ${s.serverSupportsByteRange}")
        sb.appendLine("9. First Bytes: ${s.firstAudioBytesLoaded} bytes, RejectedBeforeBytes=${s.rejectedBeforeBytes}, Failures: ${s.readFailures ?: "None"}")
        sb.appendLine("10. Player State: ${s.lastPlayerState}, Exception: ${s.exactPlaybackException ?: "None"}")
        if (s.fullExceptionCauseChain != null) {
            sb.appendLine("    Cause Chain: ${s.fullExceptionCauseChain}")
        }
        sb.appendLine("11. Expiry/Auth: isExpired=${s.isUrlExpired}, authRejected=${s.isAuthRejected}")
        sb.appendLine("12. Request DataSource: User-Agent=\"${s.userAgentSent ?: "N/A"}\"")
        if (s.traceEvents.isNotEmpty()) {
            sb.appendLine("--- COMPLETE 10-STEP PIPELINE TRACE ---")
            s.traceEvents.forEach { step ->
                sb.appendLine("  $step")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * Prints complete sanitized diagnostic report for this request to Logcat and disk.
     */
    fun printFullDiagnosticReport(requestId: Long): String {
        val s = sessions[requestId] ?: return "No diagnostic session for request #$requestId"
        val sb = StringBuilder()
        sb.appendLine("=== ANYFY PLAYBACK DIAGNOSTIC REPORT (REQ #$requestId) ===")
        sb.appendLine(formatSessionReport(s))
        sb.appendLine("===========================================================")
        val result = sb.toString()
        recordLog(Log.INFO, TAG, result)
        appendSessionToDisk(result)
        return result
    }

    fun getSession(requestId: Long): DiagnosticSession? = sessions[requestId]

    /**
     * Generates a complete, sanitized export of recent playback sessions and event history.
     * Contains ZERO secrets, auth tokens, or private cookies.
     * Combines in-memory live state with persistent disk records to survive process restarts.
     */
    fun getExportableLogText(): String {
        val sb = StringBuilder()
        sb.appendLine("===========================================================")
        sb.appendLine("           ANYFY REAL-DEVICE PLAYBACK DIAGNOSTICS          ")
        sb.appendLine("===========================================================")
        sb.appendLine("Generated At: ${dateFormat.format(Date())}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Active In-Memory Sessions: ${sessions.size}")

        val ctx = getEffectiveContext()
        if (ctx != null) {
            try {
                val mgr = com.example.service.integrations.ytmusic.YtAccountManager.getInstance(ctx)
                val acc = mgr.activeAccount.value
                val isAuth = acc != null
                val hasCookies = !acc?.cookie.isNullOrBlank()
                sb.appendLine("Active Account: ${if (isAuth) "Authenticated (name=\"${acc?.name ?: "User"}\", email=\"${acc?.email ?: "N/A"}\", cookiesPresent=$hasCookies)" else "Anonymous / Guest"}")
            } catch (_: Throwable) {
                sb.appendLine("Active Account: Unknown / Error reading account")
            }
        }
        sb.appendLine("-----------------------------------------------------------")

        if (sessions.isNotEmpty()) {
            sb.appendLine("=== RECENT PLAYBACK SESSIONS (IN-MEMORY) ===")
            sessions.values.sortedByDescending { it.requestId }.take(5).forEach { s ->
                sb.appendLine(formatSessionReport(s))
                sb.appendLine()
            }
        }

        val diskSessionsFile = getSessionsFile()
        if (diskSessionsFile != null && diskSessionsFile.exists()) {
            try {
                val diskText = diskSessionsFile.readText(Charsets.UTF_8).trim()
                if (diskText.isNotBlank()) {
                    sb.appendLine("=== PERSISTED SESSIONS HISTORY (FROM DISK) ===")
                    val diskLines = diskText.lines()
                    val recentDiskLines = if (diskLines.size > 200) diskLines.takeLast(200) else diskLines
                    recentDiskLines.forEach { sb.appendLine(it) }
                    sb.appendLine()
                }
            } catch (_: Throwable) {}
        }

        sb.appendLine("=== CHRONOLOGICAL EVENT LOG (RECENT) ===")
        val logFile = getLogFile()
        val diskLogLines = if (logFile != null && logFile.exists()) {
            try {
                val lines = logFile.readLines(Charsets.UTF_8)
                if (lines.size > 150) lines.takeLast(150) else lines
            } catch (_: Throwable) {
                emptyList()
            }
        } else emptyList()

        if (diskLogLines.isNotEmpty()) {
            diskLogLines.forEach { sb.appendLine(it) }
        } else {
            rollingLogs.forEach { sb.appendLine(it) }
        }
        sb.appendLine("======================= END OF LOG ========================")
        return sb.toString()
    }

    /**
     * Copies the sanitized diagnostic log to the clipboard and launches Android's Share Sheet.
     * Attaches both plain text and FileProvider URI so it works across all apps.
     */
    fun exportDiagnostics(context: Context) {
        try {
            init(context)
            val text = getExportableLogText()

            // 1. Copy to Clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Anyfy Playback Diagnostics", text)
            clipboard?.setPrimaryClip(clip)

            // 2. Save to cache file for file sharing
            val file = File(context.cacheDir, "anyfy_playback_diagnostics.txt")
            file.writeText(text, Charsets.UTF_8)

            // 3. Create content URI via FileProvider
            val contentUri: Uri? = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Could not generate FileProvider URI: ${e.message}")
                null
            }

            // 4. Launch Android system Share Sheet
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Anyfy Playback Diagnostic Log")
                putExtra(Intent.EXTRA_TEXT, text)
                if (contentUri != null) {
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooserIntent = Intent.createChooser(shareIntent, "Share Playback Diagnostics").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)

            Toast.makeText(context, "Diagnostics copied to clipboard & share opened", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export diagnostics: ${e.message}", e)
            Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
