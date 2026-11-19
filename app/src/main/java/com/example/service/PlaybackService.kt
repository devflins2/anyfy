package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.example.model.Track
import com.example.service.diagnostics.PlaybackDiagnosticLogger
import com.example.service.diagnostics.PlaybackLatencyTracker
import com.example.service.integrations.LiveYouTubeService
import com.example.service.integrations.YouTubeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class PlayerStatus {
    IDLE,
    EXTRACTING,
    CONNECTING,
    BUFFERING,
    READY,
    PLAYING,
    PAUSED,
    RECONNECTING,
    ERROR,
    UNAVAILABLE,
    LOADING; // Preserved for backwards compatibility

    val isPlayingState: Boolean
        get() = this == PLAYING

    val isLoadingState: Boolean
        get() = this == EXTRACTING || this == CONNECTING || this == BUFFERING || this == RECONNECTING || this == LOADING
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

data class PlaybackUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val status: PlayerStatus = PlayerStatus.IDLE,
    val errorMessage: String? = null,
    val retryAttempt: Int = 0,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffle: Boolean = false,
    val isAutoplay: Boolean = true,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerRemainingSeconds: Int? = null,
    val isSponsorBlockEnabled: Boolean = true,
    val queue: List<Track> = emptyList(),
    val currentTrackIndex: Int = 0,
    // Audio-reactive dynamics for Liquid Glass environment
    val bassPulse: Float = 0.1f,
    val beatDeformation: Float = 0.05f,
    val energyLevel: Float = 0.5f
) {
    val isRepeat: Boolean
        get() = repeatMode != RepeatMode.OFF
}

