package com.example.service.integrations

import com.example.model.Participant
import com.example.model.Room
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Future Realtime Architecture (FlinsMusic Section 28).
 * Clean contracts ready for WebSocket / Socket.IO connection.
 */
data class RemotePlaybackState(
    val trackId: String,
    val position: Long,
    val playing: Boolean,
    val track: Track? = null,
    val action: String = "sync",
    val timestamp: Long = System.currentTimeMillis(),
    val sequence: Long = 0L
)

data class RoomEvent(
    val type: String,
    val payload: Any?
)

interface RealtimeRoomService {
    val activeRoom: Flow<Room?>
    var onMemberJoinedListener: ((String) -> Unit)?
        get() = null
        set(_) {}
    fun isHost(): Boolean = false
    suspend fun createRoom(name: String, isPublic: Boolean, hostName: String): Result<Room>
    suspend fun joinRoom(code: String, participantName: String): Result<Room>
    suspend fun leaveRoom(): Result<Unit>
    suspend fun endRoom(): Result<Unit> = Result.success(Unit)
    suspend fun sendRoomEvent(event: RoomEvent): Result<Unit>
    suspend fun syncPlayback(state: RemotePlaybackState): Result<Unit>
    suspend fun addToQueue(track: Track): Result<Unit> = Result.success(Unit)
    suspend fun removeFromQueue(opId: String): Result<Unit> = Result.success(Unit)
    suspend fun reorderQueue(fromIndex: Int, toIndex: Int): Result<Unit> = Result.success(Unit)
    suspend fun advanceQueue(): Result<Unit> = Result.success(Unit)
    suspend fun changeTrack(track: Track, opId: String? = null): Result<Unit> = Result.success(Unit)
    suspend fun sendChatMessage(text: String, sharedTrack: Track? = null): Result<Unit> = Result.success(Unit)
    suspend fun sendReaction(type: String): Result<Unit> = Result.success(Unit)
    suspend fun updateMemberStatus(status: com.example.model.MemberStatus): Result<Unit> = Result.success(Unit)
    suspend fun createRoomAdvanced(
        name: String,
        description: String = "",
        visibility: String = "PUBLIC",
        coverUrl: String = "",
        maxParticipants: Int = 20,
        hostName: String
    ): Result<Room> = createRoom(name, visibility == "PUBLIC", hostName)
    suspend fun transferHost(targetUserId: String): Result<Unit> = Result.success(Unit)
    suspend fun kickParticipant(targetUserId: String): Result<Unit> = Result.success(Unit)
    suspend fun setRoomLock(isLocked: Boolean): Result<Unit> = Result.success(Unit)
    suspend fun setChatMuted(isMuted: Boolean): Result<Unit> = Result.success(Unit)
    suspend fun deleteChatMessage(messageId: String): Result<Unit> = Result.success(Unit)
    suspend fun submitSongRequest(track: Track): Result<Unit> = Result.success(Unit)
    suspend fun reviewSongRequest(requestId: String, approved: Boolean): Result<Unit> = Result.success(Unit)
    suspend fun setPlaybackModes(repeatMode: String, shuffleMode: Boolean): Result<Unit> = Result.success(Unit)
    suspend fun getPublicRooms(query: String = ""): Result<List<com.example.model.PublicRoomSummary>> = Result.success(emptyList())
}

class InMemoryRealtimeService : RealtimeRoomService {
    private val _activeRoom = MutableStateFlow<Room?>(null)
    override val activeRoom: Flow<Room?> = _activeRoom.asStateFlow()

    private var isLocalHost: Boolean = false
    private var currentRoomCode: String? = null
    private var localUserId: String = "p_usr_${System.currentTimeMillis()}"
    private var localUserName: String = "Participant"

    companion object {
        private val sharedRooms = java.util.concurrent.ConcurrentHashMap<String, Room>()
        private val roomSubscribers = java.util.concurrent.ConcurrentHashMap<String, MutableSet<InMemoryRealtimeService>>()

        init {
            val preSeeded = listOf(
                Room(
                    id = "room_8x4k",
                    name = "Gaurav's Ambient Session",
                    code = "FM-8X4K",
                    hostId = "p_host",
                    isPublic = true,
                    participants = listOf(
                        Participant("p_host", "Gaurav", "#00F0FF", isHost = true, isListening = true),
                        Participant("p_2", "Alex", "#8B5CF6", isHost = false, isListening = true),
                        Participant("p_3", "Sam", "#EC4899", isHost = false, isListening = false)
                    ),
                    currentTrackId = "trk_1",
                    isPlaying = true
                ),
                Room(
                    id = "room_chll",
                    name = "Neon Night Riders",
                    code = "FM-CHLL",
                    hostId = "p_2",
                    isPublic = true,
                    participants = listOf(
                        Participant("p_2", "Alex", "#8B5CF6", isHost = true, isListening = true),
                        Participant("p_4", "Elena", "#10B981", isHost = false, isListening = true)
                    ),
                    currentTrackId = "trk_2",
                    isPlaying = true
                )
            )
            preSeeded.forEach { room ->
                sharedRooms[room.code] = room
            }
        }

        private fun broadcastToRoom(code: String, updatedRoom: Room) {
            sharedRooms[code] = updatedRoom
            roomSubscribers[code]?.forEach { service ->
                service._activeRoom.value = updatedRoom
            }
        }
    }

