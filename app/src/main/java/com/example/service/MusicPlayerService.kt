package com.example.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MusicPlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "com.example.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.example.ACTION_STOP"
        const val ACTION_SHUTDOWN = "com.example.ACTION_SHUTDOWN"
    }

    private val binder = MusicBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: MediaNotificationManager
    private var playbackService: PlaybackService? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var playbackStateJob: Job? = null
    private var lastTrackId: String? = null
    private var lastIsPlaying: Boolean? = null
    private var lastArtwork: android.graphics.Bitmap? = null
    private var lastDurationMs: Long = 0L
    private var lastReportedPositionMs: Long = -1L
    private var lastPlaybackStateUpdateTimeMs: Long = 0L

    fun getMediaSession(): MediaSessionCompat = mediaSession

    fun shutdownPlaybackSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notificationManager.cancelNotification()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.setMetadata(null)
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 0.0f)
                    .setActions(0L)
                    .build()
            )
        }
        lastTrackId = null
        lastArtwork = null
        lastIsPlaying = null
        lastDurationMs = 0L
        lastReportedPositionMs = -1L
        lastPlaybackStateUpdateTimeMs = 0L
        stopSelf()
    }

    private val mediaButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action

            if (action == ACTION_SHUTDOWN || action == "com.example.ACTION_SHUTDOWN" ||
                action == ACTION_STOP || action == "com.example.ACTION_STOP" || action == "ACTION_STOP") {
                shutdownPlaybackSession()
                return
            }

            // Reject all playback resumption actions if there is no active track (e.g. after logout)
            if (playbackService?.uiState?.value?.currentTrack == null) {
                return
            }

            when (action) {
                ACTION_PLAY, "ACTION_PLAY" -> playbackService?.resume()
                ACTION_PAUSE, "ACTION_PAUSE" -> playbackService?.pause()
                ACTION_NEXT, "ACTION_NEXT" -> playbackService?.next()
                ACTION_PREVIOUS, "ACTION_PREVIOUS" -> playbackService?.previous()
                Intent.ACTION_MEDIA_BUTTON -> {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (event?.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> playbackService?.resume()
                            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> playbackService?.pause()
                            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> playbackService?.togglePlayPause()
                            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> playbackService?.next()
                            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playbackService?.previous()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_SHUTDOWN, "com.example.ACTION_SHUTDOWN",
            ACTION_STOP, "com.example.ACTION_STOP", "ACTION_STOP" -> {
                shutdownPlaybackSession()
                return START_NOT_STICKY
            }
            ACTION_PLAY, "ACTION_PLAY" -> {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    playbackService?.resume()
                }
            }
            ACTION_PAUSE, "ACTION_PAUSE" -> {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    playbackService?.pause()
                }
            }
            ACTION_NEXT, "ACTION_NEXT" -> {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    playbackService?.next()
                }
            }
            ACTION_PREVIOUS, "ACTION_PREVIOUS" -> {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    playbackService?.previous()
                }
            }
            else -> {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    androidx.media.session.MediaButtonReceiver.handleIntent(mediaSession, intent)
                }
            }
        }
        updateNotificationForCurrentState()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "AnyfyService")

        val intent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession.setSessionActivity(pendingIntent)

        notificationManager = MediaNotificationManager(this, mediaSession)

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    playbackService?.resume()
                }
            }
            override fun onPause() {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    playbackService?.pause()
                }
            }
            override fun onSkipToNext() {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    playbackService?.next()
                }
            }
            override fun onSkipToPrevious() {
                if (playbackService?.uiState?.value?.currentTrack != null) {
                    playbackService?.previous()
                }
            }
            override fun onSeekTo(pos: Long) {
                seekTo(pos)
            }
            override fun onStop() {
                shutdownPlaybackSession()
            }
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                if (playbackService?.uiState?.value?.currentTrack == null) {
                    return false
                }
                val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (keyEvent?.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (keyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { playbackService?.resume(); return true }
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { playbackService?.pause(); return true }
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        android.view.KeyEvent.KEYCODE_HEADSETHOOK -> { playbackService?.togglePlayPause(); return true }
                        android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { playbackService?.next(); return true }
                        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playbackService?.previous(); return true }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_BUTTON)
            addAction(ACTION_PLAY)
            addAction("ACTION_PLAY")
            addAction(ACTION_PAUSE)
            addAction("ACTION_PAUSE")
            addAction(ACTION_NEXT)
            addAction("ACTION_NEXT")
            addAction(ACTION_PREVIOUS)
            addAction("ACTION_PREVIOUS")
            addAction(ACTION_STOP)
            addAction("ACTION_STOP")
            addAction(ACTION_SHUTDOWN)
            priority = 1000
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                mediaButtonReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(mediaButtonReceiver, filter)
        }

        // Attach existing playback singleton instance immediately
        val existingPlaybackService = PlaybackService.getInstance(applicationContext)
        setPlaybackService(existingPlaybackService)
        val initialTrack = existingPlaybackService.uiState.value.currentTrack
        if (initialTrack != null) {
            postNotification(
                initialTrack,
                existingPlaybackService.uiState.value.isPlaying,
                existingPlaybackService.uiState.value.currentPositionMs,
                null
            )
        }
    }

    fun setPlaybackService(service: PlaybackService) {
        this.playbackService = service
        service.onShutdownRequested = {
            shutdownPlaybackSession()
        }
        observePlaybackState()
    }

    private fun observePlaybackState() {
        playbackStateJob?.cancel()
        val service = playbackService ?: return
        playbackStateJob = service.uiState.onEach { state ->
            val track = state.currentTrack
            if (track == null) {
                if (lastTrackId != null) {
                    shutdownPlaybackSession()
                }
                return@onEach
            }

            if (!mediaSession.isActive) {
                mediaSession.isActive = true
            }

            val trackChanged = track.id != lastTrackId
            val playStateChanged = state.isPlaying != lastIsPlaying
            val durationResolved = state.durationMs > 0L && state.durationMs != lastDurationMs

            if (trackChanged) {
                lastTrackId = track.id
                lastArtwork = null
                lastDurationMs = 0L
                updateMediaSessionMetadata(track, state.durationMs)
                updatePlaybackState(state.isPlaying, state.currentPositionMs, force = true)
            } else if (durationResolved) {
                updateMediaSessionMetadata(track, state.durationMs)
            }

            updatePlaybackState(state.isPlaying, state.currentPositionMs, force = trackChanged || playStateChanged)

            if (trackChanged || playStateChanged) {
                lastIsPlaying = state.isPlaying
                postNotification(track, state.isPlaying, state.currentPositionMs, lastArtwork)
            }
        }.launchIn(serviceScope)
    }

    private fun postNotification(
        track: Track,
        isPlaying: Boolean,
        positionMs: Long,
        artwork: android.graphics.Bitmap?
    ) {
        val notification = notificationManager.getNotification(
            track, isPlaying, positionMs, artwork
        )

        // Maintain foreground service priority while a track is loaded in session.
        // Calling stopForeground(DETACH) when paused causes Android to treat the process as cached
        // and kill it when backgrounded, causing playback to abruptly stop.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    101,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(101, notification)
            }
        } catch (e: Exception) {
            Log.e("MusicPlayerService", "Foreground service notification update fallback: ${e.message}")
            notificationManager.updateNotification(track, isPlaying, positionMs, artwork)
        }
    }

    fun seekTo(positionMs: Long) {
        if (playbackService?.uiState?.value?.currentTrack != null) {
            val isPlaying = playbackService?.uiState?.value?.isPlaying ?: true
            updatePlaybackState(isPlaying = isPlaying, position = positionMs, force = true)
            playbackService?.seekTo(positionMs)
        }
    }

    fun updateNotificationForCurrentState() {
        val state = playbackService?.uiState?.value ?: return
        val track = state.currentTrack ?: return
        postNotification(track, state.isPlaying, state.currentPositionMs, lastArtwork)
    }

    private fun updateMediaSessionMetadata(track: Track, resolvedDurationMs: Long = 0L) {
        val durationMs = if (resolvedDurationMs > 0L) {
            resolvedDurationMs
        } else {
            (track.durationSeconds * 1000L).coerceAtLeast(0L)
        }
        lastDurationMs = durationMs

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

        if (lastArtwork != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, lastArtwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, lastArtwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, lastArtwork)
        }

        mediaSession.setMetadata(metadataBuilder.build())

        // Asynchronously load artwork if not already present
        val artworkUrl = track.displayArtwork.ifBlank { track.displayBannerArtwork.ifBlank { track.artwork } }
        if (artworkUrl.isNotBlank() && lastArtwork == null) {
            notificationManager.loadArtwork(artworkUrl) { bitmap ->
                if (lastTrackId == track.id) {
                    lastArtwork = bitmap
                    val currentDur = lastDurationMs.takeIf { it > 0L } ?: (track.durationSeconds * 1000L).coerceAtLeast(0L)
                    val newMetadata = MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDur)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                        .build()
                    mediaSession.setMetadata(newMetadata)

                    // Re-notify to display artwork in notification
                    playbackService?.uiState?.value?.let { state ->
                        postNotification(track, state.isPlaying, state.currentPositionMs, bitmap)
                    }
                }
            }
        }
    }

    private fun updatePlaybackState(isPlaying: Boolean, position: Long, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        val playStateChanged = lastIsPlaying != isPlaying

        // Calculate extrapolated position since last update if playing
        val elapsedSinceLastUpdate = if (lastPlaybackStateUpdateTimeMs > 0L) (now - lastPlaybackStateUpdateTimeMs) else 0L
        val extrapolatedPosition = if (lastIsPlaying == true) {
            lastReportedPositionMs + elapsedSinceLastUpdate
        } else {
            lastReportedPositionMs
        }
        val drift = kotlin.math.abs(position - extrapolatedPosition)

        // Throttle updates: only dispatch IPC when forced, state changed, drift > 1500ms, or periodic keepalive
        val shouldUpdate = force || playStateChanged || drift > 1500L || (now - lastPlaybackStateUpdateTimeMs >= 2500L)

        if (!shouldUpdate && lastPlaybackStateUpdateTimeMs > 0L) {
            return
        }

        lastIsPlaying = isPlaying
        lastReportedPositionMs = position
        lastPlaybackStateUpdateTimeMs = now

        val playbackSpeed = if (isPlaying) 1.0f else 0.0f
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, position, playbackSpeed, now)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }

    override fun onDestroy() {
        serviceScope.coroutineContext[Job]?.cancel()
        playbackService?.onShutdownRequested = null
        mediaSession.release()
        try {
            unregisterReceiver(mediaButtonReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
