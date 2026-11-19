package com.example.service.importer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistImporterServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository
    private lateinit var service: PlaylistImportService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = MusicRepository(db.musicDao())
        service = PlaylistImportService(repository)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun commitImport_createsPlaylistAndPreservesOrderingInRoomDb() = runTest {
        val track1 = Track(
            id = "trk_import_1",
            title = "Imported Track 1",
            artist = "Artist A",
            artwork = "https://example.com/1.jpg",
            durationSeconds = 180
        )
        val track2 = Track(
            id = "trk_import_2",
            title = "Imported Track 2",
            artist = "Artist B",
            artwork = "https://example.com/2.jpg",
            durationSeconds = 210
        )

        val result = service.commitImport(
            playlistName = "Roadtrip Anthems",
            playlistDescription = "Imported from Spotify public playlist",
            artworkUrl = "https://example.com/cover.jpg",
            matchedTracks = listOf(track1, track2),
            userId = "usr_tester"
        )

        assertTrue(result.isSuccess)
        val playlistId = result.getOrThrow()
        assertTrue(playlistId.startsWith("pl_"))

        // Verify playlist metadata in Room
        val playlists = repository.getPlaylists("usr_tester").first()
        assertEquals(1, playlists.size)
        val created = playlists.first()
        assertEquals("Roadtrip Anthems", created.name)
        assertEquals("Imported from Spotify public playlist", created.description)
        assertEquals("https://example.com/cover.jpg", created.artwork)

        // Verify playlist tracks in Room DB
        val playlistTracks = repository.getPlaylistTracks(playlistId, "usr_tester").first()
        assertEquals(2, playlistTracks.size)
        assertEquals("trk_import_1", playlistTracks[0].id)
        assertEquals("trk_import_2", playlistTracks[1].id)
    }

    @Test
    fun commitImport_deduplicatesTracksSafely() = runTest {
        val track1 = Track(
            id = "trk_duplicate",
            title = "Duplicate Track",
            artist = "Artist Dup",
            artwork = "https://example.com/dup.jpg",
            durationSeconds = 190
        )

        // Same track appears multiple times
        val result = service.commitImport(
            playlistName = "Dedup Test",
            playlistDescription = "Testing duplicates",
            artworkUrl = "",
            matchedTracks = listOf(track1, track1, track1),
            userId = "usr_tester"
        )

        assertTrue(result.isSuccess)
        val playlistId = result.getOrThrow()

        // Should contain 1 instance and not crash with unique constraint violation
        val playlistTracks = repository.getPlaylistTracks(playlistId, "usr_tester").first()
        assertEquals(1, playlistTracks.size)
        assertEquals("trk_duplicate", playlistTracks[0].id)
    }

    @Test
    fun commitImport_emptyTrackList_failsValidation() = runTest {
        val result = service.commitImport(
            playlistName = "Empty List",
            playlistDescription = "Nothing",
            artworkUrl = "",
            matchedTracks = emptyList(),
            userId = "usr_tester"
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No matched songs") == true)
    }
}