    override suspend fun createRoom(name: String, isPublic: Boolean, hostName: String): Result<Room> {
        val randomDigits = (1000..9999).random()
        val code = "FM-$randomDigits"
        val myId = "p_host_${System.currentTimeMillis()}"
        localUserId = myId
        localUserName = hostName
        val newRoom = Room(
            id = "room_${System.currentTimeMillis()}",
            name = name.ifBlank { "$hostName's Room" },
            code = code,
            hostId = myId,
            isPublic = isPublic,
            participants = listOf(
                Participant(myId, hostName, "#00F0FF", isHost = true, isListening = true)
            ),
            currentTrackId = null,
            isPlaying = true
        )
        isLocalHost = true
        currentRoomCode = code
        roomSubscribers.computeIfAbsent(code) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(this)
        _activeRoom.value = newRoom
        sharedRooms[code] = newRoom
        return Result.success(newRoom)
    }

    override suspend fun joinRoom(code: String, participantName: String): Result<Room> {
        val normalizedCode = code.trim().uppercase()
        val existing = sharedRooms[normalizedCode]
        return if (existing != null) {
            isLocalHost = false
            currentRoomCode = normalizedCode
            val myId = localUserId
            localUserName = participantName
            val existingP = existing.participants.find { it.id == myId || it.name == participantName }
            val resolvedId = existingP?.id ?: myId
            localUserId = resolvedId
            val newP = Participant(
                id = resolvedId,
                name = participantName,
                avatarColorHex = existingP?.avatarColorHex ?: "#38BDF8",
                isHost = existingP?.isHost ?: false,
                isListening = true,
                status = com.example.model.MemberStatus.ONLINE
            )
            val updatedParticipants = (existing.participants.filter { it.id != resolvedId } + newP).distinctBy { it.id }
            val updated = existing.copy(
                participants = updatedParticipants
            )
            roomSubscribers.computeIfAbsent(normalizedCode) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(this)
            broadcastToRoom(normalizedCode, updated)
            Result.success(updated)
        } else {
            Result.failure(IllegalArgumentException("Room not found. Check the code and try again."))
        }
    }

    override var onMemberJoinedListener: ((String) -> Unit)? = null
    override fun isHost(): Boolean = isLocalHost

    override suspend fun leaveRoom(): Result<Unit> {
        isLocalHost = false
        currentRoomCode?.let { code ->
            roomSubscribers[code]?.remove(this)
            sharedRooms[code]?.let { current ->
                val updated = current.copy(participants = current.participants.filter { it.id != localUserId && it.name != localUserName })
                broadcastToRoom(code, updated)
            }
        }
        currentRoomCode = null
        _activeRoom.value = null
        return Result.success(Unit)
    }

    override suspend fun endRoom(): Result<Unit> {
        isLocalHost = false
        currentRoomCode?.let { code ->
            roomSubscribers[code]?.forEach { service ->
                service._activeRoom.value = null
            }
            roomSubscribers.remove(code)
            sharedRooms.remove(code)
        }
        currentRoomCode = null
        _activeRoom.value = null
        return Result.success(Unit)
    }

    override suspend fun sendRoomEvent(event: RoomEvent): Result<Unit> = Result.success(Unit)

