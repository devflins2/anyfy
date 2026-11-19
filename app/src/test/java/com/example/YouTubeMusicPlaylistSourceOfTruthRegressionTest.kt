package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Playlist
import com.example.model.Track
import com.example.service.integrations.ytmusic.YtHomeShelf
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtPlaylist
import com.example.service.integrations.ytmusic.YtSearchResult
import com.example.service.integrations.ytmusic.YtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeMusicPlaylistSourceOfTruthRegressionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val sampleTrack1 = Track(
        id = "yt_alfaaz_hamza_01",
        title = "Alfaaz",
        artist = "Hamza Malik & Zain Zohaib",
        artistId = "art_hamza_malik",
        album = "Alfaaz - Single",
        albumId = "alb_alfaaz_01",
        artwork = "https://i.ytimg.com/vi/alfaaz_hamza_01/hqdefault.jpg",
        durationSeconds = 240,
        audioUrl = null
    )

    private val sampleTrack2 = Track(
        id = "yt_akhiyaan_gulaab_02",
        title = "Akhiyaan Gulaab",
        artist = "Mitraz",
        artistId = "art_mitraz",
        album = "Akhiyaan Gulaab",
        albumId = "alb_akhiyaan",
        artwork = "https://i.ytimg.com/vi/akhiyaan_gulaab_02/hqdefault.jpg",
        durationSeconds = 195,
        audioUrl = null
    )

    private class FakeYtMusicService : YtMusicService {
        var isAuthenticated = true
        var shouldFailNetwork = false

        val remotePlaylists = mutableMapOf<String, YtPlaylist>()
        val createPlaylistCalls = mutableListOf<Triple<String, String, String>>() // title, desc, privacy
        val addPlaylistItemCalls = mutableListOf<Pair<String, String>>() // plId, videoId
        val removePlaylistItemCalls = mutableListOf<Pair<String, String>>() // plId, videoId
        val deletePlaylistCalls = mutableListOf<String>()
        val invalidatedCacheCalls = mutableListOf<String?>()

        override fun isUserAuthenticated(): Boolean = isAuthenticated

        override suspend fun createPlaylist(title: String, description: String, privacyStatus: String): Result<String> {
            if (!isAuthenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required"))
            if (shouldFailNetwork) return Result.failure(IOException("Network error communicating with YouTube Music InnerTube"))
            createPlaylistCalls.add(Triple(title, description, privacyStatus))
            val remoteId = "PL_yt_${title.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
            val ytPl = YtPlaylist(
                id = remoteId,
                title = title,
                author = "Authenticated User",
                description = description,
                artwork = "https://i.ytimg.com/vi/default/hqdefault.jpg"
            )
            remotePlaylists[remoteId] = ytPl
            return Result.success(remoteId)
        }

        override suspend fun editPlaylist(playlistId: String, newTitle: String?, newDescription: String?): Result<Boolean> {
            if (!isAuthenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required"))
            if (shouldFailNetwork) return Result.failure(IOException("Network error"))
            val existing = remotePlaylists[playlistId] ?: return Result.failure(IOException("Playlist not found on remote"))
            remotePlaylists[playlistId] = existing.copy(
                title = newTitle ?: existing.title,
                description = newDescription ?: existing.description
            )
            return Result.success(true)
        }

        override suspend fun addPlaylistItem(playlistId: String, videoId: String): Result<Boolean> {
            if (!isAuthenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required"))
            if (shouldFailNetwork) return Result.failure(IOException("Network error"))
            addPlaylistItemCalls.add(Pair(playlistId, videoId))
            val existing = remotePlaylists[playlistId] ?: return Result.failure(IOException("Playlist not found on remote"))
            val cleanVid = videoId.removePrefix("yt_").trim()
            val newTrack = YtTrack(
                id = cleanVid,
                title = "Song $cleanVid",
                artist = "Artist $cleanVid",
                artwork = "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg"
            )
            remotePlaylists[playlistId] = existing.copy(
                tracks = existing.tracks + newTrack
            )
            return Result.success(true)
        }

        override suspend fun addPlaylistItems(playlistId: String, videoIds: List<String>): Result<Boolean> {
            if (!isAuthenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required"))
            if (shouldFailNetwork) return Result.failure(IOException("Network error"))
            for (vid in videoIds) {
                addPlaylistItem(playlistId, vid)
            }
            return Result.success(true)
        }

        override suspend fun removePlaylistItem(playlistId: String, videoId: String, setVideoId: String?): Result<Boolean> {
            if (!isAuthenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required"))
            if (shouldFailNetwork) return Result.failure(IOException("Network error"))
            removePlaylistItemCalls.add(Pair(playlistId, videoId))
            val existing = remotePlaylists[playlistId] ?: return Result.failure(IOException("Playlist not found on remote"))
            val cleanVid = videoId.removePrefix("yt_").trim()
            remotePlaylists[playlistId] = existing.copy(
                tracks = existing.tracks.filter { it.id != cleanVid }
            )
            return Result.success(true)
        }

        override suspend fun removePlaylistItems(playlistId: String, videoIds: List<String>): Result<Boolean> {
            if (!isAuthenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required"))
            if (shouldFailNetwork) return Result.failure(IOException("Network error"))
            for (vid in videoIds) {
                removePlaylistItem(playlistId, vid)
            }
            return Result.success(true)
        }

        override suspend fun deletePlaylist(playlistId: String): Result<Boolean> {
            if (!isAuthenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required"))
            if (shouldFailNetwork) return Result.failure(IOException("Network error"))
            deletePlaylistCalls.add(playlistId)
            remotePlaylists.remove(playlistId)
            return Result.success(true)
        }

        override suspend fun getUserPlaylists(continuationToken: String?): Result<List<YtPlaylist>> {
            if (!isAuthenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required"))
            if (shouldFailNetwork) return Result.failure(IOException("Network error"))
            return Result.success(remotePlaylists.values.toList())
        }

        override suspend fun getPlaylist(playlistId: String, continuationToken: String?): Result<YtPlaylist> {
            if (shouldFailNetwork) return Result.failure(IOException("Network error"))
            val pl = remotePlaylists[playlistId] ?: remotePlaylists[playlistId.removePrefix("VL")]
            return if (pl != null) Result.success(pl) else Result.failure(IOException("Playlist not found"))
        }

        override fun invalidatePlaylistCache(playlistId: String?) {
            invalidatedCacheCalls.add(playlistId)
        }

        override suspend fun getHomeShelves(): Result<List<YtHomeShelf>> = Result.success(emptyList())
        override suspend fun searchTracks(query: String): Result<List<YtTrack>> = Result.success(emptyList())
        override suspend fun search(query: String): Result<YtSearchResult> = Result.success(YtSearchResult())
        override suspend fun getArtist(browseId: String): Result<com.example.service.integrations.ytmusic.YtArtist> =
            Result.success(com.example.service.integrations.ytmusic.YtArtist(id = browseId, name = "Artist"))
        override suspend fun getAlbum(browseId: String): Result<com.example.service.integrations.ytmusic.YtAlbum> =
            Result.success(com.example.service.integrations.ytmusic.YtAlbum(id = browseId, title = "Album", artist = "Artist"))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun test01_createPlaylist_createsOnYouTubeMusicAndCapturesRemoteId() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        // 1. Create playlist
        val createResult = repository.createPlaylistResult("Anyfy Hits 2026", "My favorite tracks", privacyStatus = "PRIVATE")
        assertTrue("Playlist creation must succeed", createResult.isSuccess)
        val remoteId = createResult.getOrThrow()
        assertTrue("Playlist ID must start with PL_yt_", remoteId.startsWith("PL_yt_"))

        // 2. Verify YouTube Music InnerTube received the call
        assertEquals(1, fakeService.createPlaylistCalls.size)
        assertEquals("Anyfy Hits 2026", fakeService.createPlaylistCalls[0].first)
        assertEquals("My favorite tracks", fakeService.createPlaylistCalls[0].second)
        assertEquals("PRIVATE", fakeService.createPlaylistCalls[0].third)

        // 3. Verify local read-through cache has the remote playlist ID
        val localPlaylists = repository.playlists.first()
        assertEquals(1, localPlaylists.size)
        assertEquals(remoteId, localPlaylists[0].id)
        assertEquals("Anyfy Hits 2026", localPlaylists[0].name)
        db.close()
    }

    @Test
    fun test02_createPlaylist_failsHonestlyWhenNotAuthenticated() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService().apply { isAuthenticated = false }
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        val result = repository.createPlaylistResult("Unauthenticated Playlist")
        assertTrue("Must fail when not authenticated", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("YouTube Music account authentication required", result.exceptionOrNull()?.message)

        // Verify zero fake local playlists were created in Room
        val localPlaylists = repository.playlists.first()
        assertTrue("No local-only playlists should be created", localPlaylists.isEmpty())
        db.close()
    }

    @Test
    fun test03_addTrackToPlaylist_addsDirectlyToYouTubeMusicViaVideoId() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        val remoteId = repository.createPlaylist("Chill Vibez")

        // Add track to playlist
        val addResult = repository.addTrackToPlaylistResult(remoteId, sampleTrack1)
        assertTrue("Adding track must succeed", addResult.isSuccess)

        // Verify remote YouTube Music service received the genuine video ID
        assertEquals(1, fakeService.addPlaylistItemCalls.size)
        assertEquals(remoteId, fakeService.addPlaylistItemCalls[0].first)
        assertEquals(sampleTrack1.id, fakeService.addPlaylistItemCalls[0].second)

        // Verify local read-through cache reflects the added track
        val playlistTracks = repository.getPlaylistTracks(remoteId, "usr_yt_test").first()
        assertEquals(1, playlistTracks.size)
        assertEquals("Alfaaz", playlistTracks[0].title)
        db.close()
    }

    @Test
    fun test04_addTrackToPlaylist_rejectsDuplicatesHonoringIdempotency() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        val remoteId = repository.createPlaylist("Favorites")
        assertTrue(repository.addTrackToPlaylist(remoteId, sampleTrack1))

        // Second attempt with same track
        val duplicateResult = repository.addTrackToPlaylistResult(remoteId, sampleTrack1)
        assertTrue("Duplicate addition must fail", duplicateResult.isFailure)
        assertEquals("Track already in playlist", duplicateResult.exceptionOrNull()?.message)

        // Verify remote was called only once
        assertEquals(1, fakeService.addPlaylistItemCalls.size)
        db.close()
    }

    @Test
    fun test05_addTracksToPlaylist_batchAddsDirectlyToYouTubeMusic() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        val remoteId = repository.createPlaylist("Roadtrip Mix")
        val batchResult = repository.addTracksToPlaylistResult(remoteId, listOf(sampleTrack1, sampleTrack2))
        assertTrue("Batch add must succeed", batchResult.isSuccess)

        // Verify remote received both tracks
        assertEquals(2, fakeService.addPlaylistItemCalls.size)

        val tracks = repository.getPlaylistTracks(remoteId, "usr_yt_test").first()
        assertEquals(2, tracks.size)
        assertEquals("Alfaaz", tracks[0].title)
        assertEquals("Akhiyaan Gulaab", tracks[1].title)
        db.close()
    }

    @Test
    fun test06_removeTrackFromPlaylist_removesFromYouTubeMusicAndCache() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        val remoteId = repository.createPlaylist("Workout")
        repository.addTrackToPlaylist(remoteId, sampleTrack1)
        repository.addTrackToPlaylist(remoteId, sampleTrack2)

        // Remove first track
        val removeResult = repository.removeTrackFromPlaylistResult(remoteId, sampleTrack1.id)
        assertTrue("Remove track must succeed", removeResult.isSuccess)

        // Verify remote removal
        assertEquals(1, fakeService.removePlaylistItemCalls.size)
        assertEquals(sampleTrack1.id, fakeService.removePlaylistItemCalls[0].second)

        // Verify local read-through cache
        val remaining = repository.getPlaylistTracks(remoteId, "usr_yt_test").first()
        assertEquals(1, remaining.size)
        assertEquals("Akhiyaan Gulaab", remaining[0].title)
        db.close()
    }

    @Test
    fun test07_deletePlaylist_deletesFromYouTubeMusicAndPurgesLocalCache() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        val remoteId = repository.createPlaylist("Delete Me Later")
        repository.addTrackToPlaylist(remoteId, sampleTrack1)

        val deleteResult = repository.deletePlaylistResult(remoteId)
        assertTrue("Delete playlist must succeed", deleteResult.isSuccess)

        // Verify remote delete call
        assertEquals(1, fakeService.deletePlaylistCalls.size)
        assertEquals(remoteId, fakeService.deletePlaylistCalls[0])

        // Verify local read-through cache is purged
        val playlists = repository.playlists.first()
        assertTrue("Playlist must be purged from local cache", playlists.isEmpty())
        db.close()
    }

    @Test
    fun test08_fetchUserPlaylists_prunesLegacyLocalOnlyPlaylists() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        // Simulate a legacy local-only playlist in Room from older versions (with "pl_local_...")
        db.musicDao().insertPlaylist(
            com.example.data.local.entity.PlaylistEntity(
                id = "pl_local_legacy_1234",
                userId = "usr_yt_test",
                name = "Fake Old Local Playlist"
            )
        )
        assertEquals(1, db.musicDao().getPlaylistsSnapshot("usr_yt_test").size)

        // Create a genuine remote playlist on YouTube Music
        fakeService.remotePlaylists["PL_genuine_remote_555"] = YtPlaylist(
            id = "PL_genuine_remote_555",
            title = "Cloud Playlist On YouTube",
            author = "Authenticated User",
            artwork = "https://i.ytimg.com/vi/cloud/hqdefault.jpg"
        )

        // Fetch user playlists from YouTube Music
        val fetchResult = repository.fetchUserPlaylists("usr_yt_test")
        assertTrue("Fetching playlists must succeed", fetchResult.isSuccess)

        // Verify that the legacy fake local playlist was PRUNED, and only genuine remote playlist exists!
        val playlists = repository.playlists.first()
        assertEquals(1, playlists.size)
        assertEquals("PL_genuine_remote_555", playlists[0].id)
        assertEquals("Cloud Playlist On YouTube", playlists[0].name)

        val snapshot = db.musicDao().getPlaylistsSnapshot("usr_yt_test")
        assertEquals(1, snapshot.size)
        assertEquals("PL_genuine_remote_555", snapshot[0].id)
        db.close()
    }

    @Test
    fun test09_fetchYtPlaylist_loadsRemoteTracksAndPopulatesAllKnownTracks() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        val remoteId = "PL_trending_global_100"
        fakeService.remotePlaylists[remoteId] = YtPlaylist(
            id = remoteId,
            title = "Global Top 100",
            author = "YouTube Music",
            artwork = "https://i.ytimg.com/vi/top100/hqdefault.jpg",
            tracks = listOf(
                YtTrack(id = "track_a", title = "Alfaaz", artist = "Hamza Malik", artwork = "https://i.ytimg.com/vi/track_a/hqdefault.jpg"),
                YtTrack(id = "track_b", title = "Akhiyaan Gulaab", artist = "Mitraz", artwork = "https://i.ytimg.com/vi/track_b/hqdefault.jpg")
            )
        )

        // Fetch remote playlist
        val playlist = repository.fetchYtPlaylist(remoteId)
        assertNotNull("Playlist must be fetched", playlist)
        assertEquals(2, playlist?.trackIds?.size)
        assertEquals(listOf("yt_track_a", "yt_track_b"), playlist?.trackIds)

        // Verify tracks were registered in allKnownTracks StateFlow
        val known = repository.allKnownTracks.value
        assertNotNull(known.find { it.id == "yt_track_a" })
        assertNotNull(known.find { it.id == "yt_track_b" })

        // Verify tracks were cached in Room DB for instant offline display
        val roomTracks = repository.getPlaylistTracks(remoteId, "usr_yt_test").first()
        assertEquals(2, roomTracks.size)
        assertEquals("Alfaaz", roomTracks[0].title)
        assertEquals("Akhiyaan Gulaab", roomTracks[1].title)
        db.close()
    }

    @Test
    fun test10_remoteFailures_doNotCorruptLocalCache() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val fakeService = FakeYtMusicService()
        val repository = MusicRepository(db.musicDao(), ytMusicService = fakeService)
        repository.setCurrentUserId("usr_yt_test")

        val remoteId = repository.createPlaylist("Safe Playlist")
        assertTrue(repository.addTrackToPlaylist(remoteId, sampleTrack1))

        // Simulate network failure on YouTube Music
        fakeService.shouldFailNetwork = true

        val failedAdd = repository.addTrackToPlaylistResult(remoteId, sampleTrack2)
        assertTrue("Must fail when remote YouTube Music fails", failedAdd.isFailure)

        // Local cache must still contain ONLY the successfully added track
        val tracks = repository.getPlaylistTracks(remoteId, "usr_yt_test").first()
        assertEquals(1, tracks.size)
        assertEquals("Alfaaz", tracks[0].title)
        db.close()
    }
}
