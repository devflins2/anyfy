package com.example.service.integrations

import android.util.Log
import com.example.data.repository.ServerConfigRepository
import com.example.model.Room
import com.example.model.Track
import com.example.model.MemberStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Dynamic Socket.IO Realtime Service.
 * Manages runtime server switching without APK updates or silent session disconnects.
 * Ensures zero duplicate socket connections and safe room departure before teardown.
 */
class DynamicSocketIoRealtimeService(
    private val configRepository: ServerConfigRepository,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e("DynamicSocketService", "Unhandled realtime coroutine error: ${e.message}", e)
        }
    ),
    private val persistentUserIdProvider: (() -> String)? = null
) : RealtimeRoomService {

    private val _activeRoom = MutableStateFlow<Room?>(null)
    override val activeRoom: Flow<Room?> = _activeRoom.asStateFlow()

    @Volatile
    private var currentService: SocketIoRealtimeService? = null
    @Volatile
    private var currentEndpointUrl: String = ""
    @Volatile
    private var pendingEndpointUrl: String? = null

    override var onMemberJoinedListener: ((String) -> Unit)? = null
        set(value) {
            field = value
            currentService?.onMemberJoinedListener = value
        }

    override fun isHost(): Boolean = currentService?.isHost() ?: false

    private var roomObservationJob: Job? = null

    init {
        // Initialize with currently active configuration
        val initialConfig = configRepository.activeServer.value
        switchEndpointInternal(initialConfig.url)

        // Observe future server configuration updates
        configRepository.activeServer.onEach { newConfig ->
            val targetUrl = newConfig.url
            if (targetUrl.equals(currentEndpointUrl, ignoreCase = true)) {
                return@onEach
            }

            // Do not silently disconnect a user who is actively in a room session
            if (_activeRoom.value != null) {
                Log.i("DynamicSocketService", "Deferred server switch to $targetUrl until active room is left.")
                pendingEndpointUrl = targetUrl
            } else {
                switchEndpointInternal(targetUrl)
            }
        }.launchIn(scope)

        // Kick off remote config refresh asynchronously
        scope.launch {
            configRepository.refreshRemoteConfig()
        }
    }

    @Synchronized
    private fun switchEndpointInternal(newUrl: String) {
        if (newUrl.isBlank()) return
        Log.i("DynamicSocketService", "Switching Socket.IO endpoint to $newUrl")

        // 1. Release previous socket listeners and disconnect
        roomObservationJob?.cancel()
        currentService?.disconnectAndCleanup()

        // 2. Instantiate new service with validated endpoint
        val newService = SocketIoRealtimeService(newUrl, persistentUserIdProvider = persistentUserIdProvider)
        newService.onMemberJoinedListener = onMemberJoinedListener
        currentService = newService
        currentEndpointUrl = newUrl
        pendingEndpointUrl = null

        // 3. Pipe activeRoom from the new service
        roomObservationJob = scope.launch {
            newService.activeRoom.collect { room ->
                _activeRoom.value = room
                if (room == null) {
                    val pending = pendingEndpointUrl
                    if (pending != null && !pending.equals(currentEndpointUrl, ignoreCase = true)) {
                        Log.i("DynamicSocketService", "Applying deferred server switch to $pending after room session closed.")
                        switchEndpointInternal(pending)
                    }
                }
            }
        }
    }

    fun getCurrentEndpointUrl(): String = currentEndpointUrl

    fun getPendingEndpointUrl(): String? = pendingEndpointUrl

    fun hasPendingSwitch(): Boolean = pendingEndpointUrl != null

    /**
     * Forces the pending switch immediately (e.g. if admin user explicitly confirms).
     */
    fun applyPendingSwitch() {
        val pending = pendingEndpointUrl ?: return
        switchEndpointInternal(pending)
    }

    override suspend fun createRoom(name: String, isPublic: Boolean, hostName: String): Result<Room> {
        val service = currentService ?: return Result.failure(IllegalStateException("Socket service is not initialized"))
        return service.createRoom(name, isPublic, hostName)
    }

    override suspend fun joinRoom(code: String, participantName: String): Result<Room> {
        val service = currentService ?: return Result.failure(IllegalStateException("Socket service is not initialized"))
        return service.joinRoom(code, participantName)
    }

    override suspend fun leaveRoom(): Result<Unit> {
        val service = currentService
        val result = service?.leaveRoom() ?: Result.success(Unit)
        _activeRoom.value = null

        // If a server switch was deferred while the user was in the room, apply it now
        val pending = pendingEndpointUrl
        if (pending != null && !pending.equals(currentEndpointUrl, ignoreCase = true)) {
            Log.i("DynamicSocketService", "Applying deferred server switch to $pending after leaving room.")
            switchEndpointInternal(pending)
        }
        return result
    }

    override suspend fun endRoom(): Result<Unit> {
        val service = currentService
        val result = service?.endRoom() ?: Result.success(Unit)
        _activeRoom.value = null

        val pending = pendingEndpointUrl
        if (pending != null && !pending.equals(currentEndpointUrl, ignoreCase = true)) {
            Log.i("DynamicSocketService", "Applying deferred server switch to $pending after ending room.")
            switchEndpointInternal(pending)
        }
        return result
    }

    override suspend fun sendRoomEvent(event: RoomEvent): Result<Unit> {
        return currentService?.sendRoomEvent(event) ?: Result.success(Unit)
    }

    override suspend fun syncPlayback(state: RemotePlaybackState): Result<Unit> {
        return currentService?.syncPlayback(state) ?: Result.success(Unit)
    }

    override suspend fun addToQueue(track: Track): Result<Unit> {
        return currentService?.addToQueue(track) ?: Result.failure(IllegalStateException("Socket service not ready"))
    }

    override suspend fun removeFromQueue(opId: String): Result<Unit> {
        return currentService?.removeFromQueue(opId) ?: Result.failure(IllegalStateException("Socket service not ready"))
    }

    override suspend fun reorderQueue(fromIndex: Int, toIndex: Int): Result<Unit> {
        return currentService?.reorderQueue(fromIndex, toIndex) ?: Result.failure(IllegalStateException("Socket service not ready"))
    }

    override suspend fun advanceQueue(): Result<Unit> {
        return currentService?.advanceQueue() ?: Result.failure(IllegalStateException("Socket service not ready"))
    }

    override suspend fun changeTrack(track: Track, opId: String?): Result<Unit> {
        return currentService?.changeTrack(track, opId) ?: Result.failure(IllegalStateException("Socket service not ready"))
    }

    override suspend fun sendChatMessage(text: String, sharedTrack: Track?): Result<Unit> {
        return currentService?.sendChatMessage(text, sharedTrack) ?: Result.failure(IllegalStateException("Socket service not ready"))
    }

    override suspend fun sendReaction(type: String): Result<Unit> {
        return currentService?.sendReaction(type) ?: Result.failure(IllegalStateException("Socket service not ready"))
    }



    override suspend fun updateMemberStatus(status: com.example.model.MemberStatus): Result<Unit> {
        return currentService?.updateMemberStatus(status) ?: Result.success(Unit)
    }

    override suspend fun createRoomAdvanced(
        name: String,
        description: String,
        visibility: String,
        coverUrl: String,
        maxParticipants: Int,
        hostName: String
    ): Result<Room> = currentService?.createRoomAdvanced(name, description, visibility, coverUrl, maxParticipants, hostName)
        ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun transferHost(targetUserId: String): Result<Unit> =
        currentService?.transferHost(targetUserId) ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun kickParticipant(targetUserId: String): Result<Unit> =
        currentService?.kickParticipant(targetUserId) ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun setRoomLock(isLocked: Boolean): Result<Unit> =
        currentService?.setRoomLock(isLocked) ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun setChatMuted(isMuted: Boolean): Result<Unit> =
        currentService?.setChatMuted(isMuted) ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun deleteChatMessage(messageId: String): Result<Unit> =
        currentService?.deleteChatMessage(messageId) ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun submitSongRequest(track: Track): Result<Unit> =
        currentService?.submitSongRequest(track) ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun reviewSongRequest(requestId: String, approved: Boolean): Result<Unit> =
        currentService?.reviewSongRequest(requestId, approved) ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun setPlaybackModes(repeatMode: String, shuffleMode: Boolean): Result<Unit> =
        currentService?.setPlaybackModes(repeatMode, shuffleMode) ?: Result.failure(IllegalStateException("Socket service not ready"))

    override suspend fun getPublicRooms(query: String): Result<List<com.example.model.PublicRoomSummary>> =
        currentService?.getPublicRooms(query) ?: Result.success(emptyList())
}