class PlaybackService(
    private val context: Context? = null,
    private val youTubeService: YouTubeService = LiveYouTubeService()
) {
    companion object {
        @Volatile
        private var instance: PlaybackService? = null

        fun getInstance(context: Context): PlaybackService {
            return instance ?: synchronized(this) {
                instance ?: PlaybackService(context.applicationContext).also { instance = it }
            }
        }

        private const val PREFS_NAME = "flinsmusic_playback_prefs"
        private const val KEY_SAVED_TRACK_IDS = "saved_queue_track_ids"
        private const val KEY_SAVED_CURRENT_TRACK_ID = "saved_current_track_id"
        private const val KEY_SAVED_INDEX = "saved_current_track_index"
        private const val KEY_SAVED_SHUFFLE = "saved_is_shuffle"
        private const val KEY_SAVED_AUTOPLAY = "saved_is_autoplay"
        private const val KEY_SAVED_REPEAT = "saved_is_repeat"
        private const val KEY_SAVED_REPEAT_MODE = "saved_repeat_mode"
        private const val KEY_SAVED_SPONSOR_BLOCK = "saved_sponsor_block_enabled"
    }

    private val coroutineExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("PlaybackService", "Unhandled playback coroutine error: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)
    private var playbackLoopJob: Job? = null
    private var reactiveLoopJob: Job? = null
    private var streamResolutionJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var playbackWatchdogJob: Job? = null
    private var nextTrackPreloadJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var sponsorBlockJob: Job? = null
    private var upNextJob: Job? = null
    private var continuousAutoplayJob: Job? = null
    private var originalQueueBeforeShuffle: List<Track>? = null
    private val manualTrackIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var currentSponsorSegments: List<com.example.service.sponsorblock.SponsorSegment> = emptyList()
    private var lastSkippedSegmentStartMs = -1L

    // YouTube Music listening history CPN and reporting session state
    private var activeCpn: String? = null
    private var reportedStartCpn: String? = null
    private var reportedProgress30sCpn: String? = null
    private var reportedEndCpn: String? = null

    private fun getCleanVideoId(track: Track?): String? {
        if (track == null) return null
        val candidate = track.id.removePrefix("yt_").trim()
        return if (YouTubeService.isValidYouTubeVideoId(candidate)) candidate else null
    }

    // Monotonic request counter to completely prevent race conditions
    private val activeRequestId = AtomicLong(0L)

    @Volatile
    private var exoPlayer: ExoPlayer? = null
    @Volatile
    private var isPrepared = false
    @Volatile
    private var isStreaming = false
    @Volatile
    var isRoomParticipantSlaved: Boolean = false
    @Volatile
    private var pendingSeekPositionMs: Long? = null
    @Volatile
    private var isAutoTransition = false
    @Volatile
    private var consecutiveAutoSkipCount = 0

    // Double-tap debounce trackers
    private var lastAddToQueueTime = 0L
    private var lastAddToQueueTrackId: String? = null
    private var lastPlayNextTime = 0L

    // Dedicated clean OkHttpClient for Media3 audio streaming without modifying interceptors
    private val streamingHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
    private var lastPlayNextTrackId: String? = null

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var onTrackPlayedListener: ((String) -> Unit)? = null
    var onShutdownRequested: (() -> Unit)? = null

    init {
        context?.let { youTubeService.setContext(it) }
        startReactiveEngine()
    }

    private suspend fun ensurePlayer(): ExoPlayer? {
        val ctx = context ?: return null
        if (exoPlayer != null) return exoPlayer
        return kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (exoPlayer == null) {
                val audioAttributes = Media3AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build()

                val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        5000,  // minBufferMs (responsive progressive streaming)
                        30000, // maxBufferMs
                        0,     // bufferForPlaybackMs (instant zero-delay startup on first chunk)
                        500    // bufferForPlaybackAfterRebufferMs
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setBackBuffer(15000, false)
                    .build()

                val player = ExoPlayer.Builder(ctx)
                    .setAudioAttributes(audioAttributes, true)
                    .setHandleAudioBecomingNoisy(true)
                    .setLoadControl(loadControl)
                    .build()

                player.playbackParameters = androidx.media3.common.PlaybackParameters(_uiState.value.playbackSpeed)
                player.addListener(createPlayerListener())
                player.addAnalyticsListener(createAnalyticsListener())
                exoPlayer = player
            }
            exoPlayer
        }
    }

    private fun createAnalyticsListener() = object : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val reqId = activeRequestId.get()
            PlaybackDiagnosticLogger.logHttpResponse(
                requestId = reqId,
                statusCode = loadEventInfo.responseHeaders.get("status")?.firstOrNull()?.toIntOrNull() ?: 200,
                headers = loadEventInfo.responseHeaders
            )
            if (loadEventInfo.bytesLoaded > 0) {
                PlaybackDiagnosticLogger.logFirstBytes(reqId, loadEventInfo.bytesLoaded)
            }
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean
        ) {
            val reqId = activeRequestId.get()
            if (!wasCanceled) {
                var httpCode: Int? = null
                var respHeaders: Map<String, List<String>>? = loadEventInfo.responseHeaders
                var curr: Throwable? = error
                while (curr != null) {
                    if (curr is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        httpCode = curr.responseCode
                        if (curr.headerFields.isNotEmpty()) {
                            respHeaders = curr.headerFields
                        }
                        break
                    }
                    curr = curr.cause
                }
                if (httpCode == null) {
                    httpCode = loadEventInfo.responseHeaders.get("status")?.firstOrNull()?.toIntOrNull()
                }
                if (httpCode != null) {
                    PlaybackDiagnosticLogger.logHttpResponse(reqId, httpCode, respHeaders)
                }
                val isRejectedBeforeBytes = (loadEventInfo.bytesLoaded == 0L)
                PlaybackDiagnosticLogger.logStreamReadFailure(
                    requestId = reqId,
                    error = "${error.javaClass.simpleName}: ${error.message}",
                    rejectedBeforeBytes = isRejectedBeforeBytes
                )
            }
        }

        override fun onDownstreamFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            mediaLoadData: MediaLoadData
        ) {
            val reqId = activeRequestId.get()
            val format = mediaLoadData.trackFormat
            if (format != null) {
                PlaybackDiagnosticLogger.logFormatDetails(
                    requestId = reqId,
                    itag = format.id?.toIntOrNull(),
                    mimeType = format.sampleMimeType ?: format.containerMimeType,
                    codecs = format.codecs,
                    bitrate = if (format.bitrate != androidx.media3.common.Format.NO_VALUE) format.bitrate else null,
                    sampleRate = if (format.sampleRate != androidx.media3.common.Format.NO_VALUE) format.sampleRate else null
                )
            }
        }
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val reqId = activeRequestId.get()
            for (group in tracks.groups) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val f = group.getTrackFormat(i)
                        PlaybackDiagnosticLogger.logFormatDetails(
                            requestId = reqId,
                            itag = f.id?.toIntOrNull(),
                            mimeType = f.sampleMimeType ?: f.containerMimeType,
                            codecs = f.codecs,
                            bitrate = if (f.bitrate != androidx.media3.common.Format.NO_VALUE) f.bitrate else null,
                            sampleRate = if (f.sampleRate != androidx.media3.common.Format.NO_VALUE) f.sampleRate else null
                        )
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val reqId = activeRequestId.get()
            val mediaId = mediaItem?.mediaId ?: return
            val currentUiTrack = _uiState.value.currentTrack
            android.util.Log.d("PlaybackService", "[#$reqId] onMediaItemTransition mediaId=$mediaId reason=$reason currentUiTrack=${currentUiTrack?.id}")
            if (currentUiTrack != null && mediaId != currentUiTrack.id) {
                val queue = _uiState.value.queue
                val matchedIdx = queue.indexOfFirst { it.id == mediaId }
                if (matchedIdx >= 0) {
                    _uiState.update { it.copy(currentTrack = queue[matchedIdx], currentTrackIndex = matchedIdx) }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val reqId = activeRequestId.get()
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            PlaybackDiagnosticLogger.logStateTransition(reqId, _uiState.value.status.name, stateName)
            when (playbackState) {
                Player.STATE_IDLE -> {
                    // Idle state
                }
                Player.STATE_BUFFERING -> {
                    val isPlayingNow = exoPlayer?.isPlaying == true || exoPlayer?.playWhenReady == true
                    val livePos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: _uiState.value.currentPositionMs
                    PlaybackDiagnosticLogger.trace10_PlaybackState(reqId, "BUFFERING", isPlayingNow, livePos)
                    _uiState.update { current ->
                        if (current.status != PlayerStatus.EXTRACTING && current.status != PlayerStatus.CONNECTING) {
                            current.copy(status = PlayerStatus.BUFFERING)
                        } else current
                    }
                }
                Player.STATE_READY -> {
                    val player = exoPlayer
                    val currentMediaId = player?.currentMediaItem?.mediaId
                    val currentUiTrack = _uiState.value.currentTrack

                    // Strict track identity verification: If player holds a stale media item from an obsolete request, discard it!
                    if (currentMediaId != null && currentUiTrack != null && currentMediaId != currentUiTrack.id) {
                        android.util.Log.w("PlaybackService", "[#$reqId] Discarding STATE_READY for stale player media: player=$currentMediaId vs active=${currentUiTrack.id}")
                        player?.stop()
                        player?.clearMediaItems()
                        return
                    }

                    isPrepared = true
                    consecutiveAutoSkipCount = 0
                    connectionTimeoutJob?.cancel()
                    playbackWatchdogJob?.cancel()
                    val liveDur = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: _uiState.value.durationMs
                    val livePos = player?.currentPosition?.coerceAtLeast(0L) ?: _uiState.value.currentPositionMs

                    val shouldPlay = player?.playWhenReady == true || _uiState.value.isPlaying
                    if (shouldPlay && player?.isPlaying == false) {
                        try {
                            player.play()
                        } catch (_: Exception) {}
                    }
                    val isPlayingNow = player?.isPlaying == true || player?.playWhenReady == true || shouldPlay

                    PlaybackDiagnosticLogger.trace10_PlaybackState(reqId, "READY", isPlayingNow, livePos)
                    _uiState.update { current ->
                        current.copy(
                            status = if (isPlayingNow) PlayerStatus.PLAYING else PlayerStatus.READY,
                            isPlaying = isPlayingNow,
                            currentPositionMs = livePos,
                            durationMs = if (liveDur > 0) liveDur else current.durationMs,
                            errorMessage = null
                        )
                    }
                    PlaybackLatencyTracker.markStateReady(reqId)
                    PlaybackDiagnosticLogger.printFullDiagnosticReport(reqId)
                    if (isPlayingNow) {
                        PlaybackLatencyTracker.markFirstAudio(reqId)
                        scheduleNextTrackPreload(reqId)
                        startPlaybackLoop()

                        val cpn = activeCpn
                        val vid = getCleanVideoId(_uiState.value.currentTrack)
                        if (cpn != null && vid != null && reportedStartCpn != cpn) {
                            reportedStartCpn = cpn
                            scope.launch(Dispatchers.IO) {
                                com.example.service.integrations.ytmusic.YtMusicService.getInstance().reportPlaybackStart(vid, cpn)
                            }
                        }
                    }
                    startReactiveEngine()
                }
                Player.STATE_ENDED -> {
                    isPrepared = false
                    playbackLoopJob?.cancel()
                    _uiState.update { it.copy(currentPositionMs = it.durationMs) }
                    val state = _uiState.value

                    val cpn = activeCpn
                    val vid = getCleanVideoId(state.currentTrack)
                    if (cpn != null && vid != null && reportedEndCpn != cpn) {
                        reportedEndCpn = cpn
                        val durSec = (state.durationMs / 1000f).coerceAtLeast(1f)
                        scope.launch(Dispatchers.IO) {
                            com.example.service.integrations.ytmusic.YtMusicService.getInstance().reportPlaybackCompletion(vid, cpn, durSec)
                        }
                    }
                    if (isRoomParticipantSlaved) {
                        android.util.Log.d("PlaybackService", "[TOGETHER_SYNC] Slaved participant track ended; holding in BUFFERING awaiting room state transition from host")
                        _uiState.update { it.copy(isPlaying = false, status = PlayerStatus.BUFFERING, currentPositionMs = it.durationMs) }
                    } else {
                        when (state.repeatMode) {
                            RepeatMode.ONE -> {
                                seekTo(0L)
                                exoPlayer?.play()
                            }
                            RepeatMode.ALL -> {
                                advanceToNextTrack(isUserInitiated = false)
                            }
                            RepeatMode.OFF -> {
                                if (state.currentTrackIndex < state.queue.lastIndex || state.isAutoplay) {
                                    advanceToNextTrack(isUserInitiated = false)
                                } else {
                                    _uiState.update { it.copy(isPlaying = false, status = PlayerStatus.IDLE, currentPositionMs = 0L) }
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val player = exoPlayer ?: return
            val livePos = player.currentPosition.coerceAtLeast(0L)
            _uiState.update { it.copy(currentPositionMs = livePos) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { current ->
                val isTransitioning = current.status == PlayerStatus.EXTRACTING ||
                        current.status == PlayerStatus.CONNECTING ||
                        current.status == PlayerStatus.BUFFERING ||
                        current.status == PlayerStatus.LOADING
                val resolvedIsPlaying = if (!isPlaying && isTransitioning) current.isPlaying else isPlaying
                val resolvedStatus = if (resolvedIsPlaying) {
                    PlayerStatus.PLAYING
                } else {
                    if (current.status == PlayerStatus.PLAYING) PlayerStatus.PAUSED else current.status
                }
                current.copy(
                    isPlaying = resolvedIsPlaying,
                    status = resolvedStatus
                )
            }
            if (isPlaying) {
                val reqId = activeRequestId.get()
                PlaybackLatencyTracker.markFirstAudio(reqId)
                scheduleNextTrackPreload(reqId)
                startPlaybackLoop()

                val cpn = activeCpn
                val vid = getCleanVideoId(_uiState.value.currentTrack)
                if (cpn != null && vid != null && reportedStartCpn != cpn) {
                    reportedStartCpn = cpn
                    scope.launch(Dispatchers.IO) {
                        com.example.service.integrations.ytmusic.YtMusicService.getInstance().reportPlaybackStart(vid, cpn)
                    }
                }
            } else {
                val isTransitioning = _uiState.value.status == PlayerStatus.EXTRACTING ||
                        _uiState.value.status == PlayerStatus.CONNECTING ||
                        _uiState.value.status == PlayerStatus.BUFFERING ||
                        _uiState.value.status == PlayerStatus.LOADING
                if (!isTransitioning) {
                    playbackLoopJob?.cancel()
                    val cpn = activeCpn
                    val vid = getCleanVideoId(_uiState.value.currentTrack)
                    if (cpn != null && vid != null && reportedStartCpn == cpn) {
                        val posSec = (_uiState.value.currentPositionMs / 1000f)
                        scope.launch(Dispatchers.IO) {
                            com.example.service.integrations.ytmusic.YtMusicService.getInstance().reportPlaybackProgress(vid, cpn, posSec, "paused")
                        }
                    }
                }
            }
            startReactiveEngine()
        }

        override fun onPlayerError(error: PlaybackException) {
            playbackWatchdogJob?.cancel()
            connectionTimeoutJob?.cancel()
            var curr: Throwable? = error
            while (curr != null) {
                if (curr is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    PlaybackDiagnosticLogger.logHttpResponse(
                        requestId = activeRequestId.get(),
                        statusCode = curr.responseCode,
                        headers = curr.headerFields
                    )
                    break
                }
                curr = curr.cause
            }
            PlaybackDiagnosticLogger.logPlaybackException(activeRequestId.get(), error)
            PlaybackDiagnosticLogger.printFullDiagnosticReport(activeRequestId.get())
            handlePlayerError(error)
        }
    }

    fun setOnTrackPlayedListener(listener: (String) -> Unit) {
        onTrackPlayedListener = listener
    }

    fun playTrack(track: Track, newQueue: List<Track> = emptyList()) {
        isAutoTransition = false
        consecutiveAutoSkipCount = 0
        val isSingleTrackPlay = newQueue.size <= 1
        manualTrackIds.clear()
        originalQueueBeforeShuffle = null

        val requestId = activeRequestId.incrementAndGet()
        activeCpn = com.example.service.integrations.ytmusic.YtPlaybackReporter.generateCpn()
        reportedStartCpn = null
        reportedProgress30sCpn = null
        reportedEndCpn = null
        PlaybackLatencyTracker.startSession(requestId, track.id, track.title)
        val queueToUse = if (newQueue.isNotEmpty()) {
            if (newQueue.any { it.id == track.id }) newQueue else listOf(track) + newQueue
        } else {
            listOf(track)
        }
        val targetIndex = queueToUse.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        streamResolutionJob?.cancel()
        connectionTimeoutJob?.cancel()
        nextTrackPreloadJob?.cancel()
        sponsorBlockJob?.cancel()
        lastSkippedSegmentStartMs = -1L
        if (track.id.startsWith("yt_")) {
            sponsorBlockJob = scope.launch {
                currentSponsorSegments = com.example.service.sponsorblock.SponsorBlockRepository.getInstance().getSkipSegments(track.id)
            }
        } else {
            currentSponsorSegments = emptyList()
        }

        val rawAudioUrl = track.audioUrl
        val isYtTrack = track.id.startsWith("yt_")
        val isOnlineTrack = track.id.startsWith("online_") || track.id.startsWith("api_")
        val isCatalogTrack = track.id.startsWith("trk_")
        val isItunesTrack = track.id.startsWith("itunes_")
        val isPreview = isAudioPreviewUrl(rawAudioUrl)
        val isExpired = YouTubeService.isStreamUrlExpired(rawAudioUrl)
        val isFullStreamNeeded = rawAudioUrl.isNullOrBlank() || isPreview || isExpired
        val isRemoteStreaming = isYtTrack || isOnlineTrack || isCatalogTrack || isItunesTrack || !rawAudioUrl.isNullOrBlank()

        val cleanVid = if (track.id.startsWith("yt_")) track.id.removePrefix("yt_").trim() else null
        PlaybackDiagnosticLogger.startSession(requestId, track.id, track.title, cleanVid)
        PlaybackDiagnosticLogger.trace1_SongSelection(requestId, track.id, track.title)
        PlaybackDiagnosticLogger.trace2_PlayRequest(requestId, track.id, isRemoteStreaming)

        context?.let { ctx ->
            try {
                val mgr = com.example.service.integrations.ytmusic.YtAccountManager.getInstance(ctx)
                val active = mgr.activeAccount.value
                PlaybackDiagnosticLogger.logAccountStatus(
                    requestId = requestId,
                    hasAccount = active != null,
                    accountName = active?.name,
                    hasCookies = !active?.cookie.isNullOrBlank()
                )
            } catch (_: Throwable) {}
        }

        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = scope.launch {
            delay(12000L) // 12-second hard watchdog timeout
            if (requestId == activeRequestId.get() && !isPrepared &&
                (_uiState.value.status == PlayerStatus.EXTRACTING ||
                 _uiState.value.status == PlayerStatus.CONNECTING ||
                 _uiState.value.status == PlayerStatus.BUFFERING ||
                 _uiState.value.status == PlayerStatus.LOADING)
            ) {
                android.util.Log.w("PlaybackService", "[#$requestId] Playback Watchdog timed out after 12s in status=${_uiState.value.status}")
                PlaybackDiagnosticLogger.logStateTransition(requestId, _uiState.value.status.name, "WATCHDOG_TIMEOUT_12S")
                PlaybackDiagnosticLogger.logStreamReadFailure(requestId, "Watchdog timed out after 12s in status ${_uiState.value.status.name}", rejectedBeforeBytes = true)
                streamResolutionJob?.cancel()
                handlePlaybackTimeout(track, requestId)
            }
        }

        _uiState.update { current ->
            current.copy(
                currentTrack = track,
                currentTrackIndex = targetIndex,
                isPlaying = true,
                status = if (isRemoteStreaming) {
                    if (isFullStreamNeeded) PlayerStatus.EXTRACTING else PlayerStatus.CONNECTING
                } else PlayerStatus.PLAYING,
                errorMessage = null,
                retryAttempt = 0,
                currentPositionMs = 0L,
                durationMs = track.durationSeconds * 1000L,
                queue = queueToUse,
                energyLevel = track.energy
            )
        }
        saveQueueState()
        startReactiveEngine()
        onTrackPlayedListener?.invoke(track.id)

        context?.let { ctx ->
            try {
                val serviceIntent = Intent(ctx, MusicPlayerService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(ctx, serviceIntent)
                } else {
                    ctx.startService(serviceIntent)
                }
            } catch (e: Throwable) {
                android.util.Log.w("PlaybackService", "Unable to start MusicPlayerService: ${e.message}")
            }
        }

        stopAndResetPlayer()

        if (isSingleTrackPlay) {
            fetchUpNextRecommendations(track.id)
        }

        if (isRemoteStreaming) {
            isStreaming = true
            streamResolutionJob = scope.launch {
                prepareAudioSource(track, rawAudioUrl, requestId, isForceRefresh = false)
            }
        } else {
            isStreaming = false
            startPlaybackLoop()
        }
    }

    private fun isAudioPreviewUrl(url: String?): Boolean = YouTubeService.isAudioPreviewUrl(url)

    private suspend fun prepareAudioSource(
        track: Track,
        initialUrl: String?,
        requestId: Long,
        isForceRefresh: Boolean
    ) {
        if (requestId != activeRequestId.get() || _uiState.value.currentTrack?.id != track.id) return
        val logTag = "PlaybackService"
        val sanitizedTitle = track.title.take(25).replace("\"", "")
        val logId = "req=$requestId trk=${track.id.take(14)} title=\"$sanitizedTitle\""
        context?.let { youTubeService.setContext(it) }

        var targetUrl = initialUrl
        val isPreview = isAudioPreviewUrl(targetUrl)
        val isExpired = YouTubeService.isStreamUrlExpired(targetUrl)
        val isFullStreamNeeded = targetUrl.isNullOrBlank() || isPreview || isForceRefresh || isExpired
        android.util.Log.d(logTag, "[$logId] prepareAudioSource start isForceRefresh=$isForceRefresh isExpired=$isExpired isFullStreamNeeded=$isFullStreamNeeded")

        if (isFullStreamNeeded) {
            if (requestId != activeRequestId.get() || _uiState.value.currentTrack?.id != track.id) return
            _uiState.update { current ->
                if (requestId != activeRequestId.get()) current else current.copy(status = PlayerStatus.EXTRACTING)
            }
            PlaybackDiagnosticLogger.logUrlExpiryCheck(requestId, targetUrl, isExpired)
            PlaybackDiagnosticLogger.logExtractionStart(requestId, if (track.id.startsWith("yt_")) "on-device-yt-dlp" else "stream-resolution")
            PlaybackLatencyTracker.markExtractionStart(requestId, if (track.id.startsWith("yt_")) "yt-dlp" else "stream-resolution")
            PlaybackDiagnosticLogger.trace3_StreamResolutionStart(requestId, track.id)
            try {
                val resolveResult = kotlinx.coroutines.withTimeoutOrNull(10000L) {
                    youTubeService.resolveTrackStream(track, forceRefresh = isForceRefresh, requestId = requestId)
                }
                if (requestId != activeRequestId.get() || _uiState.value.currentTrack?.id != track.id) return

                if (resolveResult != null && resolveResult.isSuccess) {
                    targetUrl = resolveResult.getOrNull()
                    val detailedSource = youTubeService.getLastResolutionSource()
                        ?: if (targetUrl?.contains("googlevideo") == true) "yt-dlp" else "other"
                    PlaybackDiagnosticLogger.logExtractionComplete(
                        requestId = requestId,
                        success = true,
                        url = targetUrl,
                        error = null,
                        detailedSource = detailedSource
                    )
                    PlaybackLatencyTracker.markExtractionEnd(requestId, success = true, details = "source=$detailedSource")
                    PlaybackDiagnosticLogger.trace6_ExtractionComplete(requestId, detailedSource, success = true, durationMs = 0L)
                    android.util.Log.d(logTag, "[$logId] resolveTrackStream OK source=$detailedSource")
                } else {
                    val reason = if (resolveResult == null) "TIMEOUT_10s" else {
                        resolveResult.exceptionOrNull()?.message?.take(80) ?: "UNKNOWN"
                    }
                    val failedSource = youTubeService.getLastResolutionSource() ?: "failed"
                    PlaybackDiagnosticLogger.logExtractionComplete(
                        requestId = requestId,
                        success = false,
                        url = null,
                        error = reason,
                        detailedSource = failedSource
                    )
                    PlaybackLatencyTracker.markExtractionEnd(requestId, success = false, details = "resolveResult failure/null ($failedSource)")
                    PlaybackDiagnosticLogger.trace6_ExtractionComplete(requestId, failedSource, success = false, durationMs = 0L, error = reason)
                    android.util.Log.w(logTag, "[$logId] resolveTrackStream FAILED cat=$reason source=$failedSource")

                    if (!initialUrl.isNullOrBlank() && !isAudioPreviewUrl(initialUrl)) {
                        android.util.Log.d(logTag, "[$logId] using initialUrl fallback")
                        targetUrl = initialUrl
                    } else {
                        if (requestId != activeRequestId.get()) return
                        if (isAutoTransition && consecutiveAutoSkipCount < 3) {
                            consecutiveAutoSkipCount++
                            android.util.Log.w(logTag, "[$logId] Auto-skipping unavailable track: '${track.title}', skip #$consecutiveAutoSkipCount")
                            scope.launch {
                                delay(400L)
                                advanceToNextTrack(isUserInitiated = false)
                            }
                            return
                        }
                        val errorReason = if (resolveResult == null) {
                            "Playback connection timed out. Please tap retry."
                        } else {
                            resolveResult.exceptionOrNull()?.message
                                ?: "Playback connection timed out. Please tap retry."
                        }
                        _uiState.update { current ->
                            if (requestId != activeRequestId.get()) current else current.copy(
                                isPlaying = false,
                                status = PlayerStatus.UNAVAILABLE,
                                errorMessage = errorReason
                            )
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                if (requestId != activeRequestId.get()) return
                android.util.Log.w(logTag, "[$logId] resolveTrackStream exception cat=${e.javaClass.simpleName}")
                if (isAutoTransition && consecutiveAutoSkipCount < 3) {
                        consecutiveAutoSkipCount++
                        android.util.Log.w(logTag, "[$logId] Auto-skipping errored track: '${track.title}', skip #$consecutiveAutoSkipCount")
                        scope.launch {
                            delay(400L)
                            advanceToNextTrack(isUserInitiated = false)
                        }
                        return
                    }
                _uiState.update { current ->
                    if (requestId != activeRequestId.get()) current else current.copy(
                        isPlaying = false,
                        status = PlayerStatus.UNAVAILABLE,
                        errorMessage = e.message ?: "Could not connect to streaming service"
                    )
                }
                return
            }
        }

        val resolvedUrl = targetUrl
        val isUrlExpired = YouTubeService.isStreamUrlExpired(resolvedUrl)
        val isUrlPreview = isAudioPreviewUrl(resolvedUrl)
        val isValidUrl = !resolvedUrl.isNullOrBlank() && !isUrlExpired && !isUrlPreview
        val urlHost = try { java.net.URI(resolvedUrl).host } catch (_: Throwable) { null }
        PlaybackDiagnosticLogger.trace7_PlayableUrlValidation(requestId, isValidUrl, isUrlExpired, isUrlPreview, urlHost)

        if (resolvedUrl.isNullOrBlank()) {
            android.util.Log.w(logTag, "[$logId] resolvedUrl is blank - UNAVAILABLE")
            if (requestId == activeRequestId.get()) {
                if (isAutoTransition && consecutiveAutoSkipCount < 3) {
                    consecutiveAutoSkipCount++
                    android.util.Log.w(logTag, "[$logId] Auto-skipping blank stream track: '${track.title}', skip #$consecutiveAutoSkipCount")
                    scope.launch {
                        delay(400L)
                        advanceToNextTrack(isUserInitiated = false)
                    }
                    return
                }
                _uiState.update { current ->
                    if (requestId != activeRequestId.get()) current else current.copy(
                        isPlaying = false,
                        status = PlayerStatus.UNAVAILABLE,
                        errorMessage = "Stream could not be found for this track"
                    )
                }
            }
            return
        }
        if (requestId != activeRequestId.get()) return

        val streamSource = when {
            resolvedUrl.contains("googlevideo") -> "YouTube Music (yt-dlp)"
            else -> "YouTube Music (Direct)"
        }
        PlaybackLatencyTracker.markStreamResolved(requestId, resolvedUrl, streamSource)

        _uiState.update { current ->
            if (requestId != activeRequestId.get()) current else current.copy(status = PlayerStatus.CONNECTING)
        }

        try {
            val player = ensurePlayer()
            if (player != null && context != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (requestId != activeRequestId.get() || _uiState.value.currentTrack?.id != track.id) {
                        android.util.Log.w(logTag, "[$logId] Aborting media setup: request obsolete (req=$requestId vs active=${activeRequestId.get()}) or track changed")
                        return@withContext
                    }

                    val requestHeaders = com.example.service.integrations.YouTubeStreamHeaders.buildRequestHeadersForUrl(resolvedUrl)
                    val userAgent = com.example.service.integrations.YouTubeStreamHeaders.getUserAgentForUrl(resolvedUrl)

                    PlaybackDiagnosticLogger.logUserAgentSent(requestId, userAgent, requestHeaders)
                    PlaybackDiagnosticLogger.trace8_MediaItemCreation(requestId, track.id, userAgent)

                    val baseDataSourceFactory: androidx.media3.datasource.DataSource.Factory =
                        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(streamingHttpClient)
                            .setUserAgent(userAgent)
                            .setDefaultRequestProperties(requestHeaders)

                    val cachedDataSourceFactory = com.example.service.cache.AudioCacheManager.createCacheDataSourceFactory(
                        context,
                        baseDataSourceFactory
                    )

                    val mediaSourceFactory = DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(cachedDataSourceFactory)

                    val artworkUri = when {
                        track.displayBannerArtwork.isNotBlank() -> Uri.parse(track.displayBannerArtwork)
                        track.displayArtwork.isNotBlank() -> Uri.parse(track.displayArtwork)
                        track.artwork.isNotBlank() -> Uri.parse(track.artwork)
                        else -> null
                    }

                    val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(artworkUri)
                        .build()

                    val mediaItemBuilder = MediaItem.Builder()
                        .setUri(Uri.parse(resolvedUrl))
                        .setMediaId(track.id)
                        .setMediaMetadata(mediaMetadata)
                    if (resolvedUrl.contains(".m3u8") || resolvedUrl.contains("manifest/hls")) {
                        mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    }
                    val mediaItem = mediaItemBuilder.build()

                    val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
                    isPrepared = false
                    PlaybackLatencyTracker.markExoPlayerPrepareStart(requestId)
                    player.setMediaSource(mediaSource, /* resetPosition = */ true)
                    player.prepare()
                    PlaybackDiagnosticLogger.trace9_ExoPlayerPrepare(requestId)

                    if (requestId == activeRequestId.get() && _uiState.value.currentTrack?.id == track.id) {
                        player.playWhenReady = true
                    } else {
                        android.util.Log.w(logTag, "[$logId] Discarding prepared media: active track or request changed")
                        player.stop()
                        player.clearMediaItems()
                        return@withContext
                    }

                    pendingSeekPositionMs?.let { seekPos ->
                        player.seekTo(seekPos)
                        pendingSeekPositionMs = null
                    }
                }

                // Connection Timeout Guard: 15s max to reach STATE_READY
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = scope.launch {
                    delay(15000L)
                    if (requestId == activeRequestId.get() && !isPrepared &&
                        (_uiState.value.status == PlayerStatus.CONNECTING ||
                         _uiState.value.status == PlayerStatus.BUFFERING ||
                         _uiState.value.status == PlayerStatus.LOADING)
                    ) {
                        android.util.Log.w(logTag, "[$logId] Stream connection timed out after 15s")
                        handlePlaybackTimeout(track, requestId)
                    }
                }
            } else {
                // Simulation mode for tests without Android Context
                isPrepared = true
                isStreaming = false
                PlaybackDiagnosticLogger.trace8_MediaItemCreation(requestId, track.id, "Simulated")
                PlaybackDiagnosticLogger.trace9_ExoPlayerPrepare(requestId)
                PlaybackDiagnosticLogger.trace10_PlaybackState(requestId, "READY", true, 0L)
                _uiState.update { it.copy(status = PlayerStatus.PLAYING, isPlaying = true) }
                startPlaybackLoop()
            }
        } catch (e: Exception) {
            isPrepared = false
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    status = PlayerStatus.UNAVAILABLE,
                    errorMessage = e.message ?: "This song is temporarily unavailable"
                )
            }
        }
    }

    private fun handlePlaybackTimeout(track: Track, requestId: Long) {
        if (requestId != activeRequestId.get()) return
        streamResolutionJob?.cancel()
        connectionTimeoutJob?.cancel()
        playbackWatchdogJob?.cancel()
        val currentAttempts = _uiState.value.retryAttempt
        if (currentAttempts < 1) {
            _uiState.update {
                it.copy(
                    status = PlayerStatus.RECONNECTING,
                    retryAttempt = currentAttempts + 1,
                    errorMessage = "Connection slow. Reconnecting..."
                )
            }
            scope.launch {
                delay(800L)
                if (requestId == activeRequestId.get()) {
                    prepareAudioSource(track, null, requestId, isForceRefresh = true)
                }
            }
        } else {
            stopAndResetPlayer()
            isPrepared = false
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    status = PlayerStatus.ERROR,
                    errorMessage = "Playback timed out. Tap play to retry."
                )
            }
            PlaybackDiagnosticLogger.logStreamReadFailure(requestId, "Connection timed out after 15s in status ${_uiState.value.status.name}", rejectedBeforeBytes = true)
            PlaybackDiagnosticLogger.printFullDiagnosticReport(requestId)
        }
    }

    private fun handleConnectionTimeout(track: Track, requestId: Long) {
        handlePlaybackTimeout(track, requestId)
    }

    private fun handlePlayerError(error: PlaybackException) {
        val requestId = activeRequestId.get()
        isPrepared = false
        val currentAttempts = _uiState.value.retryAttempt
        val track = _uiState.value.currentTrack
        android.util.Log.e("PlaybackService", "ExoPlayer playback error: ${error.errorCodeName} - ${error.message}")

        if (track != null && currentAttempts < 2) {
            val lastPos = _uiState.value.currentPositionMs.takeIf { it > 1000L }
            if (lastPos != null) {
                pendingSeekPositionMs = lastPos
            }
            _uiState.update {
                it.copy(
                    status = PlayerStatus.RECONNECTING,
                    retryAttempt = currentAttempts + 1,
                    errorMessage = "Reconnecting... (attempt ${currentAttempts + 2})"
                )
            }
            scope.launch {
                delay(1500L)
                if (requestId == activeRequestId.get()) {
                    prepareAudioSource(track, null, requestId, isForceRefresh = true)
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    status = PlayerStatus.ERROR,
                    errorMessage = "Playback error (${error.errorCodeName}). Tap to retry."
                )
            }
        }
    }

    fun retryCurrentTrack() {
        val current = _uiState.value.currentTrack ?: return
        val queue = _uiState.value.queue
        val targetIndex = queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val requestId = activeRequestId.incrementAndGet()
        PlaybackDiagnosticLogger.startSession(requestId, current.id, current.title, current.cleanVideoId)
        _uiState.update {
            it.copy(
                status = PlayerStatus.EXTRACTING,
                errorMessage = null,
                retryAttempt = it.retryAttempt + 1,
                currentTrackIndex = targetIndex,
                isPlaying = true
            )
        }
        stopAndResetPlayer()
        streamResolutionJob = scope.launch {
            prepareAudioSource(current, null, requestId, isForceRefresh = true)
        }
    }

    fun pause() {
        val player = exoPlayer
        if (player != null && isPrepared) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                try { player.pause() } catch (_: Exception) {}
            } else {
                scope.launch(Dispatchers.Main) {
                    try { player.pause() } catch (_: Exception) {}
                }
            }
        }
        _uiState.update { it.copy(isPlaying = false, status = PlayerStatus.PAUSED) }
        playbackLoopJob?.cancel()
        startReactiveEngine()
    }

    fun resume() {
        val current = _uiState.value
        if (current.currentTrack == null) return
        val player = exoPlayer
        if (player != null && isPrepared) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                try { player.play() } catch (_: Exception) {}
            } else {
                scope.launch(Dispatchers.Main) {
                    try { player.play() } catch (_: Exception) {}
                }
            }
            _uiState.update { it.copy(isPlaying = true, status = PlayerStatus.PLAYING) }
            startPlaybackLoop()
        } else if (!isStreaming) {
            _uiState.update { it.copy(isPlaying = true, status = PlayerStatus.PLAYING) }
            startPlaybackLoop()
        } else {
            player?.playWhenReady = true
            _uiState.update { it.copy(isPlaying = true) }
            startPlaybackLoop()
        }
        startReactiveEngine()
    }

    fun togglePlayPause() {
        val current = _uiState.value
        if (current.currentTrack == null) return
        if (current.isPlaying) {
            pause()
        } else {
            resume()
        }
    }

    fun seekTo(positionMs: Long) {
        val current = _uiState.value
        val clamped = positionMs.coerceIn(0L, current.durationMs.coerceAtLeast(1000L))
        _uiState.update { it.copy(currentPositionMs = clamped) }

        val player = exoPlayer
        if (player != null && isPrepared) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                try { player.seekTo(clamped) } catch (_: Exception) {}
            } else {
                scope.launch(Dispatchers.Main) {
                    try { player.seekTo(clamped) } catch (_: Exception) {}
                }
            }
        } else if (isStreaming) {
            pendingSeekPositionMs = clamped
        }
    }

    fun next() {
        advanceToNextTrack(isUserInitiated = true)
    }

    fun previous() {
        val state = _uiState.value
        if (state.currentPositionMs > 3000L) {
            seekTo(0L)
            return
        }
        val queue = state.queue
        if (queue.isEmpty() || state.currentTrack == null) return

        val currentIdx = if (state.currentTrackIndex in queue.indices && queue[state.currentTrackIndex].id == state.currentTrack.id) {
            state.currentTrackIndex
        } else {
            queue.indexOfFirst { it.id == state.currentTrack.id }.coerceAtLeast(0)
        }

        val prevIdx = if (state.isShuffle) {
            val candidates = queue.indices.filter { it != currentIdx }
            if (candidates.isNotEmpty()) candidates.random() else currentIdx
        } else {
            if (currentIdx > 0) currentIdx - 1 else if (state.repeatMode == RepeatMode.ALL) queue.lastIndex else 0
        }
        playTrackAtIndex(prevIdx, isUserInitiated = true)
    }

    fun advanceToNextTrack(isUserInitiated: Boolean) {
        val state = _uiState.value
        val queue = state.queue
        if (queue.isEmpty() || state.currentTrack == null) return

        val currentIdx = if (state.currentTrackIndex in queue.indices && queue[state.currentTrackIndex].id == state.currentTrack.id) {
            state.currentTrackIndex
        } else {
            queue.indexOfFirst { it.id == state.currentTrack.id }.coerceAtLeast(0)
        }

        if (state.isShuffle) {
            val candidates = queue.indices.filter { it != currentIdx }
            val nextIdx = if (candidates.isNotEmpty()) candidates.random() else currentIdx
            playTrackAtIndex(nextIdx, isUserInitiated = isUserInitiated)
        } else {
            if (currentIdx < queue.lastIndex) {
                playTrackAtIndex(currentIdx + 1, isUserInitiated = isUserInitiated)
            } else {
                if (state.repeatMode == RepeatMode.ALL) {
                    playTrackAtIndex(0, isUserInitiated = isUserInitiated)
                } else if (state.isAutoplay) {
                    _uiState.update { it.copy(status = PlayerStatus.LOADING) }
                    scope.launch {
                        checkAndFetchContinuousAutoplay()
                        continuousAutoplayJob?.join()
                        val updated = _uiState.value
                        if (currentIdx < updated.queue.lastIndex) {
                            playTrackAtIndex(currentIdx + 1, isUserInitiated = isUserInitiated)
                        } else {
                            if (isUserInitiated) {
                                playTrackAtIndex(0, isUserInitiated = true)
                            } else {
                                _uiState.update { it.copy(isPlaying = false, status = PlayerStatus.IDLE, currentPositionMs = 0L) }
                                playbackLoopJob?.cancel()
                            }
                        }
                    }
                } else {
                    if (isUserInitiated) {
                        playTrackAtIndex(0, isUserInitiated = true)
                    } else {
                        _uiState.update { it.copy(isPlaying = false, status = PlayerStatus.IDLE, currentPositionMs = 0L) }
                        playbackLoopJob?.cancel()
                    }
                }
            }
        }
    }

    fun playTrackAtIndex(index: Int, isUserInitiated: Boolean = true) {
        val state = _uiState.value
        if (index !in state.queue.indices) return
        val targetTrack = state.queue[index]

        isAutoTransition = !isUserInitiated
        if (isUserInitiated) {
            consecutiveAutoSkipCount = 0
        }

        if (state.isAutoplay && index >= state.queue.size - 3) {
            checkAndFetchContinuousAutoplay()
        }

        val requestId = activeRequestId.incrementAndGet()
        activeCpn = com.example.service.integrations.ytmusic.YtPlaybackReporter.generateCpn()
        reportedStartCpn = null
        reportedProgress30sCpn = null
        reportedEndCpn = null
        PlaybackLatencyTracker.startSession(requestId, targetTrack.id, targetTrack.title)
        streamResolutionJob?.cancel()
        connectionTimeoutJob?.cancel()
        nextTrackPreloadJob?.cancel()
        sponsorBlockJob?.cancel()
        lastSkippedSegmentStartMs = -1L
        if (targetTrack.id.startsWith("yt_")) {
            sponsorBlockJob = scope.launch {
                currentSponsorSegments = com.example.service.sponsorblock.SponsorBlockRepository.getInstance().getSkipSegments(targetTrack.id)
            }
        } else {
            currentSponsorSegments = emptyList()
        }

        val rawAudioUrl = targetTrack.audioUrl
        val isYtTrack = targetTrack.id.startsWith("yt_")
        val isOnlineTrack = targetTrack.id.startsWith("online_") || targetTrack.id.startsWith("api_")
        val isCatalogTrack = targetTrack.id.startsWith("trk_")
        val isItunesTrack = targetTrack.id.startsWith("itunes_")
        val isPreview = isAudioPreviewUrl(rawAudioUrl)
        val isFullStreamNeeded = rawAudioUrl.isNullOrBlank() || isPreview || isYtTrack
        val isRemoteStreaming = isYtTrack || isOnlineTrack || isCatalogTrack || isItunesTrack || !rawAudioUrl.isNullOrBlank()

        _uiState.update { current ->
            current.copy(
                currentTrack = targetTrack,
                currentTrackIndex = index,
                isPlaying = true,
                status = if (isRemoteStreaming) {
                    if (isFullStreamNeeded) PlayerStatus.EXTRACTING else PlayerStatus.CONNECTING
                } else PlayerStatus.PLAYING,
                errorMessage = null,
                retryAttempt = 0,
                currentPositionMs = 0L,
                durationMs = targetTrack.durationSeconds * 1000L,
                energyLevel = targetTrack.energy
            )
        }
        saveQueueState()
        startReactiveEngine()
        onTrackPlayedListener?.invoke(targetTrack.id)

        context?.let { ctx ->
            try {
                val serviceIntent = Intent(ctx, MusicPlayerService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(ctx, serviceIntent)
                } else {
                    ctx.startService(serviceIntent)
                }
            } catch (e: Throwable) {
                android.util.Log.w("PlaybackService", "Unable to start MusicPlayerService: ${e.message}")
            }
        }

        stopAndResetPlayer()

        if (isRemoteStreaming) {
            isStreaming = true
            streamResolutionJob = scope.launch {
                prepareAudioSource(targetTrack, rawAudioUrl, requestId, isForceRefresh = false)
            }
        } else {
            isStreaming = false
            startPlaybackLoop()
        }
    }

    fun toggleShuffle() {
        _uiState.update { current ->
            val newShuffle = !current.isShuffle
            if (newShuffle) {
                originalQueueBeforeShuffle = current.queue
                val currentTrack = current.currentTrack
                val queue = current.queue
                if (queue.size > 1 && currentTrack != null) {
                    val currentIdx = current.currentTrackIndex
                    val remaining = queue.filterIndexed { idx, _ -> idx != currentIdx }.shuffled()
                    val newQueue = listOf(currentTrack) + remaining
                    current.copy(isShuffle = true, queue = newQueue, currentTrackIndex = 0)
                } else {
                    current.copy(isShuffle = true)
                }
            } else {
                val original = originalQueueBeforeShuffle
                if (original != null && current.currentTrack != null) {
                    val newIdx = original.indexOfFirst { it.id == current.currentTrack.id }.coerceAtLeast(0)
                    current.copy(isShuffle = false, queue = original, currentTrackIndex = newIdx)
                } else {
                    current.copy(isShuffle = false)
                }
            }
        }
        saveQueueState()
    }

    fun toggleAutoplay() {
        _uiState.update { it.copy(isAutoplay = !it.isAutoplay) }
        saveQueueState()
    }

    fun toggleRepeat() {
        _uiState.update { current ->
            val nextMode = when (current.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            current.copy(repeatMode = nextMode)
        }
        saveQueueState()
    }

    fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 3.0f)
        _uiState.update { it.copy(playbackSpeed = safeSpeed) }
        exoPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(safeSpeed)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _uiState.update { it.copy(sleepTimerRemainingSeconds = null) }
            return
        }
        var remaining = minutes * 60
        _uiState.update { it.copy(sleepTimerRemainingSeconds = remaining) }
        sleepTimerJob = scope.launch {
            while (isActive && remaining > 0) {
                delay(1000L)
                remaining--
                _uiState.update { it.copy(sleepTimerRemainingSeconds = remaining) }
            }
            if (isActive && remaining <= 0) {
                pause()
                _uiState.update { it.copy(sleepTimerRemainingSeconds = null) }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(sleepTimerRemainingSeconds = null) }
    }

    fun setSponsorBlockEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isSponsorBlockEnabled = enabled) }
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SAVED_SPONSOR_BLOCK, enabled).apply()
        } catch (_: Exception) {}
    }

    fun openAudioEqualizer(context: Context) {
        try {
            val audioSessionId = exoPlayer?.audioSessionId ?: 0
            val intent = Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("PlaybackService", "System equalizer not available: ${e.message}")
        }
    }

    fun updateCurrentTrackLyrics(trackId: String, lyrics: List<com.example.model.LyricsLine>) {
        _uiState.update { current ->
            if (current.currentTrack?.id == trackId) {
                current.copy(currentTrack = current.currentTrack.copy(lyrics = lyrics))
            } else current
        }
    }

    fun playNext(track: Track) {
        val now = System.currentTimeMillis()
        if (lastPlayNextTrackId == track.id && now - lastPlayNextTime < 800L) {
            return
        }
        lastPlayNextTime = now
        lastPlayNextTrackId = track.id
        manualTrackIds.add(track.id)

        val state = _uiState.value
        if (state.currentTrack == null || state.queue.isEmpty()) {
            playTrack(track, listOf(track))
            return
        }

        _uiState.update { current ->
            val list = current.queue.toMutableList()
            val currentIdx = if (current.currentTrackIndex in list.indices && list[current.currentTrackIndex].id == current.currentTrack?.id) {
                current.currentTrackIndex
            } else {
                list.indexOfFirst { it.id == current.currentTrack?.id }.coerceAtLeast(0)
            }
            val insertAt = currentIdx + 1
            if (list.getOrNull(insertAt)?.id != track.id) {
                list.add(insertAt, track)
            }
            current.copy(queue = list, currentTrackIndex = currentIdx)
        }
        saveQueueState()
    }

    fun addToQueue(track: Track) {
        val now = System.currentTimeMillis()
        if (lastAddToQueueTrackId == track.id && now - lastAddToQueueTime < 800L) {
            return
        }
        lastAddToQueueTime = now
        lastAddToQueueTrackId = track.id
        manualTrackIds.add(track.id)

        val state = _uiState.value
        if (state.currentTrack == null || state.queue.isEmpty()) {
            playTrack(track, listOf(track))
            return
        }

        _uiState.update { current ->
            val list = current.queue.toMutableList()
            if (list.lastOrNull()?.id != track.id) {
                list.add(track)
            }
            current.copy(queue = list)
        }
        saveQueueState()
    }

    fun removeFromQueue(index: Int) {
        _uiState.update { current ->
            val list = current.queue.toMutableList()
            if (index !in list.indices) return@update current
            list.removeAt(index)
            val newCurrentIdx = when {
                index < current.currentTrackIndex -> (current.currentTrackIndex - 1).coerceAtLeast(0)
                index == current.currentTrackIndex -> current.currentTrackIndex.coerceAtMost(list.lastIndex.coerceAtLeast(0))
                else -> current.currentTrackIndex
            }
            val newCurrentTrack = if (index == current.currentTrackIndex) {
                list.getOrNull(newCurrentIdx)
            } else {
                current.currentTrack
            }
            current.copy(queue = list, currentTrackIndex = newCurrentIdx, currentTrack = newCurrentTrack)
        }
        saveQueueState()
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        _uiState.update { current ->
            val list = current.queue.toMutableList()
            if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return@update current
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)

            var newIdx = current.currentTrackIndex
            if (fromIndex == current.currentTrackIndex) {
                newIdx = toIndex
            } else if (fromIndex < current.currentTrackIndex && toIndex >= current.currentTrackIndex) {
                newIdx--
            } else if (fromIndex > current.currentTrackIndex && toIndex <= current.currentTrackIndex) {
                newIdx++
            }
            current.copy(queue = list, currentTrackIndex = newIdx)
        }
        saveQueueState()
    }

    fun clearQueue() {
        _uiState.update { state ->
            val current = state.currentTrack
            state.copy(
                queue = if (current != null) listOf(current) else emptyList(),
                currentTrackIndex = 0
            )
        }
        saveQueueState()
    }

    fun stopAndClearPlayback() {
        activeRequestId.incrementAndGet()
        streamResolutionJob?.cancel()
        streamResolutionJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        playbackLoopJob?.cancel()
        playbackLoopJob = null
        reactiveLoopJob?.cancel()
        reactiveLoopJob = null

        stopAndResetPlayer()

        _uiState.value = PlaybackUiState(
            currentTrack = null,
            isPlaying = false,
            status = PlayerStatus.IDLE,
            errorMessage = null,
            retryAttempt = 0,
            currentPositionMs = 0L,
            durationMs = 0L,
            isShuffle = false,
            repeatMode = RepeatMode.OFF,
            queue = emptyList(),
            currentTrackIndex = 0,
            bassPulse = 0.1f,
            beatDeformation = 0.05f,
            energyLevel = 0.5f
        )

        try {
            onShutdownRequested?.invoke()
        } catch (_: Exception) {}

        val ctx = context
        if (ctx != null) {
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
            } catch (_: Exception) {}

            try {
                val shutdownIntent = Intent(ctx, MusicPlayerService::class.java).apply {
                    action = MusicPlayerService.ACTION_SHUTDOWN
                }
                ctx.startService(shutdownIntent)
            } catch (_: Exception) {}
        }
    }

    private fun saveQueueState() {
        val ctx = context ?: return
        try {
            val state = _uiState.value
            val trackIds = state.queue.map { it.id }.joinToString(",")
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_SAVED_TRACK_IDS, trackIds)
                .putString(KEY_SAVED_CURRENT_TRACK_ID, state.currentTrack?.id ?: "")
                .putInt(KEY_SAVED_INDEX, state.currentTrackIndex)
                .putBoolean(KEY_SAVED_SHUFFLE, state.isShuffle)
                .putBoolean(KEY_SAVED_AUTOPLAY, state.isAutoplay)
                .putBoolean(KEY_SAVED_REPEAT, state.isRepeat)
                .putString(KEY_SAVED_REPEAT_MODE, state.repeatMode.name)
                .putBoolean(KEY_SAVED_SPONSOR_BLOCK, state.isSponsorBlockEnabled)
                .apply()
        } catch (_: Exception) {}
    }

    fun restoreSavedQueue(tracksProvider: (List<String>) -> List<Track>) {
        val ctx = context ?: return
        if (_uiState.value.queue.isNotEmpty() && _uiState.value.currentTrack != null) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawIds = prefs.getString(KEY_SAVED_TRACK_IDS, null) ?: return
            if (rawIds.isBlank()) return
            val idList = rawIds.split(",").filter { it.isNotBlank() }
            if (idList.isEmpty()) return

            val tracks = tracksProvider(idList)
            if (tracks.isEmpty()) return

            val savedTrackId = prefs.getString(KEY_SAVED_CURRENT_TRACK_ID, "") ?: ""
            val savedIndex = prefs.getInt(KEY_SAVED_INDEX, 0)
            val savedShuffle = prefs.getBoolean(KEY_SAVED_SHUFFLE, false)
            val savedAutoplay = prefs.getBoolean(KEY_SAVED_AUTOPLAY, true)
            val savedRepeat = prefs.getBoolean(KEY_SAVED_REPEAT, false)
            val savedRepeatModeStr = prefs.getString(KEY_SAVED_REPEAT_MODE, null)
            val savedRepeatMode = if (savedRepeatModeStr != null) {
                try { RepeatMode.valueOf(savedRepeatModeStr) } catch (_: Exception) { RepeatMode.OFF }
            } else {
                if (savedRepeat) RepeatMode.ALL else RepeatMode.OFF
            }
            val savedSponsorBlock = prefs.getBoolean(KEY_SAVED_SPONSOR_BLOCK, true)

            val resolvedCurrent = tracks.find { it.id == savedTrackId }
                ?: tracks.getOrNull(savedIndex)
                ?: tracks.firstOrNull()

            val finalIndex = if (resolvedCurrent != null) tracks.indexOfFirst { it.id == resolvedCurrent.id }.coerceAtLeast(0) else 0

            _uiState.update {
                it.copy(
                    queue = tracks,
                    currentTrack = resolvedCurrent,
                    currentTrackIndex = finalIndex,
                    isPlaying = false,
                    status = PlayerStatus.IDLE,
                    isShuffle = savedShuffle,
                    isAutoplay = savedAutoplay,
                    repeatMode = savedRepeatMode,
                    isSponsorBlockEnabled = savedSponsorBlock,
                    durationMs = (resolvedCurrent?.durationSeconds ?: 0) * 1000L
                )
            }
        } catch (_: Exception) {}
    }

    private fun stopAndResetPlayer() {
        nextTrackPreloadJob?.cancel()
        val player = exoPlayer
        if (player != null) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                try {
                    player.stop()
                    player.clearMediaItems()
                } catch (_: Exception) {}
            } else {
                scope.launch(Dispatchers.Main) {
                    try {
                        player.stop()
                        player.clearMediaItems()
                    } catch (_: Exception) {}
                }
            }
        }
        isPrepared = false
        isStreaming = false
        pendingSeekPositionMs = null
    }

    private fun scheduleNextTrackPreload(requestId: Long) {
        nextTrackPreloadJob?.cancel()
        nextTrackPreloadJob = scope.launch {
            delay(1500L)
            if (requestId != activeRequestId.get()) return@launch
            if (_uiState.value.status != PlayerStatus.PLAYING) return@launch

            val state = _uiState.value
            val nextIndex = if (state.isShuffle) {
                val candidates = state.queue.indices.filter { it != state.currentTrackIndex }
                candidates.firstOrNull() ?: (state.currentTrackIndex + 1)
            } else {
                state.currentTrackIndex + 1
            }
            if (nextIndex in state.queue.indices) {
                val nextTrack = state.queue[nextIndex]
                try {
                    val resolveResult = youTubeService.resolveTrackStream(nextTrack, forceRefresh = false)
                    val nextStreamUrl = resolveResult.getOrNull()
                    if (!nextStreamUrl.isNullOrBlank()) {
                        // Attach pre-resolved URL to matching next track in queue for instant 0ms transition
                        _uiState.update { curr ->
                            val actualIndex = curr.queue.indexOfFirst { it.id == nextTrack.id }
                            if (actualIndex >= 0) {
                                val updatedQueue = curr.queue.toMutableList()
                                updatedQueue[actualIndex] = curr.queue[actualIndex].copy(audioUrl = nextStreamUrl)
                                curr.copy(queue = updatedQueue)
                            } else curr
                        }

                        if (context != null) {
                            android.util.Log.i("PlaybackService", "[Preload] Next track resolved, pre-buffering audio chunk: '${nextTrack.title}'")
                            val reqHeaders = com.example.service.integrations.YouTubeStreamHeaders.buildRequestHeadersForUrl(nextStreamUrl)
                            val userAgent = com.example.service.integrations.YouTubeStreamHeaders.getUserAgentForUrl(nextStreamUrl)
                            val baseFactory: androidx.media3.datasource.DataSource.Factory =
                                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(streamingHttpClient)
                                    .setUserAgent(userAgent)
                                    .setDefaultRequestProperties(reqHeaders)
                            com.example.service.cache.AudioCacheManager.preloadTrackChunk(context, baseFactory, nextStreamUrl)
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("PlaybackService", "[Preload] Failed to preload next track: ${e.message}")
                }
            }
        }
    }

    fun release() {
        playbackLoopJob?.cancel()
        playbackLoopJob = null
        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        stopAndResetPlayer()
        val player = exoPlayer
        exoPlayer = null
        if (player != null) {
            scope.launch(Dispatchers.Main) {
                try {
                    player.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun startPlaybackLoop() {
        playbackLoopJob?.cancel()
        playbackLoopJob = scope.launch(Dispatchers.Main) {
            while (isActive && _uiState.value.isPlaying) {
                delay(50L)
                val current = _uiState.value
                val player = exoPlayer
                if (isStreaming && player != null && isPrepared) {
                    try {
                        val livePos = player.currentPosition.coerceAtLeast(0L)
                        val liveDur = player.duration
                        val validDur = if (liveDur > 0 && liveDur != C.TIME_UNSET) liveDur else current.durationMs
                        _uiState.update {
                            it.copy(
                                currentPositionMs = livePos,
                                durationMs = if (validDur > 0) validDur else it.durationMs
                            )
                        }
                        if (current.isSponsorBlockEnabled && currentSponsorSegments.isNotEmpty()) {
                            val match = currentSponsorSegments.firstOrNull { seg ->
                                livePos in seg.startMs until seg.endMs && seg.startMs != lastSkippedSegmentStartMs
                            }
                            if (match != null) {
                                lastSkippedSegmentStartMs = match.startMs
                                val targetMs = (match.endMs + 100L).coerceAtMost(validDur)
                                android.util.Log.d("PlaybackService", "SponsorBlock auto-skipped [${match.startMs}..${match.endMs}] to $targetMs ms")
                                seekTo(targetMs)
                            }
                        }

                        // Autoplay prefetch check: when remaining time is < 15s and nearing queue end
                        if (current.isAutoplay && validDur > 15000L && livePos >= validDur - 15000L) {
                            checkAndFetchContinuousAutoplay()
                        }

                        // YouTube Music listening history 30s milestone reporting
                        val cpn = activeCpn
                        val vid = getCleanVideoId(current.currentTrack)
                        if (cpn != null && vid != null && reportedProgress30sCpn != cpn && (livePos >= 30_000L || (validDur > 0 && livePos >= validDur * 0.3f))) {
                            reportedProgress30sCpn = cpn
                            val posSec = (livePos / 1000f)
                            scope.launch(Dispatchers.IO) {
                                com.example.service.integrations.ytmusic.YtMusicService.getInstance().reportPlaybackProgress(vid, cpn, posSec, "playing")
                            }
                        }

                        if (validDur > 2000L && livePos >= validDur - 150L && !player.isPlaying && player.playbackState != Player.STATE_BUFFERING) {
                            when (current.repeatMode) {
                                RepeatMode.ONE -> {
                                    seekTo(0L)
                                    player.play()
                                }
                                RepeatMode.ALL -> {
                                    advanceToNextTrack(isUserInitiated = false)
                                }
                                RepeatMode.OFF -> {
                                    if (current.currentTrackIndex < current.queue.lastIndex || current.isAutoplay) {
                                        advanceToNextTrack(isUserInitiated = false)
                                    } else {
                                        _uiState.update { it.copy(isPlaying = false, status = PlayerStatus.IDLE, currentPositionMs = 0L) }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("PlaybackService", "Error reading player position: ${e.message}")
                    }
                } else if (!isStreaming || player == null) {
                    val newPos = current.currentPositionMs + 50L
                    if (newPos >= current.durationMs && current.durationMs > 0) {
                        when (current.repeatMode) {
                            RepeatMode.ONE -> {
                                seekTo(0L)
                            }
                            RepeatMode.ALL -> {
                                advanceToNextTrack(isUserInitiated = false)
                            }
                            RepeatMode.OFF -> {
                                if (current.currentTrackIndex < current.queue.lastIndex || current.isAutoplay) {
                                    advanceToNextTrack(isUserInitiated = false)
                                } else {
                                    _uiState.update { it.copy(isPlaying = false, status = PlayerStatus.IDLE, currentPositionMs = 0L) }
                                }
                            }
                        }
                    } else {
                        _uiState.update { it.copy(currentPositionMs = newPos) }
                    }
                }
            }
        }
    }

    private fun fetchUpNextRecommendations(trackId: String) {
        upNextJob?.cancel()
        val cleanVid = trackId.removePrefix("yt_").trim()
        if (cleanVid.length != 11) return
        upNextJob = scope.launch(Dispatchers.IO) {
            try {
                val ytService = com.example.service.integrations.ytmusic.YtMusicService.getInstance()
                val res = ytService.getRadioTracks(cleanVid)
                val fetched = res.getOrNull()?.map { it.toTrack() } ?: emptyList()
                if (fetched.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (_uiState.value.currentTrack?.id != trackId) return@withContext
                        _uiState.update { current ->
                            if (current.currentTrack?.id != trackId) return@update current
                            val existingIds = current.queue.map { it.id }.toSet()
                            val newTracks = fetched.filter { it.id !in existingIds }
                            if (newTracks.isNotEmpty()) {
                                current.copy(queue = current.queue + newTracks)
                            } else current
                        }
                        saveQueueState()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "Up Next fetch error: ${e.message}")
            }
        }
    }

    private fun checkAndFetchContinuousAutoplay() {
        if (continuousAutoplayJob?.isActive == true) return
        val state = _uiState.value
        if (!state.isAutoplay) return
        val currentIdx = state.currentTrackIndex
        val queue = state.queue
        if (currentIdx < queue.lastIndex - 2 && queue.size > 2) return

        val seedTrack = queue.lastOrNull() ?: state.currentTrack ?: return
        val cleanVid = getCleanVideoId(seedTrack) ?: getCleanVideoId(state.currentTrack) ?: return

        continuousAutoplayJob = scope.launch(Dispatchers.IO) {
            try {
                val ytService = com.example.service.integrations.ytmusic.YtMusicService.getInstance()
                val res = ytService.getRadioTracks(cleanVid)
                val fetched = res.getOrNull()?.map { it.toTrack() } ?: emptyList()
                if (fetched.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { current ->
                            val existingIds = current.queue.map { it.id }.toSet()
                            val newTracks = fetched.filter { it.id !in existingIds }
                            if (newTracks.isNotEmpty()) {
                                android.util.Log.d("PlaybackService", "Continuous autoplay added ${newTracks.size} new tracks")
                                current.copy(queue = current.queue + newTracks)
                            } else current
                        }
                        saveQueueState()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "Continuous autoplay fetch error: ${e.message}")
            }
        }
    }

    private fun startReactiveEngine() {
        reactiveLoopJob?.cancel()
        val state = _uiState.value
        val energy = state.currentTrack?.energy ?: 0.5f
        val isActuallyPlaying = state.isPlaying && state.status == PlayerStatus.PLAYING
        _uiState.update {
            it.copy(
                bassPulse = if (isActuallyPlaying) 0.5f * energy else 0.1f,
                beatDeformation = if (isActuallyPlaying) 0.35f * energy else 0.05f,
                energyLevel = if (isActuallyPlaying) energy else 0.15f
            )
        }
    }
}
