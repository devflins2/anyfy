package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.importer.PlaylistImportService
import com.example.service.integrations.ytmusic.YtAlbum
import com.example.service.integrations.ytmusic.YtArtist
import com.example.service.integrations.ytmusic.YtHomeShelf
import com.example.service.integrations.ytmusic.YtMusicClient
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class YouTubeMusicPlaylistTwoWaySyncRegressionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository
    private lateinit var fakeYtMusicService: FakeSyncYtMusicService
    private lateinit var importService: PlaylistImportService

    private val testUserId = "test_user_sync_101"

    private val track1 = Track(
        id = "yt_test_vid_111",
        title = "Test Song 1",
        artist = "Test Artist 1",
        artwork = "https://i.ytimg.com/vi/test_vid_111/hqdefault.jpg",
        durationSeconds = 210
    )

    private val track2 = Track(
        id = "online_test_vid_222",
        title = "Test Song 2",
        artist = "Test Artist 2",
        artwork = "https://i.ytimg.com/vi/test_vid_222/hqdefault.jpg",
        durationSeconds = 185
    )

    private class FakeSyncYtMusicService : YtMusicService {
        var authenticated = true
        var failNextMutation = false
        var mutationErrorMessage = "Remote InnerTube operation failed"

        val remotePlaylists = mutableMapOf<String, YtPlaylist>()
        val createPlaylistWithTracksCalls = mutableListOf<Triple<String, String, List<String>>>() // title, desc, videoIds
        val addPlaylistItemCalls = mutableListOf<Pair<String, String>>() // plId, videoId

        override fun isUserAuthenticated(): Boolean = authenticated

        override suspend fun createPlaylist(title: String, description: String, privacyStatus: String): Result<String> {
            return createPlaylist(title, description, privacyStatus, emptyList())
        }

        override suspend fun createPlaylist(
            title: String,
            description: String,
            privacyStatus: String,
            videoIds: List<String>
        ): Result<String> {
            if (!authenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required. Please sign in via Settings."))
            if (failNextMutation) return Result.failure(IOException(mutationErrorMessage))

            createPlaylistWithTracksCalls.add(Triple(title, description, videoIds))
            val plId = "PL_remote_${System.currentTimeMillis()}_${(100..999).random()}"
            val initialTracks = videoIds.map { vid ->
                val clean = YtMusicClient.cleanVideoId(vid)
                YtTrack(id = clean, title = "Song $clean", artist = "YouTube Artist", artwork = "")
            }
            val pl = YtPlaylist(
                id = plId,
                title = title,
                author = "Authenticated User",
                description = description,
                artwork = "",
                tracks = initialTracks
            )
            remotePlaylists[plId] = pl
            return Result.success(plId)
        }

        override suspend fun addPlaylistItem(playlistId: String, videoId: String): Result<Boolean> {
            if (!authenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required. Please sign in via Settings."))
            if (failNextMutation) return Result.failure(IOException(mutationErrorMessage))

            val cleanVid = YtMusicClient.cleanVideoId(videoId)
            addPlaylistItemCalls.add(Pair(playlistId, cleanVid))

            val existing = remotePlaylists[playlistId]
            if (existing != null) {
                remotePlaylists[playlistId] = existing.copy(
                    tracks = existing.tracks + YtTrack(id = cleanVid, title = "Track $cleanVid", artist = "Artist", artwork = "")
                )
            }
            return Result.success(true)
        }

        override suspend fun addPlaylistItems(playlistId: String, videoIds: List<String>): Result<Boolean> {
            if (!authenticated) return Result.failure(IllegalStateException("YouTube Music account authentication required. Please sign in via Settings."))
            if (failNextMutation) return Result.failure(IOException(mutationErrorMessage))

            for (v in videoIds) {
                addPlaylistItem(playlistId, v)
            }
            return Result.success(true)
        }

        override suspend fun getUserPlaylists(continuationToken: String?): Result<List<YtPlaylist>> {
            if (!authenticated) return Result.failure(IllegalStateException("Authentication required"))
            return Result.success(remotePlaylists.values.toList())
        }

        override suspend fun getPlaylist(playlistId: String, continuationToken: String?): Result<YtPlaylist> {
            val pl = remotePlaylists[playlistId] ?: remotePlaylists[playlistId.removePrefix("VL")]
            return if (pl != null) Result.success(pl) else Result.failure(IOException("Playlist not found"))
        }

        override suspend fun editPlaylist(playlistId: String, newTitle: String?, newDescription: String?): Result<Boolean> {
            if (!authenticated) return Result.failure(IllegalStateException("Authentication required"))
            val pl = remotePlaylists[playlistId] ?: return Result.failure(IOException("Not found"))
            remotePlaylists[playlistId] = pl.copy(
                title = newTitle ?: pl.title,
                description = newDescription ?: pl.description
            )
            return Result.success(true)
        }

        override suspend fun deletePlaylist(playlistId: String): Result<Boolean> {
            if (!authenticated) return Result.failure(IllegalStateException("Authentication required"))
            remotePlaylists.remove(playlistId)
            return Result.success(true)
        }

        override suspend fun getHomeShelves(): Result<List<YtHomeShelf>> = Result.success(emptyList())
        override suspend fun searchTracks(query: String): Result<List<YtTrack>> = Result.success(emptyList())
        override suspend fun search(query: String): Result<YtSearchResult> = Result.success(YtSearchResult())
        override suspend fun getArtist(browseId: String): Result<YtArtist> =
            Result.success(YtArtist(id = browseId, name = "Artist"))
        override suspend fun getAlbum(browseId: String): Result<YtAlbum> =
            Result.success(YtAlbum(id = browseId, title = "Album", artist = "Artist"))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeYtMusicService = FakeSyncYtMusicService()
        repository = MusicRepository(
            dao = db.musicDao(),
            ytMusicService = fakeYtMusicService
        )
        repository.setCurrentUserId(testUserId)
        importService = PlaylistImportService(repository)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun testYtMusicClient_isEditPlaylistSuccessful_detectsStatusFailedAndErrors() {
        // 1. STATUS_FAILED must evaluate to false
        val failedJson = JSONObject().apply {
            put("status", "STATUS_FAILED")
            put("responseContext", JSONObject().put("visitorData", "vis123"))
        }
        assertFalse("STATUS_FAILED must fail", YtMusicClient.isEditPlaylistSuccessful(failedJson))

        // 2. Action with STATUS_FAILED must evaluate to false
        val actionFailedJson = JSONObject().apply {
            put("actions", JSONArray().put(JSONObject().apply {
                put("status", "STATUS_FAILED")
                put("action", "ACTION_ADD_VIDEO")
            }))
            put("responseContext", JSONObject())
        }
        assertFalse("Action STATUS_FAILED must fail", YtMusicClient.isEditPlaylistSuccessful(actionFailedJson))

        // 3. Error object must evaluate to false
        val errorJson = JSONObject().apply {
            put("error", JSONObject().apply {
                put("code", 400)
                put("message", "Invalid playlist id")
            })
            put("responseContext", JSONObject())
        }
        assertFalse("Error payload must fail", YtMusicClient.isEditPlaylistSuccessful(errorJson))

        // 4. STATUS_SUCCEEDED must evaluate to true
        val successJson = JSONObject().apply {
            put("status", "STATUS_SUCCEEDED")
            put("responseContext", JSONObject())
        }
        assertTrue("STATUS_SUCCEEDED must succeed", YtMusicClient.isEditPlaylistSuccessful(successJson))

        // 5. Action with STATUS_SUCCEEDED must evaluate to true
        val actionSuccessJson = JSONObject().apply {
            put("actions", JSONArray().put(JSONObject().apply {
                put("status", "STATUS_SUCCEEDED")
                put("action", "ACTION_ADD_VIDEO")
            }))
            put("responseContext", JSONObject())
        }
        assertTrue("Action STATUS_SUCCEEDED must succeed", YtMusicClient.isEditPlaylistSuccessful(actionSuccessJson))
    }

    @Test
    fun testYtMusicClient_cleanVideoIdAndCleanPlaylistId_stripsPrefixes() {
        assertEquals("vid12345678", YtMusicClient.cleanVideoId("yt_vid12345678"))
        assertEquals("vid12345678", YtMusicClient.cleanVideoId("online_vid12345678"))
        assertEquals("vid12345678", YtMusicClient.cleanVideoId("api_vid12345678"))
        assertEquals("vid12345678", YtMusicClient.cleanVideoId("track_vid12345678"))
        assertEquals("PLabcdef123456", YtMusicClient.cleanPlaylistId("VLPLabcdef123456"))
        assertEquals("PLabcdef123456", YtMusicClient.cleanPlaylistId("FLPLabcdef123456"))
    }

    @Test
    fun testAddTrackToPlaylist_remoteWriteBack_succeedsAndSyncs() = runTest {
        // Step 1: Create a remote playlist
        val createRes = repository.createPlaylistResult("My Remote Rock", "Rock hits", userId = testUserId)
        assertTrue(createRes.isSuccess)
        val plId = createRes.getOrThrow()
        assertTrue(plId.startsWith("PL_remote_"))

        // Step 2: Add song to playlist
        val addRes = repository.addTrackToPlaylistResult(plId, track1, testUserId)
        assertTrue("Remote addition must succeed", addRes.isSuccess)

        // Step 3: Verify FakeYtMusicService recorded the write-back
        assertEquals(1, fakeYtMusicService.addPlaylistItemCalls.size)
        assertEquals(plId, fakeYtMusicService.addPlaylistItemCalls[0].first)
        assertEquals("test_vid_111", fakeYtMusicService.addPlaylistItemCalls[0].second)

        // Step 4: Verify song appears in the remote playlist
        val remotePl = fakeYtMusicService.remotePlaylists[plId]
        assertNotNull(remotePl)
        assertTrue(remotePl!!.tracks.any { it.id == "test_vid_111" })
    }

    @Test
    fun testAddTrackToPlaylist_remoteFailure_doesNotClaimSuccess() = runTest {
        val createRes = repository.createPlaylistResult("My Chill Beats", "Chill", userId = testUserId)
        val plId = createRes.getOrThrow()

        // Configure remote mutation failure
        fakeYtMusicService.failNextMutation = true
        fakeYtMusicService.mutationErrorMessage = "YouTube Music error: Playlist does not accept new items"

        val addRes = repository.addTrackToPlaylistResult(plId, track1, testUserId)
        assertFalse("Failed remote mutation must not claim success", addRes.isSuccess)
        val errorMsg = addRes.exceptionOrNull()?.message
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("YouTube Music error"))

        // Verify song was NOT added to local playlist tracks
        val localTracks = repository.dao.getPlaylistTrackIds(plId, testUserId).first()
        assertFalse("Failed track must not be persisted in playlist", localTracks.contains(track1.id))
    }

    @Test
    fun testCommitImport_createsRemotePlaylistWithInitialTracksAtomically() = runTest {
        val matchedTracks = listOf(track1, track2)

        val importRes = importService.commitImport(
            playlistName = "Imported Spotify Favs",
            playlistDescription = "Imported into YouTube Music",
            artworkUrl = "https://i.ytimg.com/vi/test_vid_111/hqdefault.jpg",
            matchedTracks = matchedTracks,
            userId = testUserId
        )

        assertTrue("Import must succeed", importRes.isSuccess)
        val importedId = importRes.getOrThrow()
        assertTrue(importedId.startsWith("PL_remote_"))

        // Verify atomic creation call carried both video IDs
        assertEquals(1, fakeYtMusicService.createPlaylistWithTracksCalls.size)
        val createCall = fakeYtMusicService.createPlaylistWithTracksCalls[0]
        assertEquals("Imported Spotify Favs", createCall.first)
        assertEquals(2, createCall.third.size)
        assertTrue(createCall.third.contains(track1.id))
        assertTrue(createCall.third.contains(track2.id))

        // Verify remote playlist contains both tracks
        val remotePl = fakeYtMusicService.remotePlaylists[importedId]
        assertNotNull(remotePl)
        assertEquals(2, remotePl!!.tracks.size)

        // Verify local Room database has the imported playlist
        val localPlaylists = repository.dao.getPlaylistsSnapshot(testUserId)
        assertTrue(localPlaylists.any { it.id == importedId && it.name == "Imported Spotify Favs" })
    }

    @Test
    fun testFetchUserPlaylists_protectsRecentlyCreatedPlaylistsAgainstCdnPropagationDelay() = runTest {
        // 1. Create a playlist locally that is brand new (simulating 10 seconds ago)
        val createRes = repository.createPlaylistResult("Just Created Playlist", "Propagating", userId = testUserId)
        val newPlId = createRes.getOrThrow()

        // 2. Simulate YouTube Music liked/library feed not yet containing the new playlist
        fakeYtMusicService.remotePlaylists.clear()

        // 3. Trigger fetchUserPlaylists
        val fetchRes = repository.fetchUserPlaylists(userId = testUserId, forceRefresh = true)
        assertTrue(fetchRes.isSuccess)

        // 4. Verify the newly created playlist was NOT pruned/deleted from Room
        val localPlaylists = repository.dao.getPlaylistsSnapshot(testUserId)
        assertTrue("Recently created playlist must remain in Room during propagation delay",
            localPlaylists.any { it.id == newPlId })
    }

    @Test
    fun testUnauthenticated_failsWithDescriptiveError() = runTest {
        fakeYtMusicService.authenticated = false

        val createRes = repository.createPlaylistResult("Secret Jam", userId = testUserId)
        assertFalse(createRes.isSuccess)
        val err = createRes.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("authentication required", ignoreCase = true))

        val addRes = repository.addTrackToPlaylistResult("PL_some_playlist", track1, userId = testUserId)
        assertFalse(addRes.isSuccess)
    }
}
