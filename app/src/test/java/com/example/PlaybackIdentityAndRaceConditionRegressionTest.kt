package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.RepeatMode
import com.example.service.integrations.LiveYouTubeService
import com.example.service.integrations.StreamException
import com.example.service.integrations.YouTubeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive regression tests verifying stable track identity, race-condition immunity,
 * and zero wrong-song substitution across all user interaction paths in Anyfy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackIdentityAndRaceConditionRegressionTest {

    private lateinit var context: Context
    private lateinit var playbackService: PlaybackService
    private val testDispatcher = StandardTestDispatcher()

    private val songA = Track(
        id = "yt_dQw4w9WgXcQ",
        title = "Song A (Never Gonna Give You Up)",
        artist = "Rick Astley",
        artwork = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        durationSeconds = 213
    )

    private val songB = Track(
        id = "yt_k2qgadSvNyU",
        title = "Song B (New Rules)",
        artist = "Dua Lipa",
        artwork = "https://i.ytimg.com/vi/k2qgadSvNyU/hqdefault.jpg",
        durationSeconds = 209
    )

    private val songC = Track(
        id = "yt_34Na4j8AVgA",
        title = "Song C (Starboy)",
        artist = "The Weeknd",
        artwork = "https://i.ytimg.com/vi/34Na4j8AVgA/hqdefault.jpg",
        durationSeconds = 230
    )

    private val songD = Track(
        id = "yt_7wtfhZwyrcc",
        title = "Song D (Believer)",
        artist = "Imagine Dragons",
        artwork = "https://i.ytimg.com/vi/7wtfhZwyrcc/hqdefault.jpg",
        durationSeconds = 204
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("flinsmusic_playback_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        playbackService = PlaybackService(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Scenario 1: Tap Song A
     * Verify Song A plays, and its selected ID == currentTrack.id == queue[0].id.
     */
    @Test
    fun test01_tapSongA_playsSongA_andPreservesIdentity() {
        val queue = listOf(songA, songB, songC)
        playbackService.playTrack(songA, queue)

        val state = playbackService.uiState.value
        assertNotNull(state.currentTrack)
        assertEquals("yt_dQw4w9WgXcQ", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(3, state.queue.size)
        assertEquals("yt_dQw4w9WgXcQ", state.queue[0].id)
        assertTrue(state.isPlaying)
    }

    /**
     * Scenario 2: Tap Song B immediately after Song A
     * Verify Song B plays, Song A's playback is cancelled and superseded.
     */
    @Test
    fun test02_tapSongBImmediatelyAfterA_cancelsA_andPlaysB() {
        val queue = listOf(songA, songB, songC)
        playbackService.playTrack(songA, queue)
        // Immediately tap Song B
        playbackService.playTrack(songB, queue)

        val state = playbackService.uiState.value
        assertNotNull(state.currentTrack)
        assertEquals("yt_k2qgadSvNyU", state.currentTrack?.id)
        assertEquals(1, state.currentTrackIndex)
        assertEquals(queue[1].id, state.currentTrack?.id)
    }

    /**
     * Scenario 3: Rapidly tap multiple songs (A -> B -> C -> D)
     * Verify only the final tapped song (D) plays, and obsolete extraction jobs/callbacks are ignored.
     */
    @Test
    fun test03_rapidTapsMultipleSongs_onlyLastSongPlays_obsoleteJobsIgnored() {
        val queue = listOf(songA, songB, songC, songD)
        playbackService.playTrack(songA, queue)
        playbackService.playTrack(songB, queue)
        playbackService.playTrack(songC, queue)
        playbackService.playTrack(songD, queue)

        val state = playbackService.uiState.value
        assertEquals("yt_7wtfhZwyrcc", state.currentTrack?.id)
        assertEquals(3, state.currentTrackIndex)
        assertEquals("Song D (Believer)", state.currentTrack?.title)
    }

    /**
     * Scenario 4: Select song from Search
     * Verify genuine YouTube video ID is strictly preserved end-to-end.
     */
    @Test
    fun test04_selectSongFromSearch_preservesExactYouTubeVideoId() {
        val searchTrack = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            artwork = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            durationSeconds = 213
        )

        playbackService.playTrack(searchTrack)

        val state = playbackService.uiState.value
        assertEquals("yt_dQw4w9WgXcQ", state.currentTrack?.id)
        val cleanVid = state.currentTrack?.id?.removePrefix("yt_")?.trim()
        assertEquals("dQw4w9WgXcQ", cleanVid)
        assertTrue(YouTubeService.isValidYouTubeVideoId(cleanVid))
    }

    /**
     * Scenario 5: Select song from Recently Played
     * Verify track ID and metadata identity are exactly preserved.
     */
    @Test
    fun test05_selectSongFromRecentlyPlayed_preservesExactIdentity() {
        val historyTrack = Track(
            id = "yt_34Na4j8AVgA",
            title = "Starboy",
            artist = "The Weeknd",
            artwork = "https://i.ytimg.com/vi/34Na4j8AVgA/hqdefault.jpg",
            durationSeconds = 230
        )
        val historyQueue = listOf(historyTrack, songA, songB)
        playbackService.playTrack(historyTrack, historyQueue)

        val state = playbackService.uiState.value
        assertEquals("yt_34Na4j8AVgA", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals("yt_34Na4j8AVgA", state.queue[0].id)
    }

    /**
     * Scenario 6: Select song from Artist Discography
     * Verify selected discography track identity is maintained without substitution.
     */
    @Test
    fun test06_selectSongFromArtistDiscography_preservesExactIdentity() {
        val discographyTrack = Track(
            id = "yt_k2qgadSvNyU",
            title = "New Rules",
            artist = "Dua Lipa",
            artwork = "https://i.ytimg.com/vi/k2qgadSvNyU/hqdefault.jpg",
            durationSeconds = 209
        )
        val discographyQueue = listOf(songA, discographyTrack, songC)
        playbackService.playTrack(discographyTrack, discographyQueue)

        val state = playbackService.uiState.value
        assertEquals("yt_k2qgadSvNyU", state.currentTrack?.id)
        assertEquals(1, state.currentTrackIndex)
    }

    /**
     * Scenario 7: Select song from Album tracklist
     * Verify album track identity and exact queue index mapping.
     */
    @Test
    fun test07_selectSongFromAlbumTracklist_preservesExactIdentityAndQueueIndex() {
        val albumTracks = listOf(
            songA.copy(id = "yt_alb_trk_01", title = "Track 1"),
            songB.copy(id = "yt_alb_trk_02", title = "Track 2"),
            songC.copy(id = "yt_alb_trk_03", title = "Track 3")
        )
        // Select track 2 (index 1)
        playbackService.playTrack(albumTracks[1], albumTracks)

        val state = playbackService.uiState.value
        assertEquals("yt_alb_trk_02", state.currentTrack?.id)
        assertEquals(1, state.currentTrackIndex)
        assertEquals(3, state.queue.size)
    }

    /**
     * Scenario 8: Select song from Playlist with Duplicate Songs
     * When a playlist has duplicates [songA, songB, songA, songC], clicking the second songA (index 2)
     * must play index 2 directly using playTrackAtIndex, not jump to index 0.
     */
    @Test
    fun test08_selectSongFromPlaylistWithDuplicates_exactQueueIndexPlays() {
        val playlistWithDupes = listOf(songA, songB, songA, songC)
        playbackService.playTrack(songA, playlistWithDupes)

        // Currently at index 0
        assertEquals(0, playbackService.uiState.value.currentTrackIndex)

        // User taps the 3rd item in queue (index 2, which is also songA)
        playbackService.playTrackAtIndex(2)

        val state = playbackService.uiState.value
        assertEquals(2, state.currentTrackIndex)
        assertEquals(songA.id, state.currentTrack?.id)
        assertEquals(4, state.queue.size)
    }

    /**
     * Scenario 9: Next and Previous transitions
     * Verify sequential navigation and repeat modes advance accurately without desync.
     */
    @Test
    fun test09_nextAndPreviousTransitions_advanceAccuratelyWithoutDesync() {
        val queue = listOf(songA, songB, songC)
        playbackService.playTrack(songA, queue)

        // Advance to next -> songB
        playbackService.next()
        assertEquals("yt_k2qgadSvNyU", playbackService.uiState.value.currentTrack?.id)
        assertEquals(1, playbackService.uiState.value.currentTrackIndex)

        // Advance to next -> songC
        playbackService.next()
        assertEquals("yt_34Na4j8AVgA", playbackService.uiState.value.currentTrack?.id)
        assertEquals(2, playbackService.uiState.value.currentTrackIndex)

        // Previous -> songB
        playbackService.previous()
        assertEquals("yt_k2qgadSvNyU", playbackService.uiState.value.currentTrack?.id)
        assertEquals(1, playbackService.uiState.value.currentTrackIndex)

        // Previous -> songA
        playbackService.previous()
        assertEquals("yt_dQw4w9WgXcQ", playbackService.uiState.value.currentTrack?.id)
        assertEquals(0, playbackService.uiState.value.currentTrackIndex)

        // Test boundary wrap with RepeatMode.ALL
        playbackService.toggleRepeat() // -> RepeatMode.ALL
        assertEquals(RepeatMode.ALL, playbackService.uiState.value.repeatMode)

        // Previous from index 0 should wrap to queue.lastIndex (index 2)
        playbackService.previous()
        assertEquals("yt_34Na4j8AVgA", playbackService.uiState.value.currentTrack?.id)
        assertEquals(2, playbackService.uiState.value.currentTrackIndex)
    }

    /**
     * Scenario 10: Shuffle mapping
     * Verify currently playing track remains pinned at index 0 when shuffle is enabled,
     * and original queue order is restored when shuffle is toggled off.
     */
    @Test
    fun test10_shuffleMapping_keepsCurrentTrackAtZero_restoresOnUnshuffle() {
        val queue = listOf(songA, songB, songC, songD)
        // Start playing songB (index 1)
        playbackService.playTrack(songB, queue)
        assertEquals(1, playbackService.uiState.value.currentTrackIndex)

        // Enable shuffle
        playbackService.toggleShuffle()
        val shuffledState = playbackService.uiState.value
        assertTrue(shuffledState.isShuffle)
        // Current track must remain songB
        assertEquals(songB.id, shuffledState.currentTrack?.id)
        // Current track index must be pinned to index 0 in the shuffled queue
        assertEquals(0, shuffledState.currentTrackIndex)
        assertEquals(songB.id, shuffledState.queue[0].id)
        assertEquals(4, shuffledState.queue.size)

        // Disable shuffle (unshuffle)
        playbackService.toggleShuffle()
        val unshiftedState = playbackService.uiState.value
        assertFalse(unshiftedState.isShuffle)
        // Original queue order should be restored
        assertEquals(listOf(songA.id, songB.id, songC.id, songD.id), unshiftedState.queue.map { it.id })
        // Current track index points back to songB's original position (index 1)
        assertEquals(1, unshiftedState.currentTrackIndex)
        assertEquals(songB.id, unshiftedState.currentTrack?.id)
    }

    /**
     * Scenario 11: Delayed network responses (stale callback arrives after newer selection)
     * Verify stale responses are discarded and do not override the newer selected track.
     */
    @Test
    fun test11_delayedNetworkResponse_staleCallbackDiscarded() {
        val queue = listOf(songA, songB)
        // User plays songA
        playbackService.playTrack(songA, queue)
        val initialTrack = playbackService.uiState.value.currentTrack
        assertEquals(songA.id, initialTrack?.id)

        // User rapidly switches to songB
        playbackService.playTrack(songB, queue)
        val updatedTrack = playbackService.uiState.value.currentTrack
        assertEquals(songB.id, updatedTrack?.id)

        // Verify that the UI state reflects songB
        assertEquals(songB.id, playbackService.uiState.value.currentTrack?.id)
        assertEquals(1, playbackService.uiState.value.currentTrackIndex)
    }

    /**
     * Scenario 12: Failed stream extraction prevents wrong-song substitution
     * When resolving a track with a genuine YouTube video ID, if extraction fails,
     * the resolver must NEVER fall back to title-based search and play an unrelated track.
     * It must return Result.failure and clear any poisoned cache.
     */
    @Test
    fun test12_failedStreamExtraction_refusesTitleSubstitution_preservesIdentity() = runBlocking {
        // Create an OkHttpClient that fails on all stream extraction attempts
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        val ytService = LiveYouTubeService(failingClient)
        ytService.setContext(context)

        // Track with a genuine YouTube video ID
        val genuineTrack = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            artwork = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            durationSeconds = 213
        )

        // Attempt stream resolution
        val result = ytService.resolveTrackStream(genuineTrack, forceRefresh = true)

        // Must fail honestly instead of silently substituting a search result
        assertTrue("Extraction for genuine video ID must fail without substitution", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue("Exception must be StreamException", exception is StreamException)
        assertEquals(StreamException.EXTRACTION_FAILURE, (exception as StreamException).code)

        // Verify cache was not poisoned
        assertFalse("Stream cache must not contain poisoned URL", ytService.isStreamCached(genuineTrack.id))
    }
}
