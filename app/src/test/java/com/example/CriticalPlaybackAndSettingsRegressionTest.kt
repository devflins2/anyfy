package com.example

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.core.app.ApplicationProvider
import com.example.model.Track
import com.example.service.MusicPlayerService
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CriticalPlaybackAndSettingsRegressionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var playbackService: PlaybackService

    private val testTrack = Track(
        id = "local_test_track_1",
        title = "Test Song",
        artist = "Test Artist",
        album = "Test Album",
        artwork = "https://example.com/art.jpg",
        durationSeconds = 200 // 200,000 ms
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

    @Test
    fun testBug1_SettingsScreen_HasExactlyOneYouTubeMusicIntegrationSection() {
        // Find SettingsScreen source file
        val possiblePaths = listOf(
            File("app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt"),
            File("src/main/java/com/example/ui/screens/settings/SettingsScreen.kt")
        )
        val file = possiblePaths.firstOrNull { it.exists() }
        assertNotNull("SettingsScreen.kt must exist", file)

        val content = file!!.readText()
        val titleMatches = Regex("text\\s*=\\s*\"YOUTUBE MUSIC INTEGRATION\"").findAll(content).count()
        assertEquals(
            "SettingsScreen must have exactly ONE 'YOUTUBE MUSIC INTEGRATION' header",
            1,
            titleMatches
        )

        val keyMatches = Regex("item\\(key\\s*=\\s*\"settings_youtube_music_integration\"\\)").findAll(content).count()
        assertEquals(
            "SettingsScreen must have exactly ONE unique key 'settings_youtube_music_integration'",
            1,
            keyMatches
        )
    }

    @Test
    fun testBug2_MusicPlayerService_SessionDurationAndTimelineAccuracy() = runTest(testDispatcher) {
        val serviceController = Robolectric.buildService(MusicPlayerService::class.java).create().startCommand(0, 0)
        val musicPlayerService = serviceController.get()
        musicPlayerService.setPlaybackService(playbackService)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        // Play track
        playbackService.playTrack(testTrack)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        val session = musicPlayerService.getMediaSession()
        assertNotNull("MediaSession must be initialized", session)
        assertTrue("MediaSession must be active while playing", session.isActive)

        val metadata = session.controller.metadata
        if (metadata != null) {
            assertEquals("Test Song", metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            assertEquals("Test Artist", metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
            val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            assertEquals(200000L, duration)
        }

        // Verify PlaybackStateCompat
        val playbackState = session.controller.playbackState
        assertNotNull("PlaybackState must be published to MediaSession", playbackState)
        assertTrue(
            "Actions must include SEEK_TO",
            playbackState!!.actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L
        )

        // Test seeking from notification callback
        musicPlayerService.seekTo(45000L)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        val stateAfterSeek = session.controller.playbackState
        assertEquals("Position should immediately reflect seek without snap-back", 45000L, stateAfterSeek?.position)
        assertEquals(45000L, playbackService.uiState.value.currentPositionMs)

        serviceController.destroy()
    }

    @Test
    fun testBug3_CompletedSongReplay_ResetsPositionAndResumesAudio() = runTest(testDispatcher) {
        playbackService.playTrack(testTrack)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        // Fast-forward / complete the track
        playbackService.seekTo(200000L)
        playbackService.pause()
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        // Manually trigger track completion state
        playbackService.seekTo(200000L)
        // Calling resume when song is completed should reset position to 0L and start playing
        playbackService.resume()
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        val state = playbackService.uiState.value
        assertEquals("Replaying completed track must start from position 0L", 0L, state.currentPositionMs)
        assertTrue("Track must be marked playing on replay", state.isPlaying)
        assertTrue("Status must be PLAYING or EXTRACTING", state.status == PlayerStatus.PLAYING || state.status == PlayerStatus.EXTRACTING)
    }

    @Test
    fun testBug3_CompletedSong_SeekToRewindAllowsPlayback() = runTest(testDispatcher) {
        playbackService.playTrack(testTrack)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        // Seek to end
        playbackService.seekTo(200000L)
        playbackService.pause()
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()

        // User scrubs back to 30 seconds
        playbackService.seekTo(30000L)
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()
        assertEquals(30000L, playbackService.uiState.value.currentPositionMs)

        // Resume playback from scrubbed position
        playbackService.resume()
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()
        val state = playbackService.uiState.value
        assertEquals(30000L, state.currentPositionMs)
        assertTrue(state.isPlaying)
        assertEquals(PlayerStatus.PLAYING, state.status)
    }
}
