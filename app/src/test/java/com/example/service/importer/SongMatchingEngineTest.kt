package com.example.service.importer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.importer.matcher.SongMatchingEngine
import com.example.service.importer.model.ImportTrackItem
import com.example.service.importer.model.MatchConfidence
import com.example.service.importer.model.MatchStatus
import com.example.service.integrations.ytmusic.YtAlbum
import com.example.service.integrations.ytmusic.YtArtist
import com.example.service.integrations.ytmusic.YtHomeShelf
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtPlaylist
import com.example.service.integrations.ytmusic.YtSearchResult
import com.example.service.integrations.ytmusic.YtTrack
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongMatchingEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = MusicRepository(db.musicDao())
        repository.registerTracks(listOf(
            Track(id = "trk_kesariya", title = "Kesariya", artist = "Arijit Singh", artwork = "", durationSeconds = 268),
            Track(id = "trk_blinding_lights", title = "Blinding Lights", artist = "The Weeknd", artwork = "", durationSeconds = 200),
            Track(id = "trk_believer", title = "Believer", artist = "Imagine Dragons", artwork = "", durationSeconds = 204),
            Track(id = "trk_lover", title = "Lover", artist = "Taylor Swift", artwork = "", durationSeconds = 221)
        ))
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun matchTrack_exactMatchInLocalCatalog_returnsHighConfidenceMatched() = runTest {
        val engine = SongMatchingEngine(repository)

        // "Kesariya" by "Arijit Singh" is in repository.catalogTracks
        val imported = ImportTrackItem(
            sourceTrackId = "sp_kesariya",
            title = "Kesariya",
            artists = listOf("Arijit Singh"),
            durationMs = 268000L
        )

        val result = engine.matchTrack(imported)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals(MatchConfidence.HIGH, result.confidence)
        assertTrue(result.confidenceScore >= 0.90f)
        assertNotNull(result.matchedTrack)
        assertEquals("Kesariya", result.matchedTrack?.title)
        assertEquals("Arijit Singh", result.matchedTrack?.artist)
    }

    @Test
    fun matchTrack_normalizedNoiseInTitle_matchesCorrectCatalogTrack() = runTest {
        val engine = SongMatchingEngine(repository)

        // Title has YouTube / Spotify noise "(Official Music Video)"
        val imported = ImportTrackItem(
            sourceTrackId = "sp_blinding_lights",
            title = "Blinding Lights (Official Music Video)",
            artists = listOf("The Weeknd"),
            durationMs = 200000L
        )

        val result = engine.matchTrack(imported)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals(MatchConfidence.HIGH, result.confidence)
        assertNotNull(result.matchedTrack)
        assertEquals("Blinding Lights", result.matchedTrack?.title)
    }

    @Test
    fun matchTrack_multipleCloseCandidates_classifiesAsMultipleMatches() = runTest {
        // Register two similar tracks in catalog
        val trackA = Track(
            id = "trk_custom_1",
            title = "Shape of You",
            artist = "Ed Sheeran",
            artwork = "https://example.com/a.jpg",
            durationSeconds = 233
        )
        val trackB = Track(
            id = "trk_custom_2",
            title = "Shape of You",
            artist = "Ed Sheeran (Acoustic)",
            artwork = "https://example.com/b.jpg",
            durationSeconds = 230
        )
        repository.registerTracks(listOf(trackA, trackB))

        val engine = SongMatchingEngine(repository)

        val imported = ImportTrackItem(
            sourceTrackId = "sp_shape",
            title = "Shape of You",
            artists = listOf("Ed Sheeran"),
            durationMs = 233000L
        )

        val result = engine.matchTrack(imported)
        // Should detect multiple close matches or matched with candidates
        assertTrue(result.candidates.size >= 2)
        assertNotNull(result.matchedTrack)
    }

    @Test
    fun matchTrack_completelyUnrelatedSong_returnsNotFoundAndNeverAutoMatches() = runTest {
        val fakeYtMusicService = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.success(YtArtist(id = "", name = ""))
            override suspend fun getAlbum(browseId: String) = Result.success(YtAlbum(id = "", title = "", artist = ""))
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.success(YtPlaylist(id = "", title = ""))
        }

        val engine = SongMatchingEngine(repository, fakeYtMusicService)

        val imported = ImportTrackItem(
            sourceTrackId = "sp_nonexistent",
            title = "Zzzzzz Xxxxxx Yyyyyy Unknown Unreleased 9999",
            artists = listOf("Nonexistent Alien Band 888"),
            durationMs = 120000L
        )

        val result = engine.matchTrack(imported)
        assertEquals(MatchStatus.NOT_FOUND, result.status)
        assertNull(result.matchedTrack)
        assertTrue("Confidence score must be low (<0.50)", result.confidenceScore < 0.50f)
    }
}

