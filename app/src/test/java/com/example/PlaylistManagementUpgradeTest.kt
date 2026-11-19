package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistManagementUpgradeTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: MusicRepository

    private val track1 = Track(
        id = "trk_neon_pulse",
        title = "Neon Pulse",
        artist = "SynthWave AI",
        artistId = "art_synth",
        album = "Future Beats",
        albumId = "alb_future",
        artwork = "https://example.com/pulse.jpg",
        durationSeconds = 210,
        audioUrl = "https://example.com/pulse.mp3"
    )

    private val track2 = Track(
        id = "trk_velvet_echo",
        title = "Velvet Echo",
        artist = "Acoustic Soul",
        artistId = "art_acoustic",
        album = "Echoes",
        albumId = "alb_echoes",
        artwork = "https://example.com/echo.jpg",
        durationSeconds = 180,
        audioUrl = "https://example.com/echo.mp3"
    )

    private val track3 = Track(
        id = "trk_solar_flare",
        title = "Solar Flare",
        artist = "Cosmic Vibes",
        artistId = "art_cosmic",
        album = "Space Odyssey",
        albumId = "alb_space",
        artwork = "https://example.com/flare.jpg",
        durationSeconds = 240,
        audioUrl = "https://example.com/flare.mp3"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MusicRepository(database.musicDao())
        repository.setCurrentUserId("user_alpha")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun playlist_renameAndEditDetails_updatesAndPersists() = runTest {
        val playlistId = repository.createPlaylist("Old Title", "Old Desc", "https://art1.jpg")
        advanceUntilIdle()

        var playlists = repository.playlists.first()
        assertEquals(1, playlists.size)
        assertEquals("Old Title", playlists[0].name)
        assertEquals("Old Desc", playlists[0].description)
        assertEquals("https://art1.jpg", playlists[0].artwork)

        // Rename only
        repository.renamePlaylist(playlistId, "New Title")
        advanceUntilIdle()
        playlists = repository.playlists.first()
        assertEquals("New Title", playlists[0].name)

        // Update full details: name, description, custom artwork
        repository.updatePlaylist(playlistId, "Vibrant Mix", "Upgraded Description", "https://art2.jpg")
        advanceUntilIdle()
        playlists = repository.playlists.first()
        assertEquals("Vibrant Mix", playlists[0].name)
        assertEquals("Upgraded Description", playlists[0].description)
        assertEquals("https://art2.jpg", playlists[0].artwork)
    }

    @Test
    fun playlist_deletePlaylist_removesPlaylistAndItsTracksOnly() = runTest {
        val pl1 = repository.createPlaylist("Playlist 1")
        val pl2 = repository.createPlaylist("Playlist 2")
        repository.addTrackToPlaylist(pl1, track1)
        repository.addTrackToPlaylist(pl2, track1)
        advanceUntilIdle()

        // Delete playlist 1
        repository.deletePlaylist(pl1)
        advanceUntilIdle()

        val remainingPlaylists = repository.playlists.first()
        assertEquals(1, remainingPlaylists.size)
        assertEquals(pl2, remainingPlaylists[0].id)

        // pl2 still contains track1
        val pl2Tracks = repository.getPlaylistTracks(pl2).first()
        assertEquals(1, pl2Tracks.size)
        assertEquals(track1.id, pl2Tracks[0].id)
    }

    @Test
    fun removeTrack_neverAffectsOtherPlaylistsLikedSongsOrHistory() = runTest {
        val plA = repository.createPlaylist("Playlist A")
        val plB = repository.createPlaylist("Playlist B")

        repository.addTrackToPlaylist(plA, track1)
        repository.addTrackToPlaylist(plA, track2)
        repository.addTrackToPlaylist(plB, track1)

        // User also likes track1
        repository.toggleLike(track1.id)
        advanceUntilIdle()

        // Verify pre-conditions
        assertTrue("track1 must be liked", repository.likedTrackIds.first().contains(track1.id))
        assertEquals(2, repository.getPlaylistTracks(plA).first().size)
        assertEquals(1, repository.getPlaylistTracks(plB).first().size)

        // Remove track1 from Playlist A
        repository.removeTrackFromPlaylist(plA, track1.id)
        advanceUntilIdle()

        // 1. Removed from plA
        val plATracks = repository.getPlaylistTracks(plA).first()
        assertEquals(1, plATracks.size)
        assertEquals(track2.id, plATracks[0].id)

        // 2. Still in plB
        val plBTracks = repository.getPlaylistTracks(plB).first()
        assertEquals(1, plBTracks.size)
        assertEquals(track1.id, plBTracks[0].id)

        // 3. Still in Liked Songs
        val liked = repository.likedTrackIds.first()
        assertTrue("track1 must still be liked after removal from playlist A", liked.contains(track1.id))
    }

    @Test
    fun reorderTracks_persistsCorrectOrderAcrossAppRestart() = runTest {
        val dbFile = context.getDatabasePath("test_reorder_persistence.db")
        if (dbFile.exists()) dbFile.delete()

        val db1 = Room.databaseBuilder(context, AppDatabase::class.java, "test_reorder_persistence.db")
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        val repo1 = MusicRepository(db1.musicDao())
        repo1.setCurrentUserId("user_alpha")

        val plId = repo1.createPlaylist("Workout Mix")
        repo1.addTrackToPlaylist(plId, track1) // pos 0
        repo1.addTrackToPlaylist(plId, track2) // pos 1
        repo1.addTrackToPlaylist(plId, track3) // pos 2
        advanceUntilIdle()

        val initialOrder = repo1.getPlaylistTracks(plId).first().map { it.id }
        assertEquals(listOf(track1.id, track2.id, track3.id), initialOrder)

        // Reorder: Move track3 to first, track1 to middle, track2 to last
        val newOrder = listOf(track3.id, track1.id, track2.id)
        repo1.reorderPlaylistTracks(plId, newOrder)
        advanceUntilIdle()

        val reorderedSession1 = repo1.getPlaylistTracks(plId).first().map { it.id }
        assertEquals(newOrder, reorderedSession1)

        db1.close()

        // Reopen database to simulate app restart
        val db2 = Room.databaseBuilder(context, AppDatabase::class.java, "test_reorder_persistence.db")
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        val repo2 = MusicRepository(db2.musicDao())
        repo2.setCurrentUserId("user_alpha")
        advanceUntilIdle()

        val reorderedSession2 = repo2.getPlaylistTracks(plId).first().map { it.id }
        assertEquals("Track order must persist across app restart", newOrder, reorderedSession2)

        db2.close()
        dbFile.delete()
    }

    @Test
    fun multiSelect_removeMultipleTracksAtOnce() = runTest {
        val pl = repository.createPlaylist("Focus Beats")
        repository.addTrackToPlaylist(pl, track1)
        repository.addTrackToPlaylist(pl, track2)
        repository.addTrackToPlaylist(pl, track3)
        advanceUntilIdle()

        assertEquals(3, repository.getPlaylistTracks(pl).first().size)

        // Remove track1 and track3 together
        repository.removeTracksFromPlaylist(pl, listOf(track1.id, track3.id))
        advanceUntilIdle()

        val remaining = repository.getPlaylistTracks(pl).first()
        assertEquals(1, remaining.size)
        assertEquals(track2.id, remaining[0].id)
    }

    @Test
    fun undo_restoresTrackToExactPreviousPosition() = runTest {
        val pl = repository.createPlaylist("Chill Mix")
        repository.addTrackToPlaylist(pl, track1) // pos 0
        repository.addTrackToPlaylist(pl, track2) // pos 1
        repository.addTrackToPlaylist(pl, track3) // pos 2
        advanceUntilIdle()

        val initialTracks = repository.getPlaylistTracks(pl).first().map { it.id }
        assertEquals(listOf(track1.id, track2.id, track3.id), initialTracks)

        // Capture position and remove track2 (at index 1)
        val pos = repository.getPlaylistTrackPosition(pl, track2.id)
        assertEquals(1, pos)

        repository.removeTrackFromPlaylist(pl, track2.id)
        advanceUntilIdle()

        val afterRemove = repository.getPlaylistTracks(pl).first().map { it.id }
        assertEquals(listOf(track1.id, track3.id), afterRemove)

        // Restore track2 to position 1
        val restored = repository.restoreTrackToPlaylist(pl, track2, pos)
        assertTrue("Restore must succeed", restored)
        advanceUntilIdle()

        val afterUndo = repository.getPlaylistTracks(pl).first().map { it.id }
        assertEquals("Track must be restored to its exact previous position", listOf(track1.id, track2.id, track3.id), afterUndo)
    }

    @Test
    fun duplicateDetection_rejectsDuplicateTrackAddition() = runTest {
        val pl = repository.createPlaylist("Party Hits")
        val firstAdd = repository.addTrackToPlaylist(pl, track1)
        assertTrue("First addition succeeds", firstAdd)

        val duplicateAdd = repository.addTrackToPlaylist(pl, track1)
        assertFalse("Duplicate track addition must be rejected", duplicateAdd)

        val tracks = repository.getPlaylistTracks(pl).first()
        assertEquals(1, tracks.size)
        assertEquals(track1.id, tracks[0].id)
    }

    @Test
    fun userIsolation_playlistsAndOperationsNeverLeakBetweenUsers() = runTest {
        val plAlpha = repository.createPlaylist("Alpha Playlist", userId = "user_alpha")
        repository.addTrackToPlaylist(plAlpha, track1, userId = "user_alpha")

        // Switch to user_beta
        repository.setCurrentUserId("user_beta")
        advanceUntilIdle()

        val betaPlaylists = repository.playlists.first()
        assertEquals("User beta must not see user alpha's playlists", 0, betaPlaylists.size)

        val plBeta = repository.createPlaylist("Beta Playlist", userId = "user_beta")
        repository.addTrackToPlaylist(plBeta, track2, userId = "user_beta")
        advanceUntilIdle()

        assertEquals(1, repository.playlists.first().size)
        assertEquals("Beta Playlist", repository.playlists.first()[0].name)

        // Switch back to user_alpha
        repository.setCurrentUserId("user_alpha")
        advanceUntilIdle()

        val alphaPlaylists = repository.playlists.first()
        assertEquals(1, alphaPlaylists.size)
        assertEquals("Alpha Playlist", alphaPlaylists[0].name)
        assertEquals(listOf(track1.id), alphaPlaylists[0].trackIds)
    }

    @Test
    fun mainViewModel_playlistManagement_renameDeleteAndUndoFlow() = runTest(testDispatcher) {
        val playbackService = PlaybackService()
        val viewModel = MainViewModel(
            repository = repository,
            playbackService = playbackService
        )
        advanceUntilIdle()

        // 1. Create playlist
        viewModel.createPlaylist("Gym Tunes", "High Energy", "https://example.com/gym.jpg")
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        val pl = repository.playlists.first { it.isNotEmpty() }.first()
        assertEquals("Gym Tunes", pl.name)

        // 2. Add tracks
        repository.addTrackToPlaylist(pl.id, track1)
        repository.addTrackToPlaylist(pl.id, track2)
        advanceUntilIdle()

        val initialTracks = repository.getPlaylistTracks(pl.id).first()
        assertEquals(2, initialTracks.size)

        // 3. Play Next and Add to Queue
        viewModel.playNext(track3)
        viewModel.addToQueue(track3)
        assertEquals("Added to queue: Solar Flare", viewModel.userMessage.value)

        // 4. Remove track with undo state
        viewModel.removeTrackFromPlaylist(pl.id, track1)
        val undoState = viewModel.undoRemovalState.value
        assertNotNull("Undo state must be populated", undoState)
        assertEquals(track1.id, undoState?.track?.id)
        assertEquals(pl.id, undoState?.playlistId)

        // 5. Execute Undo
        viewModel.undoRemoveTrack()
        assertNull("Undo state must be cleared after restore", viewModel.undoRemovalState.value)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val restoredTracks = repository.getPlaylistTracks(pl.id).first { it.size == 2 }
        assertEquals(2, restoredTracks.size)
        assertTrue(restoredTracks.any { it.id == track1.id })

        // 6. Test Undo expiration after 5 seconds
        viewModel.removeTrackFromPlaylist(pl.id, track1)
        assertNotNull("Undo state must be populated before expiration", viewModel.undoRemovalState.value)
        advanceUntilIdle() // Fast forwards past 5000ms timeout
        ShadowLooper.idleMainLooper()
        assertNull("Undo state must expire after 5 seconds", viewModel.undoRemovalState.value)

        // 7. Delete playlist
        viewModel.deletePlaylist(pl.id)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        val remaining = repository.playlists.first { list -> list.none { it.id == pl.id } }
        assertTrue("Playlist must be deleted", remaining.none { it.id == pl.id })
    }
}
