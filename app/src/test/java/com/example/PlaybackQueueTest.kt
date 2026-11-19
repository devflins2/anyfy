package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackQueueTest {

    private lateinit var context: Context
    private lateinit var service: PlaybackService

    private val track1 = Track(
        id = "t1",
        title = "Track One",
        artist = "Artist A",
        artwork = "https://example.com/1.jpg",
        durationSeconds = 180
    )

    private val track2 = Track(
        id = "t2",
        title = "Track Two",
        artist = "Artist B",
        artwork = "https://example.com/2.jpg",
        durationSeconds = 210
    )

    private val track3 = Track(
        id = "t3",
        title = "Track Three",
        artist = "Artist C",
        artwork = "https://example.com/3.jpg",
        durationSeconds = 240
    )

    private val track4 = Track(
        id = "t4",
        title = "Track Four",
        artist = "Artist D",
        artwork = "https://example.com/4.jpg",
        durationSeconds = 195
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear preferences before each test
        context.getSharedPreferences("flinsmusic_playback_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        service = PlaybackService(context)
    }

    @Test
    fun playTrack_setsCurrentTrack_andInitializesQueue() {
        service.playTrack(track1, listOf(track1, track2, track3))
        val state = service.uiState.value

        assertEquals("t1", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(3, state.queue.size)
        assertTrue(state.isPlaying)
    }

    @Test
    fun playTrack_whenTrackNotInQueue_prependsToQueue() {
        service.playTrack(track1, listOf(track2, track3))
        val state = service.uiState.value

        assertEquals("t1", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(3, state.queue.size)
        assertEquals("t1", state.queue[0].id)
    }

    @Test
    fun playNext_insertsImmediatelyAfterCurrentTrack() {
        service.playTrack(track1, listOf(track1, track2, track3))
        service.playNext(track4)

        val state = service.uiState.value
        assertEquals("t1", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(4, state.queue.size)
        // track4 must be right after track1
        assertEquals("t4", state.queue[1].id)
        // track2 and track3 must maintain relative order
        assertEquals("t2", state.queue[2].id)
        assertEquals("t3", state.queue[3].id)
    }

    @Test
    fun playNext_whenNoCurrentTrack_playsImmediately() {
        service.playNext(track1)
        val state = service.uiState.value

        assertEquals("t1", state.currentTrack?.id)
        assertEquals(1, state.queue.size)
    }

    @Test
    fun addToQueue_appendsToEndOfQueue_withoutInterruptingPlayback() {
        service.playTrack(track1, listOf(track1, track2))
        service.addToQueue(track3)

        val state = service.uiState.value
        assertEquals("t1", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(3, state.queue.size)
        assertEquals("t3", state.queue.last().id)
        assertTrue(state.isPlaying)
    }

    @Test
    fun addToQueue_preventsRapidDoubleTapDuplicates() {
        service.playTrack(track1, listOf(track1))
        service.addToQueue(track2)
        service.addToQueue(track2) // Instant rapid second tap

        val state = service.uiState.value
        assertEquals(2, state.queue.size)
        assertEquals("t2", state.queue[1].id)
    }

    @Test
    fun reorderQueue_movesItemCorrectly_andPreservesCurrentPlayback() {
        // Queue: [t1 (current, idx 0), t2 (idx 1), t3 (idx 2), t4 (idx 3)]
        service.playTrack(track1, listOf(track1, track2, track3, track4))

        // Move t4 (idx 3) to idx 1 (before t2)
        service.reorderQueue(3, 1)

        val state = service.uiState.value
        assertEquals("t1", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(listOf("t1", "t4", "t2", "t3"), state.queue.map { it.id })
    }

    @Test
    fun removeFromQueue_removesUpcomingItem_withoutStoppingPlayback() {
        service.playTrack(track1, listOf(track1, track2, track3))

        // Remove t2 (idx 1)
        service.removeFromQueue(1)

        val state = service.uiState.value
        assertEquals("t1", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(2, state.queue.size)
        assertEquals(listOf("t1", "t3"), state.queue.map { it.id })
        assertTrue(state.isPlaying)
    }

    @Test
    fun clearQueue_removesUpcomingTracks_keepsCurrentTrackPlaying() {
        service.playTrack(track1, listOf(track1, track2, track3))
        service.clearQueue()

        val state = service.uiState.value
        assertEquals("t1", state.currentTrack?.id)
        assertEquals(1, state.queue.size)
        assertEquals(listOf("t1"), state.queue.map { it.id })
        assertTrue(state.isPlaying)
    }

    @Test
    fun nextAndPrevious_advanceAccurately() {
        service.playTrack(track1, listOf(track1, track2, track3))

        service.next()
        assertEquals("t2", service.uiState.value.currentTrack?.id)
        assertEquals(1, service.uiState.value.currentTrackIndex)

        service.next()
        assertEquals("t3", service.uiState.value.currentTrack?.id)
        assertEquals(2, service.uiState.value.currentTrackIndex)

        service.previous()
        assertEquals("t2", service.uiState.value.currentTrack?.id)
        assertEquals(1, service.uiState.value.currentTrackIndex)
    }

    @Test
    fun advanceToNextTrack_withShuffle_picksFromOtherIndices() {
        service.playTrack(track1, listOf(track1, track2, track3, track4))
        service.toggleShuffle()
        assertTrue(service.uiState.value.isShuffle)

        service.next()
        val current = service.uiState.value.currentTrack
        assertNotNull(current)
        // With shuffle enabled on track1 (idx 0), next track must be one of the other tracks
        assertTrue(current?.id in listOf("t2", "t3", "t4"))
    }

    @Test
    fun queueStatePersistence_acrossServiceRestarts() {
        service.playTrack(track1, listOf(track1, track2, track3))
        service.next() // Now playing t2 at idx 1
        service.toggleShuffle()

        val allTracks = listOf(track1, track2, track3, track4)

        // Create a new instance representing an app restart
        val newService = PlaybackService(context)
        newService.restoreSavedQueue { ids ->
            ids.mapNotNull { id -> allTracks.find { it.id == id } }
        }

        val restoredState = newService.uiState.value
        assertEquals(3, restoredState.queue.size)
        assertEquals("t2", restoredState.currentTrack?.id)
        assertEquals(restoredState.queue.indexOfFirst { it.id == "t2" }, restoredState.currentTrackIndex)
        assertTrue(restoredState.isShuffle)
        // On restart, it must not autoplay audio
        assertFalse(restoredState.isPlaying)
    }
}
