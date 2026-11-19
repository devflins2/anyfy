package com.example

import android.content.Context
import android.content.Intent
import android.support.v4.media.session.PlaybackStateCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.LikedTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.RecentlyPlayedEntity
import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.MusicPlayerService
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.viewmodel.AuthState
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogoutPlaybackBehaviorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val sampleTrack1 = Track(
        id = "trk_song_1",
        title = "Midnight Horizon",
        artist = "Celeste Blue",
        artwork = "https://example.com/art1.jpg",
        durationSeconds = 240
    )

    private val sampleTrack2 = Track(
        id = "trk_song_2",
        title = "Neon Drift",
        artist = "Kaelen Voss",
        artwork = "https://example.com/art2.jpg",
        durationSeconds = 195
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun logout_duringPlayingState_stopsPlayback_clearsQueue_andResetsState() {
        val service = PlaybackService(context)
        service.playTrack(sampleTrack1, listOf(sampleTrack1, sampleTrack2))

        // Assert playing state is set
        assertEquals(sampleTrack1.id, service.uiState.value.currentTrack?.id)
        assertTrue(service.uiState.value.isPlaying)
        assertEquals(2, service.uiState.value.queue.size)

        // Trigger Spotify-style logout shutdown
        service.stopAndClearPlayback()

        val state = service.uiState.value
        assertNull("Current track must be null after logout", state.currentTrack)
        assertFalse("isPlaying must be false after logout", state.isPlaying)
        assertEquals(PlayerStatus.IDLE, state.status)
        assertTrue("Queue must be completely empty after logout", state.queue.isEmpty())
        assertEquals(0L, state.currentPositionMs)
        assertEquals(0L, state.durationMs)

        // Resumption controls must do nothing after logout
        service.resume()
        assertFalse("resume() must not resume playback after logout", service.uiState.value.isPlaying)
        assertNull(service.uiState.value.currentTrack)

        service.togglePlayPause()
        assertFalse("togglePlayPause() must not start playback after logout", service.uiState.value.isPlaying)

        service.next()
        assertNull("next() must not skip or load track after logout", service.uiState.value.currentTrack)

        service.previous()
        assertNull("previous() must not skip or load track after logout", service.uiState.value.currentTrack)
    }

    @Test
    fun logout_duringPausedState_clearsQueue_andResetsState() {
        val service = PlaybackService(context)
        service.playTrack(sampleTrack1, listOf(sampleTrack1, sampleTrack2))
        service.pause()

        assertEquals(PlayerStatus.PAUSED, service.uiState.value.status)
        assertFalse(service.uiState.value.isPlaying)

        // Logout while paused
        service.stopAndClearPlayback()

        val state = service.uiState.value
        assertNull(state.currentTrack)
        assertFalse(state.isPlaying)
        assertEquals(PlayerStatus.IDLE, state.status)
        assertTrue(state.queue.isEmpty())

        // Resumption attempt must be safely rejected
        service.resume()
        assertFalse(service.uiState.value.isPlaying)
        assertNull(service.uiState.value.currentTrack)
    }

    @Test
    fun logout_duringLoadingState_cancelsResolution_andResetsState() {
        val service = PlaybackService(context)
        service.playTrack(sampleTrack1, listOf(sampleTrack1))

        // Immediately logout while state is in LOADING / stream resolution
        service.stopAndClearPlayback()

        val state = service.uiState.value
        assertNull("Track must be cleared", state.currentTrack)
        assertFalse("isPlaying must be false", state.isPlaying)
        assertEquals(PlayerStatus.IDLE, state.status)
        assertTrue("Queue must be empty", state.queue.isEmpty())
    }

    @Test
    fun logout_duringStoppedOrIdleState_isSafeAndRepeatable() {
        val service = PlaybackService(context)
        assertEquals(PlayerStatus.IDLE, service.uiState.value.status)

        // Calling stopAndClearPlayback multiple times must be idempotent and safe
        service.stopAndClearPlayback()
        service.stopAndClearPlayback()
        service.stopAndClearPlayback()

        val state = service.uiState.value
        assertNull(state.currentTrack)
        assertFalse(state.isPlaying)
        assertEquals(PlayerStatus.IDLE, state.status)
        assertTrue(state.queue.isEmpty())
    }

    @Test
    fun logout_mediaSessionAndNotificationControls_cannotRestartPlayback() = runTest(testDispatcher) {
        val serviceController = Robolectric.buildService(MusicPlayerService::class.java).create().startCommand(0, 0)
        val musicPlayerService = serviceController.get()
        val playbackService = PlaybackService(context)
        musicPlayerService.setPlaybackService(playbackService)
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        // User starts playback
        playbackService.playTrack(sampleTrack1, listOf(sampleTrack1))
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val mediaSession = musicPlayerService.getMediaSession()
        assertTrue("MediaSession should be active during playback", mediaSession.isActive)

        // Logout occurs
        playbackService.stopAndClearPlayback()
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        // Verify MediaSession is deactivated and controls are disabled
        assertFalse("MediaSession must be deactivated after logout", mediaSession.isActive)
        val playbackState = mediaSession.controller.playbackState
        assertEquals(PlaybackStateCompat.STATE_NONE, playbackState?.state)
        assertEquals(0L, playbackState?.actions ?: -1L)

        // Attempt playback resumption via MediaSession callbacks
        mediaSession.controller.transportControls.play()
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        assertFalse("Playback must not restart via MediaSession play", playbackService.uiState.value.isPlaying)
        assertNull(playbackService.uiState.value.currentTrack)

        // Attempt playback resumption via MediaButtonReceiver intent
        val playIntent = Intent("com.example.ACTION_PLAY")
        musicPlayerService.onStartCommand(playIntent, 0, 1)
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        assertFalse("Playback must not restart via ACTION_PLAY intent", playbackService.uiState.value.isPlaying)
        assertNull(playbackService.uiState.value.currentTrack)

        serviceController.destroy()
    }

    @Test
    fun newLogin_afterLogout_startsWithPlaybackStopped_andEmptyQueue() {
        val service = PlaybackService(context)

        // 1. Alice is logged in and playing a queue
        service.playTrack(sampleTrack1, listOf(sampleTrack1, sampleTrack2))
        assertEquals(sampleTrack1.id, service.uiState.value.currentTrack?.id)
        assertTrue(service.uiState.value.isPlaying)

        // 2. Alice logs out -> complete playback shutdown
        service.stopAndClearPlayback()
        assertEquals(PlayerStatus.IDLE, service.uiState.value.status)
        assertTrue(service.uiState.value.queue.isEmpty())

        // 3. Attempting to restore queue after logout must find nothing
        var tracksProviderCalled = false
        service.restoreSavedQueue {
            tracksProviderCalled = true
            listOf(sampleTrack1, sampleTrack2)
        }
        assertFalse("restoreSavedQueue must not invoke provider when prefs were cleared", tracksProviderCalled)
        assertTrue("Queue must remain empty", service.uiState.value.queue.isEmpty())
        assertNull("Current track must remain null", service.uiState.value.currentTrack)

        // 4. Bob logs in -> starts completely stopped with empty queue
        val bobState = service.uiState.value
        assertNull(bobState.currentTrack)
        assertFalse(bobState.isPlaying)
        assertEquals(PlayerStatus.IDLE, bobState.status)
        assertTrue(bobState.queue.isEmpty())

        // 5. Playback starts only when Bob explicitly selects a song
        service.playTrack(sampleTrack2, listOf(sampleTrack2))
        assertEquals(sampleTrack2.id, service.uiState.value.currentTrack?.id)
        assertTrue(service.uiState.value.isPlaying)
    }

    @Test
    fun logout_triggersFromAuthViewModelSessionExpiration_andSignOut() = runTest(testDispatcher) {
        val playbackService = PlaybackService(context)
        val authViewModel = AuthViewModel(playbackService = playbackService)
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        // Start playback
        playbackService.playTrack(sampleTrack1, listOf(sampleTrack1))
        assertTrue(playbackService.uiState.value.isPlaying)

        // User signs out via AuthViewModel
        authViewModel.signOut()
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        // Playback must be completely shut down
        val state = playbackService.uiState.value
        assertNull("Track must be null after authViewModel.signOut()", state.currentTrack)
        assertFalse("isPlaying must be false after authViewModel.signOut()", state.isPlaying)
        assertTrue("Queue must be empty after authViewModel.signOut()", state.queue.isEmpty())
        assertEquals(PlayerStatus.IDLE, state.status)
    }

    @Test
    fun mainViewModel_setCurrentUserIdEmpty_triggersPlaybackShutdown() = runTest(testDispatcher) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repository = MusicRepository(db.musicDao())
        val playbackService = PlaybackService(context)
        val mainViewModel = MainViewModel(repository, playbackService)

        // Authenticated user Alice plays track
        mainViewModel.setCurrentUserId("usr_alice")
        playbackService.playTrack(sampleTrack1, listOf(sampleTrack1))
        assertTrue(playbackService.uiState.value.isPlaying)

        // Session invalidated / signed out
        mainViewModel.setCurrentUserId("")

        val state = playbackService.uiState.value
        assertNull(state.currentTrack)
        assertFalse(state.isPlaying)
        assertTrue(state.queue.isEmpty())

        db.close()
    }

    @Test
    fun savedDataPreserved_likesPlaylistsHistoryUnchangedOnLogout() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.musicDao()
        val repository = MusicRepository(dao)
        val playbackService = PlaybackService(context)

        // 1. User Alice adds likes, playlists, and history
        repository.setCurrentUserId("usr_alice")
        dao.insertLikedTrack(LikedTrackEntity(userId = "usr_alice", trackId = "trk_alice_fav"))
        dao.insertPlaylist(PlaylistEntity(id = "pl_alice", userId = "usr_alice", name = "Alice Jams"))
        dao.insertRecentlyPlayed(RecentlyPlayedEntity(userId = "usr_alice", trackId = "trk_alice_fav", playedAt = 12345L))

        // Alice plays a track
        playbackService.playTrack(sampleTrack1, listOf(sampleTrack1))
        assertTrue(playbackService.uiState.value.isPlaying)

        // 2. Alice logs out
        playbackService.stopAndClearPlayback()
        repository.setCurrentUserId(null)

        // Playback is stopped and queue is empty
        assertFalse(playbackService.uiState.value.isPlaying)
        assertNull(playbackService.uiState.value.currentTrack)

        // 3. Database records for Alice remain 100% intact
        val aliceLikes = dao.getLikedTracks("usr_alice").first()
        val alicePlaylists = dao.getAllPlaylists("usr_alice").first()
        val aliceRecent = dao.getRecentlyPlayed("usr_alice").first()

        assertEquals(1, aliceLikes.size)
        assertEquals("trk_alice_fav", aliceLikes[0].trackId)

        assertEquals(1, alicePlaylists.size)
        assertEquals("Alice Jams", alicePlaylists[0].name)

        assertEquals(1, aliceRecent.size)
        assertEquals("trk_alice_fav", aliceRecent[0].trackId)

        db.close()
    }
}
