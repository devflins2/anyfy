package com.example

import com.example.model.Track
import com.example.model.VisualQuality
import com.example.service.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlinsMusicUnitTest {

    @Test
    fun track_durationFormatting_isAccurate() {
        val track = Track(
            id = "test_1",
            title = "Aetherial Echo",
            artist = "Kaelen Voss",
            artwork = "https://example.com/art.jpg",
            durationSeconds = 214
        )
        assertEquals("3:34", track.durationFormatted)
    }

    @Test
    fun playbackService_initialStateAndControls() {
        val service = PlaybackService()
        val track1 = Track(
            id = "t1",
            title = "Song 1",
            artist = "Artist 1",
            artwork = "https://example.com/1.jpg",
            durationSeconds = 180
        )
        val track2 = Track(
            id = "t2",
            title = "Song 2",
            artist = "Artist 2",
            artwork = "https://example.com/2.jpg",
            durationSeconds = 200
        )

        service.playTrack(track1, listOf(track1, track2))
        val state = service.uiState.value
        assertEquals("t1", state.currentTrack?.id)
        assertTrue(state.isPlaying)
        assertEquals(2, state.queue.size)

        service.togglePlayPause()
        assertFalse(service.uiState.value.isPlaying)

        service.next()
        assertEquals("t2", service.uiState.value.currentTrack?.id)
    }

    @Test
    fun visualQuality_blobCounts_areAccurate() {
        assertEquals(3, VisualQuality.BATTERY_SAVER.blobCount)
        assertEquals(4, VisualQuality.BALANCED.blobCount)
        assertEquals(5, VisualQuality.HIGH.blobCount)
    }

    @Test
    fun playbackService_playerStatusTransitions_andResilientQueue() {
        val service = PlaybackService()
        val track1 = Track(
            id = "t1",
            title = "Song 1",
            artist = "Artist 1",
            artwork = "https://example.com/1.jpg",
            durationSeconds = 180
        )
        val track2 = Track(
            id = "t2",
            title = "Song 2",
            artist = "Artist 2",
            artwork = "https://example.com/2.jpg",
            durationSeconds = 200
        )
        service.playTrack(track1, listOf(track1, track2))
        assertEquals(com.example.service.PlayerStatus.PLAYING, stateOrStatus(service))
        assertEquals(2, service.uiState.value.queue.size)

        // Queue is preserved even on manual track skip or error
        service.next()
        val nextState = service.uiState.value
        assertEquals("t2", nextState.currentTrack?.id)
        assertEquals(2, nextState.queue.size)
    }

    private fun stateOrStatus(service: PlaybackService) = service.uiState.value.status

    @Test
    fun playbackService_singleton_isConsistent() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val inst1 = PlaybackService.getInstance(context)
        val inst2 = PlaybackService.getInstance(context)
        org.junit.Assert.assertSame(inst1, inst2)
    }

    @Test
    fun mediaNotificationManager_createsCompliantNotification() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val mediaSession = android.support.v4.media.session.MediaSessionCompat(context, "TestSession")
        val manager = com.example.service.MediaNotificationManager(context, mediaSession)

        val track = Track(
            id = "notif_track",
            title = "Echoes in Amber",
            artist = "Solar Drift",
            artwork = "https://example.com/art.jpg",
            durationSeconds = 210
        )

        val playingNotification = manager.getNotification(track, isPlaying = true, positionMs = 5000L)
        assertNotNull(playingNotification)
        assertEquals("Echoes in Amber", playingNotification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals("Solar Drift", playingNotification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
        assertTrue((playingNotification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, playingNotification.visibility)
        assertEquals(3, playingNotification.actions?.size)

        val pausedNotification = manager.getNotification(track, isPlaying = false, positionMs = 5000L)
        assertEquals(0, (pausedNotification.flags and android.app.Notification.FLAG_ONGOING_EVENT))

        mediaSession.release()
    }
}

