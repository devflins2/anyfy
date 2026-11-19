package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.MainActivity
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MediaNotificationManager(
    private val context: Context,
    private val mediaSession: MediaSessionCompat
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "flins_music_channel"
    private val notificationId = 101
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Anyfy Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun getNotification(
        track: Track,
        isPlaying: Boolean,
        positionMs: Long,
        artwork: Bitmap? = null
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId).apply {
            // Song Title and Artist
            setContentTitle(track.title)
            setContentText(track.artist)
            setSubText("Anyfy")
            setSmallIcon(android.R.drawable.ic_media_play)

            // Large Icon (Artwork)
            val currentArt = artwork ?: mediaSession.controller.metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            if (currentArt != null) {
                setLargeIcon(currentArt)
            }

            // Intent to open the app when the notification is clicked
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setContentIntent(pendingIntent)

            // When user dismisses/swipes away the notification when paused, trigger graceful session stop
            val stopPendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_STOP
            )
            setDeleteIntent(stopPendingIntent)

            // Notification stays non-dismissible only while actively playing
            setOngoing(isPlaying)

            // Previous Action
            val prevPendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous,
                    "Previous",
                    prevPendingIntent
                )
            )

            // Play / Pause Action
            val playPauseAction = if (isPlaying) {
                val pausePendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
                    context,
                    PlaybackStateCompat.ACTION_PAUSE
                )
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    pausePendingIntent
                )
            } else {
                val playPendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
                    context,
                    PlaybackStateCompat.ACTION_PLAY
                )
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    "Play",
                    playPendingIntent
                )
            }
            addAction(playPauseAction)

            // Next Action
            val nextPendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )
            addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next,
                    "Next",
                    nextPendingIntent
                )
            )

            // Standard MediaStyle linking to MediaSession
            setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

            // Make controls visible on lock screen
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setPriority(NotificationCompat.PRIORITY_LOW)
        }

        return builder.build()
    }

    fun updateNotification(track: Track, isPlaying: Boolean, positionMs: Long, artwork: Bitmap? = null) {
        val notification = getNotification(track, isPlaying, positionMs, artwork)
        notificationManager.notify(notificationId, notification)
    }

    fun cancelNotification() {
        notificationManager.cancel(notificationId)
    }

    fun loadArtwork(url: String, onLoaded: (Bitmap) -> Unit) {
        if (url.isBlank()) return
        scope.launch {
            try {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(300, 300) // Downsample to notification icon dimensions to conserve memory
                    .allowHardware(false) // Must be software bitmap for RemoteViews/MediaSession
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        ?: result.drawable.toBitmap()
                    onLoaded(bitmap)
                }
            } catch (e: Exception) {
                Log.e("MediaNotificationManager", "Error loading artwork for notification", e)
            }
        }
    }
}