    override suspend fun syncPlayback(state: RemotePlaybackState): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.success(Unit)
        val existing = sharedRooms[code] ?: _activeRoom.value
        val updated = existing?.copy(
            currentTrackId = state.trackId,
            isPlaying = state.playing,
            playbackPositionMs = state.position,
            currentTrack = state.track ?: existing.currentTrack,
            lastEventTimestamp = state.timestamp,
            sequenceNumber = if (state.sequence > 0L) state.sequence else (existing.sequenceNumber + 1)
        )
        if (updated != null) {
            broadcastToRoom(code, updated)
        }
        return Result.success(Unit)
    }

    override suspend fun addToQueue(track: Track): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.failure(IllegalStateException("Not in a room"))
        val existing = sharedRooms[code] ?: _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val newItem = com.example.model.RoomQueueItem(
            opId = "op_${System.currentTimeMillis()}_${(100..999).random()}",
            track = track,
            addedByUserId = localUserId,
            addedByUserName = localUserName
        )
        val newQueue = existing.sharedQueue + newItem
        val updated = existing.copy(
            sharedQueue = newQueue,
            queueRevision = existing.queueRevision + 1,
            currentTrackId = existing.currentTrackId ?: track.id,
            currentTrack = existing.currentTrack ?: track
        )
        broadcastToRoom(code, updated)
        return Result.success(Unit)
    }

    override suspend fun removeFromQueue(opId: String): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.failure(IllegalStateException("Not in a room"))
        val existing = sharedRooms[code] ?: _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val newQueue = existing.sharedQueue.filterNot { it.opId == opId }
        val updated = existing.copy(
            sharedQueue = newQueue,
            queueRevision = existing.queueRevision + 1
        )
        broadcastToRoom(code, updated)
        return Result.success(Unit)
    }

    override suspend fun reorderQueue(fromIndex: Int, toIndex: Int): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.failure(IllegalStateException("Not in a room"))
        val existing = sharedRooms[code] ?: _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        if (fromIndex !in existing.sharedQueue.indices || toIndex !in existing.sharedQueue.indices) {
            return Result.failure(IllegalArgumentException("Invalid queue indices"))
        }
        val mutable = existing.sharedQueue.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        val updated = existing.copy(
            sharedQueue = mutable,
            queueRevision = existing.queueRevision + 1
        )
        broadcastToRoom(code, updated)
        return Result.success(Unit)
    }

    override suspend fun advanceQueue(): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.failure(IllegalStateException("Not in a room"))
        val existing = sharedRooms[code] ?: _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        if (existing.sharedQueue.isEmpty()) {
            val updated = existing.copy(isPlaying = false)
            broadcastToRoom(code, updated)
            return Result.success(Unit)
        }
        val next = existing.sharedQueue.first()
        val remaining = existing.sharedQueue.drop(1)
        val updated = existing.copy(
            currentTrackId = next.track.id,
            currentTrack = next.track,
            isPlaying = true,
            playbackPositionMs = 0L,
            sharedQueue = remaining,
            queueRevision = existing.queueRevision + 1
        )
        broadcastToRoom(code, updated)
        return Result.success(Unit)
    }

    override suspend fun changeTrack(track: Track, opId: String?): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.failure(IllegalStateException("Not in a room"))
        val existing = sharedRooms[code] ?: _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val updatedQueue = if (opId != null) existing.sharedQueue.filterNot { it.opId == opId } else existing.sharedQueue
        val updated = existing.copy(
            currentTrackId = track.id,
            currentTrack = track,
            isPlaying = true,
            playbackPositionMs = 0L,
            lastEventTimestamp = System.currentTimeMillis(),
            sequenceNumber = existing.sequenceNumber + 1,
            sharedQueue = updatedQueue,
            queueRevision = if (opId != null) existing.queueRevision + 1 else existing.queueRevision
        )
        broadcastToRoom(code, updated)
        return Result.success(Unit)
    }

    override suspend fun sendChatMessage(text: String, sharedTrack: Track?): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.failure(IllegalStateException("Not in a room"))
        val existing = sharedRooms[code] ?: _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val trimmed = text.trim()
        if (trimmed.isBlank() && sharedTrack == null) {
            return Result.failure(IllegalArgumentException("Message cannot be empty"))
        }
        if (trimmed.length > 500) {
            return Result.failure(IllegalArgumentException("Message exceeds 500 characters"))
        }
        val msg = com.example.model.ChatMessage(
            id = "msg_${System.currentTimeMillis()}_${(100..999).random()}",
            senderId = localUserId,
            senderName = localUserName,
            senderAvatarColorHex = if (isHost()) "#00F0FF" else "#38BDF8",
            text = trimmed,
            sharedTrack = sharedTrack
        )
        val updated = existing.copy(
            chatMessages = (existing.chatMessages + msg).takeLast(100)
        )
        broadcastToRoom(code, updated)
        return Result.success(Unit)
    }

    override suspend fun sendReaction(type: String): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.failure(IllegalStateException("Not in a room"))
        val existing = sharedRooms[code] ?: _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val emoji = when (type.uppercase()) {
            "HEART" -> "❤️"
            "FIRE" -> "🔥"
            "LAUGH" -> "😂"
            "WOW" -> "😮"
            "SAD" -> "😢"
            else -> "❤️"
        }
        val rx = com.example.model.LiveReaction(
            id = "rx_${System.currentTimeMillis()}_${(100..999).random()}",
            senderId = localUserId,
            senderName = localUserName,
            type = type.uppercase(),
            emoji = emoji
        )
        val updated = existing.copy(
            recentReactions = (existing.recentReactions + rx).takeLast(10)
        )
        broadcastToRoom(code, updated)
        return Result.success(Unit)
    }


    override suspend fun updateMemberStatus(status: com.example.model.MemberStatus): Result<Unit> {
        val code = currentRoomCode ?: _activeRoom.value?.code ?: return Result.success(Unit)
        val existing = sharedRooms[code] ?: _activeRoom.value ?: return Result.success(Unit)
        val updatedParticipants = existing.participants.map {
            if (it.id == localUserId) it.copy(status = status) else it
        }
        val updated = existing.copy(participants = updatedParticipants)
        broadcastToRoom(code, updated)
        return Result.success(Unit)
    }

    fun testBroadcastRoom(updatedRoom: Room) {
        broadcastToRoom(updatedRoom.code, updatedRoom)
    }
}

