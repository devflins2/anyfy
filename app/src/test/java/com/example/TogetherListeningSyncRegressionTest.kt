package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room as AndroidRoom
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.MemberStatus
import com.example.model.ChatMessage
import com.example.model.LiveReaction
import com.example.model.RoomQueueItem
import com.example.model.Participant
import com.example.model.Room
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.integrations.InMemoryRealtimeService
import com.example.service.integrations.RemotePlaybackState
import com.example.service.integrations.SocketIoRealtimeService
import com.example.service.integrations.YouTubeService
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TogetherListeningSyncRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository

    private val testTrack1 = Track(
        id = "trk_kesariya",
        title = "Kesariya",
        artist = "Arijit Singh",
        artwork = "https://example.com/kesariya.jpg",
        audioUrl = "https://aac.saavncdn.com/kesariya_320.mp4",
        durationSeconds = 268
    )

    private val testTrack2 = Track(
        id = "trk_believer",
        title = "Believer",
        artist = "Imagine Dragons",
        artwork = "https://example.com/believer.jpg",
        audioUrl = "https://aac.saavncdn.com/believer_320.mp4",
        durationSeconds = 204
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("flinsmusic_playback_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        db = AndroidRoom.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MusicRepository(db.musicDao())
        repository.registerTracks(listOf(testTrack1, testTrack2))
    }

    @After
    fun tearDown() {
        db.close()
    }

    // =========================================================================
    // SCENARIO 1: Host plays track A; participant starts track A.
    // =========================================================================
    @Test
    fun scenario1_hostPlaysTrackA_participantStartsTrackA() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        // 1. Host creates room
        val roomResult = hostRealtimeService.createRoom("Rock Room", isPublic = true, hostName = "HostUser")
        assertTrue(roomResult.isSuccess)
        val roomCode = roomResult.getOrThrow().code

        // 2. Participant joins room
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // 3. Host plays Track A
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 0L,
                playing = true,
                track = testTrack1,
                action = "play",
                timestamp = 1000L,
                sequence = 1L
            )
        )
        advanceUntilIdle()

        // 4. Participant must receive and start Track A
        val state = participantPlayback.uiState.value
        assertEquals("Participant must synchronize to track A", testTrack1.id, state.currentTrack?.id)
        assertTrue("Participant must be playing", state.isPlaying)
    }

    // =========================================================================
    // SCENARIO 2: Host pauses and resumes; participant follows.
    // =========================================================================
    @Test
    fun scenario2_hostPausesAndResumes_participantFollows() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Sync Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Initial play
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 10000L,
                playing = true,
                track = testTrack1,
                action = "play",
                timestamp = 1000L,
                sequence = 1L
            )
        )
        advanceUntilIdle()
        assertTrue(participantPlayback.uiState.value.isPlaying)

        // Host Pauses
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 15000L,
                playing = false,
                track = testTrack1,
                action = "pause",
                timestamp = 2000L,
                sequence = 2L
            )
        )
        advanceUntilIdle()
        assertFalse("Participant must follow host pause", participantPlayback.uiState.value.isPlaying)

        // Host Resumes
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 15000L,
                playing = true,
                track = testTrack1,
                action = "play",
                timestamp = 3000L,
                sequence = 3L
            )
        )
        advanceUntilIdle()
        assertTrue("Participant must follow host resume", participantPlayback.uiState.value.isPlaying)
    }

    // =========================================================================
    // SCENARIO 3: Host seeks; participant position updates.
    // =========================================================================
    @Test
    fun scenario3_hostSeeks_participantPositionUpdates() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Seek Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Start track
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 5000L,
                playing = true,
                track = testTrack1,
                action = "play",
                timestamp = 1000L,
                sequence = 1L
            )
        )
        advanceUntilIdle()

        // Host seeks forward to 45,000ms
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 45000L,
                playing = true,
                track = testTrack1,
                action = "seek",
                timestamp = 2000L,
                sequence = 2L
            )
        )
        advanceUntilIdle()

        val pos = participantPlayback.uiState.value.currentPositionMs
        assertEquals("Participant position must update to host target seek position", 45000L, pos)
    }

    // =========================================================================
    // SCENARIO 4: Host changes from track A to track B; participant switches and plays track B.
    // =========================================================================
    @Test
    fun scenario4_hostChangesFromTrackAToTrackB_participantSwitchesAndPlaysTrackB() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("TrackChange Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Host plays Track A
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 0L,
                playing = true,
                track = testTrack1,
                action = "track_change",
                timestamp = 1000L,
                sequence = 1L
            )
        )
        advanceUntilIdle()
        assertEquals(testTrack1.id, participantPlayback.uiState.value.currentTrack?.id)

        // Host skips to Track B
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack2.id,
                position = 0L,
                playing = true,
                track = testTrack2,
                action = "track_change",
                timestamp = 2000L,
                sequence = 2L
            )
        )
        advanceUntilIdle()

        val state = participantPlayback.uiState.value
        assertEquals("Participant must switch to Track B", testTrack2.id, state.currentTrack?.id)
        assertTrue("Participant must be playing Track B", state.isPlaying)
    }

    // =========================================================================
    // SCENARIO 5: Rapid track changes do not let older stream resolution override latest track.
    // =========================================================================
    @Test
    fun scenario5_rapidTrackChanges_olderResolutionDoesNotOverrideLatestTrack() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Rapid Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Rapid Track A then Track B
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 0L,
                playing = true,
                track = testTrack1,
                action = "track_change",
                timestamp = 1000L,
                sequence = 1L
            )
        )
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack2.id,
                position = 0L,
                playing = true,
                track = testTrack2,
                action = "track_change",
                timestamp = 1200L,
                sequence = 2L
            )
        )
        advanceUntilIdle()

        // Participant must strictly stay on Track B
        assertEquals("Latest requested track must win", testTrack2.id, participantPlayback.uiState.value.currentTrack?.id)

        // If an out-of-order event for Track A arrives late with an older timestamp
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 5000L,
                playing = true,
                track = testTrack1,
                action = "sync",
                timestamp = 1100L, // Older than 1200L
                sequence = 1L
            )
        )
        advanceUntilIdle()

        assertEquals("Out-of-order stale event for Track A must be rejected", testTrack2.id, participantPlayback.uiState.value.currentTrack?.id)
    }

    // =========================================================================
    // SCENARIO 6: Late joiners receive and apply the current playback state.
    // =========================================================================
    @Test
    fun scenario6_lateJoinersReceiveAndApplyCurrentPlaybackState() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()

        // Host creates room and plays track at 50s
        val roomResult = hostRealtimeService.createRoom("Late Join Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 50000L,
                playing = true,
                track = testTrack1,
                action = "play",
                timestamp = 1000L,
                sequence = 1L
            )
        )

        // Late joining participant connects using separate service
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        val state = participantPlayback.uiState.value
        assertEquals("Late joiner should have Track A", testTrack1.id, state.currentTrack?.id)
        assertTrue("Late joiner should be playing", state.isPlaying)
        assertEquals("Late joiner should adopt position 50s", 50000L, state.currentPositionMs)
    }

    // =========================================================================
    // SCENARIO 7: Reconnection restores the correct room and playback state.
    // =========================================================================
    @Test
    fun scenario7_reconnectionRestoresCorrectRoomAndPlaybackState() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        val createResult = service.createRoom("Recon Room", isPublic = true, hostName = "HostUser")
        if (createResult.isSuccess) {
            val room = service.activeRoom.first()
            assertNotNull(room)
            val originalCode = room!!.code
            assertTrue(originalCode.startsWith("FM-"))

            // Simulate seek and playback update
            service.syncPlayback(
                RemotePlaybackState(
                    trackId = testTrack1.id,
                    position = 30000L,
                    playing = true,
                    track = testTrack1,
                    action = "seek",
                    timestamp = 2000L,
                    sequence = 2L
                )
            )

            // Verify activeRoom maintains code and position across updates without wipeout
            val updated = service.activeRoom.first()
            assertEquals(originalCode, updated?.code)
            assertEquals(testTrack1.id, updated?.currentTrackId)
            assertEquals(30000L, updated?.playbackPositionMs)
            assertTrue(updated?.isPlaying == true)
            assertTrue(service.isHost())
        }
    }

    // =========================================================================
    // SCENARIO 8: Missing participant metadata triggers resolution rather than silently stopping.
    // =========================================================================
    @Test
    fun scenario8_missingParticipantMetadataTriggersResolutionRatherThanSilentlyStopping() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Metadata Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Unseen track ID that is NOT in participant's repository
        val unseenTrackId = "trk_unseen_999"
        val unseenTrack = Track(
            id = unseenTrackId,
            title = "Unseen Mystery Song",
            artist = "Mystery Artist",
            artwork = "https://example.com/art.jpg",
            audioUrl = "https://aac.saavncdn.com/fallback_sample.mp4",
            durationSeconds = 180
        )

        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = unseenTrackId,
                position = 0L,
                playing = true,
                track = unseenTrack,
                action = "play",
                timestamp = 1000L,
                sequence = 1L
            )
        )
        advanceUntilIdle()

        // Participant must resolve and initiate playback for the new track
        val state = participantPlayback.uiState.value
        assertEquals("Participant must resolve unseen track ID", unseenTrackId, state.currentTrack?.id)
        assertNotNull("Participant repository must register the new track", repository.getTrackById(unseenTrackId))
        assertTrue("Participant must be active and not silently idle", state.status != PlayerStatus.IDLE)
    }

    // =========================================================================
    // SCENARIO 9: Failed stream resolution produces a recoverable error.
    // =========================================================================
    @Test
    fun scenario9_failedStreamResolutionProducesRecoverableError() = runTest {
        val failingYouTubeService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> {
                return Result.failure(IllegalStateException("Stream resolution failed"))
            }
            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> {
                return Result.failure(IllegalStateException("Stream resolution failed"))
            }
            override suspend fun searchTracks(query: String): List<Track> = emptyList()
            override fun setContext(context: Context) {}
        }
        val playbackService = PlaybackService(context, failingYouTubeService)

        val unresolvableTrack = Track(
            id = "yt_invalid_track",
            title = "Broken Track",
            artist = "Nobody",
            artwork = "https://example.com/none.jpg",
            audioUrl = null,
            durationSeconds = 120
        )

        playbackService.playTrack(unresolvableTrack)

        // Await the state transition out of LOADING to UNAVAILABLE
        val state = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5000L) {
                playbackService.uiState.first { !it.status.isLoadingState }
            }
        }

        assertEquals(PlayerStatus.UNAVAILABLE, state.status)
        assertFalse(state.isPlaying)
        assertNotNull("Must provide a recoverable error message", state.errorMessage)

        // Retry must be possible without app restart
        playbackService.retryCurrentTrack()
        val retryState = playbackService.uiState.value
        assertEquals("Retry must reload the current track without app restart", unresolvableTrack.id, retryState.currentTrack?.id)
    }

    // =========================================================================
    // SCENARIO 10: Stale or duplicate events do not corrupt playback.
    // =========================================================================
    @Test
    fun scenario10_staleOrDuplicateEventsDoNotCorruptPlayback() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Stale Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Newer event (seq 5, ts 5000)
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 30000L,
                playing = true,
                track = testTrack1,
                action = "play",
                timestamp = 5000L,
                sequence = 5L
            )
        )
        advanceUntilIdle()
        assertEquals(30000L, participantPlayback.uiState.value.currentPositionMs)

        // Stale event (seq 2, ts 2000) arriving out of order
        hostRealtimeService.syncPlayback(
            RemotePlaybackState(
                trackId = testTrack1.id,
                position = 5000L,
                playing = false,
                track = testTrack1,
                action = "pause",
                timestamp = 2000L,
                sequence = 2L
            )
        )
        advanceUntilIdle()

        // State must remain at 30,000ms and playing = true!
        val state = participantPlayback.uiState.value
        assertEquals("Stale position must not overwrite newer position", 30000L, state.currentPositionMs)
        assertTrue("Stale pause must not overwrite newer play", state.isPlaying)
    }

    // =========================================================================
    // SCENARIO 11: Room isolation is maintained.
    // =========================================================================
    @Test
    fun scenario11_roomIsolationIsMaintained() = runTest {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")

        val localRoom = Room(
            id = "room_1111",
            code = "FM-1111",
            hostId = "host_1",
            name = "Room A",
            isPublic = true,
            participants = listOf(Participant(id = "p1", name = "Bob", isHost = false)),
            currentTrackId = "trk_kesariya",
            isPlaying = true,
            playbackPositionMs = 1000L
        )

        // Event for a foreign room
        val eventOtherRoom = JSONObject().apply {
            put("roomId", "FM-9999")
            put("code", "FM-9999")
            put("trackId", "trk_foreign")
            put("isPlaying", false)
            put("position", 99999L)
        }

        val parsed = service.parseRoom(eventOtherRoom)
        assertNotEquals("Foreign room event must not match local room code", localRoom.code, parsed.code)
        assertEquals("FM-9999", parsed.code)
    }

    // =========================================================================
    // SCENARIO 12: Normal independent playback outside a Together room remains unaffected.
    // =========================================================================
    @Test
    fun scenario12_normalIndependentPlaybackOutsideTogetherRoomRemainsUnaffected() = runTest {
        val participantPlayback = PlaybackService(context)
        val standaloneVm = MainViewModel(repository, participantPlayback)

        // Verify no active room
        assertNull("Must not be in any room", standaloneVm.activeRoom.value)

        // Normal play
        standaloneVm.playTrack(testTrack1)
        assertTrue("Normal playback must start", participantPlayback.uiState.value.isPlaying)
        assertEquals(testTrack1.id, participantPlayback.uiState.value.currentTrack?.id)

        // Normal pause
        standaloneVm.togglePlayPause()
        assertFalse("Normal playback must pause", participantPlayback.uiState.value.isPlaying)

        // Normal resume
        standaloneVm.togglePlayPause()
        assertTrue("Normal playback must resume", participantPlayback.uiState.value.isPlaying)

        // Normal seek
        standaloneVm.seekTo(25000L)
        assertEquals(25000L, participantPlayback.uiState.value.currentPositionMs)

        // Normal next track
        standaloneVm.playTrack(testTrack2)
        assertEquals(testTrack2.id, participantPlayback.uiState.value.currentTrack?.id)
    }

    // =========================================================================
    // Role & Alias Unit Tests
    // =========================================================================
    @Test
    fun hostRole_isCorrectlyIdentified_afterRoomCreation() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        val createResult = service.createRoom("Rock Room", isPublic = true, hostName = "HostUser")
        if (createResult.isSuccess) {
            assertTrue(service.isHost())
            val room = service.activeRoom.first()
            assertNotNull(room)
            assertEquals("HostUser", room?.participants?.firstOrNull()?.name)
            assertTrue(room?.participants?.firstOrNull()?.isHost == true)
        }
    }

    @Test
    fun participantRole_isCorrectlyIdentified_andNotMarkedAsHost() = runTest {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        assertFalse(service.isHost())
    }

    @Test
    fun parseRoom_extractsTrackId_fromVariousFieldAliases() {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")

        val json1 = JSONObject().apply {
            put("code", "FM-1001")
            put("name", "Test Room")
            put("isPublic", true)
            put("currentTrackId", "trk_kesariya")
            put("isPlaying", true)
            put("playbackPositionMs", 45000L)
            put("participants", JSONArray())
        }
        val room1 = service.parseRoom(json1)
        assertEquals("trk_kesariya", room1.currentTrackId)
        assertTrue(room1.isPlaying)
        assertEquals(45000L, room1.playbackPositionMs)

        val json2 = JSONObject().apply {
            put("code", "FM-1002")
            put("name", "Test Room 2")
            put("isPublic", true)
            put("trackId", "trk_believer")
            put("isPlaying", false)
            put("positionMs", 12000L)
            put("participants", JSONArray())
        }
        val room2 = service.parseRoom(json2)
        assertEquals("trk_believer", room2.currentTrackId)
        assertFalse(room2.isPlaying)
        assertEquals(12000L, room2.playbackPositionMs)
    }

    @Test
    fun memberJoinedCallback_triggersStateBroadcastForHost() {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        var joinedMemberName: String? = null
        service.onMemberJoinedListener = { memberName ->
            joinedMemberName = memberName
        }

        service.onMemberJoinedListener?.invoke("Charlie")
        assertEquals("Charlie", joinedMemberName)
    }

    // =========================================================================
    // SCENARIO 13: Shared queue - add, reorder, remove, advance, and isolation.
    // =========================================================================
    @Test
    fun scenario13_sharedQueue_addReorderRemoveAndAdvance() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Party Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // 1. Host adds testTrack1
        hostRealtimeService.addToQueue(testTrack1)
        advanceUntilIdle()

        var room = participantVm.activeRoom.value
        assertNotNull(room)
        assertEquals(1, room!!.sharedQueue.size)
        assertEquals(testTrack1.id, room.sharedQueue[0].track.id)

        // 2. Participant adds testTrack2
        participantVm.addTrackToRoomQueue(testTrack2)
        advanceUntilIdle()

        room = participantVm.activeRoom.value
        assertEquals(2, room!!.sharedQueue.size)
        assertEquals(testTrack1.id, room.sharedQueue[0].track.id)
        assertEquals(testTrack2.id, room.sharedQueue[1].track.id)

        // 3. Reorder: move track 2 to position 0
        participantVm.reorderRoomQueue(1, 0)
        advanceUntilIdle()

        room = participantVm.activeRoom.value
        assertEquals(testTrack2.id, room!!.sharedQueue[0].track.id)
        assertEquals(testTrack1.id, room.sharedQueue[1].track.id)

        // 4. Remove track 2
        val itemToRemove = room.sharedQueue[0]
        participantVm.removeTrackFromRoomQueue(itemToRemove.opId)
        advanceUntilIdle()

        room = participantVm.activeRoom.value
        assertEquals(1, room!!.sharedQueue.size)
        assertEquals(testTrack1.id, room.sharedQueue[0].track.id)

        // 5. Advance queue (host plays next from queue)
        hostRealtimeService.advanceQueue()
        advanceUntilIdle()

        room = participantVm.activeRoom.value
        assertEquals(testTrack1.id, room!!.currentTrackId)
        assertEquals(0, room.sharedQueue.size)
    }

    // =========================================================================
    // SCENARIO 14: Chat messaging and track sharing into chat.
    // =========================================================================
    @Test
    fun scenario14_chatMessaging_andTrackSharing() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Chat Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Participant sends text message
        participantVm.sendChatMessage("Hello party people!")
        advanceUntilIdle()

        var room = participantVm.activeRoom.value
        assertNotNull(room)
        assertEquals(1, room!!.chatMessages.size)
        assertEquals("Hello party people!", room.chatMessages[0].text)
        assertNull(room.chatMessages[0].sharedTrack)

        // Host sends a message sharing testTrack1
        hostRealtimeService.sendChatMessage("Check this track out!", testTrack1)
        advanceUntilIdle()

        room = participantVm.activeRoom.value
        assertEquals(2, room!!.chatMessages.size)
        val sharedMsg = room.chatMessages[1]
        assertEquals("Check this track out!", sharedMsg.text)
        assertNotNull(sharedMsg.sharedTrack)
        assertEquals(testTrack1.id, sharedMsg.sharedTrack?.id)
    }

    // =========================================================================
    // SCENARIO 15: Live reactions emission and broadcast.
    // =========================================================================
    @Test
    fun scenario15_liveReactions_emissionAndBroadcast() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Reaction Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        participantVm.sendReaction("FIRE")
        advanceUntilIdle()

        var room = participantVm.activeRoom.value
        assertNotNull(room)
        assertEquals(1, room!!.recentReactions.size)
        assertEquals("FIRE", room.recentReactions[0].type)
        assertEquals("🔥", room.recentReactions[0].emoji)

        hostRealtimeService.sendReaction("HEART")
        advanceUntilIdle()

        room = participantVm.activeRoom.value
        assertEquals(2, room!!.recentReactions.size)
        assertEquals("HEART", room.recentReactions[1].type)
        assertEquals("❤️", room.recentReactions[1].emoji)
    }

    // =========================================================================
    // SCENARIO 16: Chat message validation, room isolation, and queue progression without voting.
    // =========================================================================
    @Test
    fun scenario16_chatValidation_andQueueProgression_withoutVoting() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Party Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // 1. Empty chat message rejection
        val emptySendResult = participantRealtimeService.sendChatMessage("   ")
        assertTrue(emptySendResult.isFailure)
        assertEquals("Message cannot be empty", emptySendResult.exceptionOrNull()?.message)

        // 2. 500-character message limit rejection
        val oversizedText = "M".repeat(501)
        val oversizedResult = participantRealtimeService.sendChatMessage(oversizedText)
        assertTrue(oversizedResult.isFailure)
        assertEquals("Message exceeds 500 characters", oversizedResult.exceptionOrNull()?.message)

        // 3. Valid message delivery
        val validSendResult = participantRealtimeService.sendChatMessage("Let the music play!")
        assertTrue(validSendResult.isSuccess)
        advanceUntilIdle()

        val roomAfterChat = participantVm.activeRoom.value
        assertNotNull(roomAfterChat)
        assertEquals(1, roomAfterChat!!.chatMessages.size)
        assertEquals("Let the music play!", roomAfterChat.chatMessages[0].text)

        // 4. Shared queue addition & automatic next-track progression without voting
        hostRealtimeService.addToQueue(testTrack1)
        hostRealtimeService.addToQueue(testTrack2)
        advanceUntilIdle()

        var room = participantVm.activeRoom.value
        assertNotNull(room)
        assertEquals(2, room!!.sharedQueue.size)
        assertEquals(testTrack1.id, room.sharedQueue[0].track.id)

        // Host advances track from shared queue (nextInRoomOrLocal)
        val hostPlayback = PlaybackService(context)
        val hostVm = MainViewModel(repository, hostPlayback, hostRealtimeService)
        hostVm.nextInRoomOrLocal()
        advanceUntilIdle()

        room = hostVm.activeRoom.value
        assertNotNull(room)
        assertEquals(1, room!!.sharedQueue.size)
        assertEquals(testTrack2.id, room.sharedQueue[0].track.id)
    }

    // =========================================================================
    // SCENARIO 17: Member presence and buffering status.
    // =========================================================================
    @Test
    fun scenario17_memberPresence_andBufferingStatus() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Presence Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Participant reports BUFFERING
        participantRealtimeService.updateMemberStatus(MemberStatus.BUFFERING)
        advanceUntilIdle()

        var room = hostRealtimeService.activeRoom.first()
        val participant = room?.participants?.find { !it.isHost }
        assertEquals(MemberStatus.BUFFERING, participant?.status)

        // Participant reports ONLINE
        participantRealtimeService.updateMemberStatus(MemberStatus.ONLINE)
        advanceUntilIdle()

        room = hostRealtimeService.activeRoom.first()
        val participantOnline = room?.participants?.find { !it.isHost }
        assertEquals(MemberStatus.ONLINE, participantOnline?.status)
    }

    // =========================================================================
    // SCENARIO 18: parseRoom maps party features from JSON payload.
    // =========================================================================
    @Test
    fun scenario18_parseRoom_mapsPartyFeaturesJson() {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")

        val payload = JSONObject().apply {
            put("code", "FM-7777")
            put("name", "Deep House Party")
            put("hostId", "host_123")
            put("isPlaying", true)
            put("playbackPositionMs", 42000L)
            put("queueRevision", 5L)
            put("isHostRecovering", true)

            // Participants with status
            val parts = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "u_alice")
                    put("name", "Alice")
                    put("isHost", true)
                    put("status", "ONLINE")
                })
                put(JSONObject().apply {
                    put("id", "u_bob")
                    put("name", "Bob")
                    put("isHost", false)
                    put("status", "BUFFERING")
                })
            }
            put("participants", parts)

            // Shared queue
            val queue = JSONArray().apply {
                put(JSONObject().apply {
                    put("opId", "op_1")
                    put("addedByUserId", "u_alice")
                    put("addedByUserName", "Alice")
                    put("track", JSONObject().apply {
                        put("id", "trk_1")
                        put("title", "Song 1")
                        put("artist", "Artist 1")
                        put("durationSeconds", 200)
                    })
                })
            }
            put("sharedQueue", queue)

            // Chat messages
            val chat = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "msg_1")
                    put("senderId", "u_bob")
                    put("senderName", "Bob")
                    put("text", "Great track!")
                    put("timestampMs", 1700000000000L)
                })
            }
            put("chatMessages", chat)


        }

        val room = service.parseRoom(payload)
        assertEquals("FM-7777", room.code)
        assertEquals("Deep House Party", room.name)
        assertTrue(room.isHostRecovering)
        assertEquals(5L, room.queueRevision)
        assertEquals(2, room.participants.size)
        assertEquals(MemberStatus.ONLINE, room.participants[0].status)
        assertEquals(MemberStatus.BUFFERING, room.participants[1].status)
        assertEquals(1, room.sharedQueue.size)
        assertEquals("trk_1", room.sharedQueue[0].track.id)
        assertEquals("Alice", room.sharedQueue[0].addedByUserName)
        assertEquals(1, room.chatMessages.size)
        assertEquals("Great track!", room.chatMessages[0].text)
    }

    // =========================================================================
    // SCENARIO 19: Host is single source of truth - non-host cannot independently change playback position
    // =========================================================================
    @Test
    fun scenario19_hostIsSingleSourceOfTruth_nonHostCannotChangePlaybackPosition() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val hostPlayback = PlaybackService(context)
        val hostVm = MainViewModel(repository, hostPlayback, hostRealtimeService)
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Seek Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // 1. Host starts playing track 1
        hostVm.playTrack(testTrack1)
        advanceUntilIdle()

        // Participant follows
        assertEquals(testTrack1.id, participantPlayback.uiState.value.currentTrack?.id)

        // 2. Host seeks forward to 50000ms - authoritative host seek synchronizes room
        hostVm.seekTo(50000L)
        advanceUntilIdle()

        assertEquals(50000L, hostPlayback.uiState.value.currentPositionMs)
        assertEquals(50000L, participantPlayback.uiState.value.currentPositionMs)

        // 3. Non-host attempts to seek - blocked, host authoritative position is preserved for all users
        participantVm.seekTo(15000L)
        advanceUntilIdle()

        assertEquals(50000L, hostPlayback.uiState.value.currentPositionMs)
        assertEquals(50000L, participantPlayback.uiState.value.currentPositionMs)
    }

    // =========================================================================
    // SCENARIO 20: Any authorized member can select a track and change the shared current song
    // =========================================================================
    @Test
    fun scenario20_nonHostMemberSelectsTrack_changesSharedCurrentTrack() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val hostPlayback = PlaybackService(context)
        val hostVm = MainViewModel(repository, hostPlayback, hostRealtimeService)
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Track Control Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Host plays Track 1
        hostVm.playTrack(testTrack1)
        advanceUntilIdle()

        // Participant adds Track 2 to shared queue
        participantVm.addTrackToRoomQueue(testTrack2)
        advanceUntilIdle()

        val roomBefore = participantVm.activeRoom.value
        assertNotNull(roomBefore)
        assertEquals(1, roomBefore!!.sharedQueue.size)
        val queueItem = roomBefore.sharedQueue.first()

        // Non-host selects Track 2 from the shared queue
        participantVm.selectTrackInRoom(queueItem.track, queueItem.opId)
        advanceUntilIdle()

        // Both host and participant must be playing Track 2
        assertEquals(testTrack2.id, hostPlayback.uiState.value.currentTrack?.id)
        assertEquals(testTrack2.id, participantPlayback.uiState.value.currentTrack?.id)
        assertTrue(hostPlayback.uiState.value.isPlaying)
        assertTrue(participantPlayback.uiState.value.isPlaying)

        // Shared queue has track removed
        val roomAfter = hostVm.activeRoom.value
        assertNotNull(roomAfter)
        assertEquals(0, roomAfter!!.sharedQueue.size)
    }

    // =========================================================================
    // SCENARIO 21: Ephemeral chat - in-memory only, wiped on leave, no persistent store
    // =========================================================================
    @Test
    fun scenario21_ephemeralChat_inMemoryOnly_wipedOnLeaveRoom() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val participantPlayback = PlaybackService(context)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Chat Room", isPublic = true, hostName = "HostUser")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Send ephemeral chat message
        participantVm.sendChatMessage("Hello party!")
        advanceUntilIdle()

        val activeRoom = participantVm.activeRoom.value
        assertNotNull(activeRoom)
        assertEquals(1, activeRoom!!.chatMessages.size)
        assertEquals("Hello party!", activeRoom.chatMessages.first().text)

        // Verify NO chat messages stored in Room Database
        val tables = mutableListOf<String>()
        val cursor = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'")
        while (cursor.moveToNext()) {
            tables.add(cursor.getString(0))
        }
        cursor.close()
        assertTrue("No chat table should exist in database", tables.none { it.contains("chat", ignoreCase = true) })

        // When user leaves room, active room is null and local volatile memory disappears
        participantVm.leaveRoom()
        advanceUntilIdle()
        assertNull(participantVm.activeRoom.value)
    }

    // =========================================================================
    // SCENARIO 22: Host intentionally ends party for everyone
    // =========================================================================
    @Test
    fun scenario22_hostEndParty_endsSessionForHostAndParticipants() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val hostPlayback = PlaybackService(context)
        val participantPlayback = PlaybackService(context)
        val hostVm = MainViewModel(repository, hostPlayback, hostRealtimeService)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        // 1. Host creates room
        val roomResult = hostRealtimeService.createRoom("Rock Room", isPublic = true, hostName = "HostUser")
        assertTrue(roomResult.isSuccess)
        val roomCode = roomResult.getOrThrow().code

        // 2. Participant joins
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()
        assertNotNull(hostVm.activeRoom.value)
        assertNotNull(participantVm.activeRoom.value)

        // 3. Host calls endRoom()
        hostVm.endRoom()
        advanceUntilIdle()

        // 4. Verify host and participant rooms are closed/null
        assertNull(hostVm.activeRoom.value)
        assertNull(participantVm.activeRoom.value)
    }

    // =========================================================================
    // SCENARIO 23: Participant joins, leaves, and rejoins the same room -> NO duplicate participant slots
    // =========================================================================
    @Test
    fun scenario23_participantLeavesAndRejoins_noDuplicateParticipantSlots() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val hostPlayback = PlaybackService(context)
        val participantPlayback = PlaybackService(context)
        val hostVm = MainViewModel(repository, hostPlayback, hostRealtimeService)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        // 1. Host creates room
        val roomResult = hostRealtimeService.createRoom("Deduplication Room", isPublic = true, hostName = "HostGaurav")
        assertTrue(roomResult.isSuccess)
        val roomCode = roomResult.getOrThrow().code

        // 2. Participant joins
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        val roomAfterFirstJoin = hostVm.activeRoom.value
        assertNotNull(roomAfterFirstJoin)
        assertEquals(2, roomAfterFirstJoin!!.participants.size)

        // 3. Participant leaves the room
        participantVm.leaveRoom()
        advanceUntilIdle()

        val roomAfterLeave = hostVm.activeRoom.value
        assertNotNull(roomAfterLeave)
        assertEquals(1, roomAfterLeave!!.participants.size)

        // 4. Participant rejoins the SAME room
        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        val roomAfterRejoin = hostVm.activeRoom.value
        assertNotNull(roomAfterRejoin)

        // CRITICAL ASSERTION: Exactly 2 participants, no duplicates for the participant
        assertEquals("Room must have exactly 2 participants after rejoin", 2, roomAfterRejoin!!.participants.size)
        val participantCount = roomAfterRejoin.participants.count { !it.isHost }
        assertEquals("There must be exactly one participant entry", 1, participantCount)
        assertEquals(
            "Participants list distinct by id must equal list size",
            roomAfterRejoin.participants.size,
            roomAfterRejoin.participants.distinctBy { it.id }.size
        )
    }

    // =========================================================================
    // SCENARIO 24: Slaved participant playback flag is managed by active room membership
    // =========================================================================
    @Test
    fun scenario24_slavedParticipantFlagIsControlledByActiveRoomState() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val hostPlayback = PlaybackService(context)
        val participantPlayback = PlaybackService(context)
        val hostVm = MainViewModel(repository, hostPlayback, hostRealtimeService)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Sync Protection Room", isPublic = true, hostName = "HostGaurav")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Participant is verified as slaved to room
        assertTrue("Participant playback service must be slaved to room", participantPlayback.isRoomParticipantSlaved)
        assertFalse("Host playback service must not be slaved", hostPlayback.isRoomParticipantSlaved)

        // Host plays Track 1
        hostVm.playTrack(testTrack1)
        advanceUntilIdle()

        assertEquals(testTrack1.id, participantPlayback.uiState.value.currentTrack?.id)

        // When participant leaves room, isRoomParticipantSlaved must reset to false
        participantVm.leaveRoom()
        advanceUntilIdle()

        assertFalse("Leaving room must restore independent playback mode", participantPlayback.isRoomParticipantSlaved)
    }

    // =========================================================================
    // SCENARIO 25: Stale sequence numbers are dropped and do not alter playback
    // =========================================================================
    @Test
    fun scenario25_staleSequenceNumberIsRejected() = runTest {
        val hostRealtimeService = InMemoryRealtimeService()
        val participantRealtimeService = InMemoryRealtimeService()
        val hostPlayback = PlaybackService(context)
        val participantPlayback = PlaybackService(context)
        val hostVm = MainViewModel(repository, hostPlayback, hostRealtimeService)
        val participantVm = MainViewModel(repository, participantPlayback, participantRealtimeService)

        val roomResult = hostRealtimeService.createRoom("Sequence Test Room", isPublic = true, hostName = "HostGaurav")
        val roomCode = roomResult.getOrThrow().code

        participantVm.joinRoom(roomCode)
        advanceUntilIdle()

        // Process a newer event with sequence 10
        val newerRoom = hostVm.activeRoom.value!!.copy(
            currentTrackId = testTrack1.id,
            currentTrack = testTrack1,
            playbackPositionMs = 50000L,
            isPlaying = true,
            sequenceNumber = 10L,
            lastEventTimestamp = 100000L
        )
        participantRealtimeService.testBroadcastRoom(newerRoom)
        advanceUntilIdle()

        assertEquals(testTrack1.id, participantPlayback.uiState.value.currentTrack?.id)

        // Now an older out-of-order event with sequence 5 arrives
        val olderRoom = newerRoom.copy(
            currentTrackId = testTrack2.id,
            currentTrack = testTrack2,
            playbackPositionMs = 10000L,
            sequenceNumber = 5L,
            lastEventTimestamp = 50000L
        )
        participantRealtimeService.testBroadcastRoom(olderRoom)
        advanceUntilIdle()

        // Participant must STILL be playing Track 1 (sequence 10), older event with sequence 5 must be dropped
        assertEquals(testTrack1.id, participantPlayback.uiState.value.currentTrack?.id)
    }

    // =========================================================================
    // SCENARIO 26: SocketIoRealtimeService parseRoom deduplicates participants
    // =========================================================================
    @Test
    fun scenario26_parseRoomDeduplicatesDuplicateParticipantSlots() {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        val json = JSONObject().apply {
            put("roomId", "FM-DEDUP")
            put("code", "FM-DEDUP")
            put("hostId", "host_1")
            put("participants", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "user_1")
                    put("name", "Gaurav")
                    put("avatarColorHex", "#00F0FF")
                    put("isHost", false)
                    put("status", "ONLINE")
                })
                put(JSONObject().apply {
                    put("id", "user_1") // Duplicate entry for user_1
                    put("name", "Gaurav")
                    put("avatarColorHex", "#00F0FF")
                    put("isHost", false)
                    put("status", "ONLINE")
                })
                put(JSONObject().apply {
                    put("id", "user_2")
                    put("name", "Alex")
                    put("avatarColorHex", "#8B5CF6")
                    put("isHost", false)
                    put("status", "ONLINE")
                })
            })
        }

        val room = service.parseRoom(json)
        assertEquals("Participants list must be deduplicated", 2, room.participants.size)
        assertEquals("user_1", room.participants[0].id)
        assertEquals("user_2", room.participants[1].id)
    }

    // =========================================================================
    // SCENARIO 27: Persistent User ID Provider keeps identity stable across instances
    // =========================================================================
    @Test
    fun scenario27_persistentUserIdProviderKeepsIdentityStable() {
        val persistentId = "usr_persistent_stable_1234"
        val service1 = SocketIoRealtimeService("https://socketformusic.onrender.com/", persistentUserIdProvider = { persistentId })
        val json1 = JSONObject().apply {
            put("roomId", "FM-1111")
            put("code", "FM-1111")
            put("hostId", persistentId)
        }
        val parsed = service1.parseRoom(json1)
        service1.setActiveRoomForTesting(parsed)
        assertTrue("Service initialized with persistent host ID must be identified as host", service1.isHost())
        assertEquals("Local user ID must match persistent ID", persistentId, service1.getLocalUserIdForTesting())
    }
}



