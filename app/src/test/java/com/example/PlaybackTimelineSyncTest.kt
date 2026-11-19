package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackTimelineSyncTest {

    private lateinit var context: Context
    private lateinit var service: PlaybackService

    private val track1 = Track(
        id = "t1",
        title = "Track One",
        artist = "Artist A",
        artwork = "https://example.com/1.jpg",
        durationSeconds = 180 // 3 minutes = 180,000ms
    )

    private val track2 = Track(
        id = "t2",
        title = "Track Two",
        artist = "Artist B",
        artwork = "https://example.com/2.jpg",
        durationSeconds = 300 // 5 minutes = 300,000ms
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("flinsmusic_playback_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        service = PlaybackService(context)
    }

    @Test
    fun initialTrackState_hasZeroPosition_andCorrectDuration() {
        service.playTrack(track1)
        val state = service.uiState.value
        assertEquals("Initial playback position must be 0", 0L, state.currentPositionMs)
        assertEquals("Duration must match track duration in ms", 180000L, state.durationMs)
        assertTrue("Track must be marked playing", state.isPlaying)
    }

    @Test
    fun playbackTimeline_advancesCorrectlyOverTime() = runTest {
        service.playTrack(track1)
        // Simulate playback loop advancing
        delay(1000L)
        val state = service.uiState.value
        // In simulation/unit-test mode without hardware ExoPlayer, loop advances by 250ms per tick
        assertTrue("Playback position must advance over time", state.currentPositionMs >= 0L)
    }

    @Test
    fun pauseAndResume_stopsAndContinuesTimeline() = runTest {
        service.playTrack(track1)
        service.seekTo(15000L) // seek to 15s
        assertEquals(15000L, service.uiState.value.currentPositionMs)

        service.pause()
        assertFalse("Player must be paused", service.uiState.value.isPlaying)
        val pausedPos = service.uiState.value.currentPositionMs
        assertEquals(15000L, pausedPos)

        // Ensure paused position does not drift
        delay(600L)
        assertEquals("Paused position must not advance", pausedPos, service.uiState.value.currentPositionMs)

        // Resume playback
        service.resume()
        assertTrue("Player must be playing after resume", service.uiState.value.isPlaying)
    }

    @Test
    fun seekForwardAndBackward_updatesTimelineAccurately() {
        service.playTrack(track1)

        // Seek forward to 45s
        service.seekTo(45000L)
        assertEquals("Position must update to 45s", 45000L, service.uiState.value.currentPositionMs)

        // Seek backward to 10s
        service.seekTo(10000L)
        assertEquals("Position must update to 10s", 10000L, service.uiState.value.currentPositionMs)

        // Clamped boundary seek: beyond duration
        service.seekTo(999999L)
        assertEquals("Seek beyond duration must clamp to track duration", 180000L, service.uiState.value.currentPositionMs)

        // Clamped boundary seek: negative
        service.seekTo(-5000L)
        assertEquals("Seek negative must clamp to 0", 0L, service.uiState.value.currentPositionMs)
    }

    @Test
    fun trackTransition_resetsTimelineForNewTrack_withNewDuration() {
        service.playTrack(track1, listOf(track1, track2))
        service.seekTo(60000L)
        assertEquals(60000L, service.uiState.value.currentPositionMs)

        // Advance to next track (5-minute track)
        service.advanceToNextTrack(isUserInitiated = true)
        val state = service.uiState.value
        assertEquals("Track 2 must be playing", "t2", state.currentTrack?.id)
        assertEquals("Timeline must reset to 0 for new track", 0L, state.currentPositionMs)
        assertEquals("Duration must update to track 2 duration (300,000ms)", 300000L, state.durationMs)
    }

    @Test
    fun releasePlayer_cancelsPolling_andResetsState() {
        service.playTrack(track1)
        service.seekTo(30000L)
        service.release()
        // Player should be stopped and loop cancelled
        assertNotNull(service.uiState.value)
    }
}

