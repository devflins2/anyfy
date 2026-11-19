package com.example.service.integrations

import android.util.Log
import com.example.model.Participant
import com.example.model.Room
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class SocketIoRealtimeService(
    private val serverUrl: String,
    private val socketInstance: Socket? = null,
    private val persistentUserIdProvider: (() -> String)? = null
) : RealtimeRoomService {
    private var socket: Socket? = socketInstance
    private val _activeRoom = MutableStateFlow<Room?>(null)
    override val activeRoom: Flow<Room?> = _activeRoom.asStateFlow()

    private val localUserId: String = persistentUserIdProvider?.invoke()?.takeIf { it.isNotBlank() }
        ?: "user_${UUID.randomUUID().toString().replace("-", "").take(8)}"
    private val participantNames = ConcurrentHashMap<String, String>()
    private val participantAvatarColors = ConcurrentHashMap<String, String>()
    private val pendingChatAcks = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    private val isJoining = AtomicBoolean(false)
    private val isCreating = AtomicBoolean(false)
    private var isLocalHost: Boolean = false
    private val eventSequenceCounter = AtomicLong(1L)

    // Internal coroutine scope for background tasks (heartbeat, etc.)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    override var onMemberJoinedListener: ((String) -> Unit)? = null

    override fun isHost(): Boolean {
        return isLocalHost || (_activeRoom.value?.let { room ->
            room.hostId.isNotBlank() && room.hostId == localUserId
        } ?: false)
    }

    @androidx.annotation.VisibleForTesting
    fun setActiveRoomForTesting(room: Room?) {
        _activeRoom.value = room
    }

    @androidx.annotation.VisibleForTesting
    fun getLocalUserIdForTesting(): String = localUserId

    @Volatile
    private var pendingJoinDeferred: CompletableDeferred<Result<Room>>? = null

    @Volatile
    private var pendingJoinCode: String? = null

    @Volatile
    private var pendingCreateDeferred: CompletableDeferred<Result<Room>>? = null

    @Volatile
    private var pendingCreateCode: String? = null

    @Volatile
    private var pendingCreateName: String? = null

    companion object {
        fun normalizeRoomCode(input: String): String {
            if (input.isBlank()) return ""
            var code = input.trim().uppercase()
            if (code.contains("CODE=")) {
                val regex = Regex("""CODE=([^&]+)""", RegexOption.IGNORE_CASE)
                val match = regex.find(code)
                if (match != null) {
                    code = match.groupValues[1]
                }
            }
            code = code.replace("\\s+".toRegex(), "")
            if (code.matches(Regex("""^\d{4,6}$"""))) {
                code = "FM-$code"
            }
            return code
        }
    }

    /** Start emitting room:heartbeat every 15s to keep presence alive on the server. */
    private fun startHeartbeat(roomCode: String) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(15_000L)
                try {
                    if (socket?.connected() != true) break
                    val currentRoom = _activeRoom.value
                    if (currentRoom == null || currentRoom.code.isBlank()) break
                    val hbData = org.json.JSONObject().apply {
                        put("roomId", currentRoom.code)
                        put("code", currentRoom.code)
                        put("userId", localUserId)
                    }
                    socket?.emit("room:heartbeat", hbData)
                    Log.d("SocketIoService", "[HEARTBEAT] Sent heartbeat for room ${currentRoom.code}")
                } catch (e: Throwable) {
                    Log.w("SocketIoService", "Heartbeat emit failed", e)
                }
            }
        }
    }

    /** Cancel the heartbeat loop (on leave or cleanup). */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    init {
        if (socket == null) {
            try {
                socket = IO.socket(serverUrl)
            } catch (e: URISyntaxException) {
                Log.e("SocketIoService", "Invalid socket URL: $serverUrl", e)
            }
        }
        setupSocketListeners()
        try {
            socket?.connect()
        } catch (e: Throwable) {
            Log.e("SocketIoService", "Failed to initiate connect", e)
        }
    }

    private fun setupSocketListeners() {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("SocketIoService", "[DIAG_SYNC] Connected/reconnected to server (userId=$localUserId)")
            val current = _activeRoom.value
            if (current != null && current.code.isNotBlank()) {
                val participantName = participantNames[localUserId]
                    ?: if (isLocalHost) "Host" else "Listener"
                Log.d("SocketIoService", "[DIAG_SYNC] Auto-rejoining room ${current.code} on reconnect (isHost=$isLocalHost)")
                val joinData = JSONObject().apply {
                    put("roomId", current.code)
                    put("code", current.code)
                    put("userId", localUserId)
                    put("participantName", participantName)
                    put("userName", participantName)
                    put("isHost", isLocalHost)
                }
                try {
                    // Emit only once — server handles both aliases
                    socket?.emit("room:join", joinData)
                    _activeRoom.update { currentRoom ->
                        if (currentRoom != null) {
                            val updatedParticipants = currentRoom.participants.map { p ->
                                if (p.id == localUserId) p.copy(status = com.example.model.MemberStatus.ONLINE) else p
                            }
                            currentRoom.copy(participants = updatedParticipants)
                        } else null
                    }
                    val statusData = JSONObject().apply {
                        put("roomId", current.code)
                        put("code", current.code)
                        put("userId", localUserId)
                        put("status", "ONLINE")
                    }
                    socket?.emit("player:status", statusData)
                    startHeartbeat(current.code)

                    if (isLocalHost && current.currentTrackId != null) {
                        val track = current.currentTrack
                        val ts = System.currentTimeMillis()
                        val seq = eventSequenceCounter.getAndIncrement()
                        val syncData = JSONObject().apply {
                            put("roomId", current.code)
                            put("code", current.code)
                            put("hostId", localUserId)
                            put("userId", localUserId)
                            put("trackId", current.currentTrackId)
                            put("position", current.playbackPositionMs)
                            put("isPlaying", current.isPlaying)
                            put("action", "sync")
                            put("timestamp", ts)
                            put("seq", seq)
                            if (track != null) {
                                put("title", track.title)
                                put("artist", track.artist)
                                put("artwork", track.artwork)
                                put("durationSeconds", track.durationSeconds)
                                put("track", JSONObject().apply {
                                    put("id", track.id)
                                    put("title", track.title)
                                    put("artist", track.artist)
                                    put("artwork", track.artwork)
                                    put("durationSeconds", track.durationSeconds)
                                })
                            }
                        }
                        if (current.isPlaying) {
                            socket?.emit("player:play", syncData)
                        } else {
                            socket?.emit("player:pause", syncData)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("SocketIoService", "Error auto-rejoining on connect", e)
                }
            }
        }

        val stateListener = io.socket.emitter.Emitter.Listener { args ->
            try {
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    val parsed = parseRoom(data)
                    _activeRoom.value = parsed

                    val pendingJoin = pendingJoinDeferred
                    if (pendingJoin?.isActive == true) {
                        val expectedCode = pendingJoinCode
                        if (expectedCode == null || parsed.code.equals(expectedCode, ignoreCase = true)) {
                            pendingJoin.complete(Result.success(parsed))
                        }
                    }

                    val pendingCreate = pendingCreateDeferred
                    if (pendingCreate?.isActive == true) {
                        val expectedCode = pendingCreateCode
                        if (expectedCode == null || parsed.code.equals(expectedCode, ignoreCase = true)) {
                            pendingCreate.complete(Result.success(parsed))
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling room state", e)
                pendingJoinDeferred?.takeIf { it.isActive }?.complete(Result.failure(e))
                pendingCreateDeferred?.takeIf { it.isActive }?.complete(Result.failure(e))
            }
        }

        val playbackListener = io.socket.emitter.Emitter.Listener { args ->
            try {
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    val current = _activeRoom.value ?: return@Listener
                    val incomingRoomCode = data.optString("roomId").ifBlank { data.optString("code") }
                    // Prevent cross-room event leaking
                    if (incomingRoomCode.isNotBlank() && !incomingRoomCode.equals(current.code, ignoreCase = true)) {
                        return@Listener
                    }
                    val incomingTimestamp = data.optLong("timestamp", 0L)
                    val incomingSeq = data.optLong("seq", 0L)
                    if (incomingSeq > 0 && current.sequenceNumber > 0 && incomingSeq < current.sequenceNumber) {
                        Log.d("SocketIoService", "[DIAG_SYNC_DROP] Stale playback event dropped (incoming Seq=$incomingSeq < current Seq=${current.sequenceNumber})")
                        return@Listener
                    }
                    if (incomingTimestamp > 0 && current.lastEventTimestamp > incomingTimestamp && (incomingSeq == 0L || incomingSeq <= current.sequenceNumber)) {
                        Log.d("SocketIoService", "[DIAG_SYNC_DROP] Stale playback event dropped (incoming Ts=$incomingTimestamp < current Ts=${current.lastEventTimestamp})")
                        return@Listener
                    }
                    val updated = parseRoom(data)
                    val finalTs = if (incomingTimestamp > 0) incomingTimestamp else System.currentTimeMillis()
                    val finalSeq = if (incomingSeq > 0) incomingSeq else (current.sequenceNumber + 1)
                    val eventId = data.optString("eventId", UUID.randomUUID().toString().take(8))
                    val action = data.optString("action", if (updated.isPlaying) "play" else "pause")
                    Log.d("SocketIoService", "[DIAG_SYNC_IN] EventId=$eventId, Action=$action, Room=${updated.code}, TrackId=${updated.currentTrackId}, Pos=${updated.playbackPositionMs}, Playing=${updated.isPlaying}, Seq=$finalSeq, Ts=$finalTs")
                    _activeRoom.value = updated.copy(
                        lastEventTimestamp = finalTs,
                        sequenceNumber = finalSeq
                    )
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling playback event", e)
            }
        }

        val seekListener = io.socket.emitter.Emitter.Listener { args ->
            try {
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    val current = _activeRoom.value ?: return@Listener
                    val incomingRoomCode = data.optString("roomId").ifBlank { data.optString("code") }
                    // Prevent cross-room event leaking
                    if (incomingRoomCode.isNotBlank() && !incomingRoomCode.equals(current.code, ignoreCase = true)) {
                        return@Listener
                    }
                    val incomingTimestamp = data.optLong("timestamp", 0L)
                    val incomingSeq = data.optLong("seq", 0L)
                    if (incomingSeq > 0 && current.sequenceNumber > 0 && incomingSeq < current.sequenceNumber) {
                        Log.d("SocketIoService", "[DIAG_SYNC_DROP] Stale seek event dropped (incoming Seq=$incomingSeq < current Seq=${current.sequenceNumber})")
                        return@Listener
                    }
                    if (incomingTimestamp > 0 && current.lastEventTimestamp > incomingTimestamp && (incomingSeq == 0L || incomingSeq <= current.sequenceNumber)) {
                        Log.d("SocketIoService", "[DIAG_SYNC_DROP] Stale seek event dropped (incoming Ts=$incomingTimestamp < current Ts=${current.lastEventTimestamp})")
                        return@Listener
                    }
                    val targetPos = when {
                        data.has("position") -> data.optLong("position", 0L)
                        data.has("positionMs") -> data.optLong("positionMs", 0L)
                        data.has("playbackPositionMs") -> data.optLong("playbackPositionMs", 0L)
                        else -> current.playbackPositionMs
                    }
                    val targetTrackId = data.optString("trackId").ifBlank {
                        data.optString("currentTrackId").ifBlank { current.currentTrackId }
                    }
                    val senderId = data.optString("senderId").ifBlank { data.optString("userId") }
                    val eventId = data.optString("eventId", UUID.randomUUID().toString().take(8))
                    val finalTs = if (incomingTimestamp > 0) incomingTimestamp else System.currentTimeMillis()
                    val finalSeq = if (incomingSeq > 0) incomingSeq else (current.sequenceNumber + 1)
                    Log.d("SocketIoService", "[DIAG_SYNC_IN] EventId=$eventId, Action=seek, Room=${current.code}, Pos=$targetPos, Seq=$finalSeq, Ts=$finalTs, Sender=$senderId")
                    _activeRoom.update { c ->
                        c?.copy(
                            currentTrackId = targetTrackId,
                            playbackPositionMs = targetPos,
                            lastEventTimestamp = finalTs,
                            sequenceNumber = finalSeq
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling seek event", e)
            }
        }

        socket?.on("room:state", stateListener)
        socket?.on("room_state", stateListener)
        socket?.on("playback:update", playbackListener)
        socket?.on("playback_update", playbackListener)
        socket?.on("playback:sync", playbackListener)
        socket?.on("sync_playback", playbackListener)
        socket?.on("playback_state", playbackListener)
        socket?.on("player:state", playbackListener)
        socket?.on("player_state", playbackListener)
        socket?.on("player:play", playbackListener)
        socket?.on("player:pause", playbackListener)
        socket?.on("player:seek", seekListener)
        socket?.on("playback:play", playbackListener)
        socket?.on("playback:pause", playbackListener)
        socket?.on("playback:seek", seekListener)
        socket?.on("track:change", playbackListener)
        socket?.on("track_change", playbackListener)
        socket?.on("playback:track_change", playbackListener)

        val errorListener = io.socket.emitter.Emitter.Listener { args ->
            try {
                val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
                val rawMsg = data?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: data?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Room error"
                val formattedMsg = if (rawMsg.contains("not found", ignoreCase = true)) {
                    "Room not found. Check the code and try again."
                } else {
                    rawMsg
                }
                val joinPending = pendingJoinDeferred
                if (joinPending?.isActive == true) {
                    joinPending.complete(Result.failure(IllegalArgumentException(formattedMsg)))
                }

                val createPending = pendingCreateDeferred
                if (createPending?.isActive == true) {
                    createPending.complete(Result.failure(IllegalArgumentException(formattedMsg)))
                }

                // If no join/create is pending and room was previously active, a "not found" error means
                // the server's 30s grace period expired or the room was deleted. Clear room state cleanly.
                if (joinPending == null && createPending == null && _activeRoom.value != null) {
                    if (rawMsg.contains("not found", ignoreCase = true)) {
                        Log.w("SocketIoService", "[TOGETHER_DIAG] Room no longer exists on server after disconnect. Exiting room.")
                        stopHeartbeat()
                        _activeRoom.value = null
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling room error", e)
            }
        }

        socket?.on("room:error", errorListener)
        socket?.on("error", errorListener)

        socket?.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = if (args.isNotEmpty()) args[0]?.toString() else "unknown"
            Log.w("SocketIoService", "[TOGETHER_DIAG] Socket disconnected: reason=$reason")
            // Mark local presence as RECONNECTING rather than clearing the room during brief drops
            _activeRoom.update { current ->
                if (current != null) {
                    val updatedParticipants = current.participants.map { p ->
                        if (p.id == localUserId) p.copy(status = com.example.model.MemberStatus.RECONNECTING) else p
                    }
                    current.copy(participants = updatedParticipants)
                } else null
            }
        }

        socket?.on("room:ended") { args ->
            val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
            val reason = data?.optString("reason") ?: "Host ended party session"
            Log.i("SocketIoService", "[TOGETHER_DIAG] Room ended: $reason")
            stopHeartbeat()
            _activeRoom.value = null
        }

        socket?.on("room:member-joined") { args ->
            try {
                val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
                val joinedUserId = data?.optString("userId")
                val joinedName = data?.optString("name")
                val participantObj = data?.optJSONObject("participant")
                if (!joinedUserId.isNullOrBlank()) {
                    val pName = joinedName?.takeIf { it.isNotBlank() }
                        ?: participantObj?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: participantNames[joinedUserId]
                    if (!pName.isNullOrBlank()) {
                        participantNames[joinedUserId] = pName
                    }
                    val pColor = participantObj?.optString("avatarColorHex")?.takeIf { it.isNotBlank() }
                    if (!pColor.isNullOrBlank()) {
                        participantAvatarColors[joinedUserId] = pColor
                    }
                    _activeRoom.update { current ->
                        if (current != null) {
                            val existingIndex = current.participants.indexOfFirst { it.id == joinedUserId }
                            val newParticipant = Participant(
                                id = joinedUserId,
                                name = pName ?: "Listener ${current.participants.size + 1}",
                                avatarColorHex = pColor ?: participantAvatarColors[joinedUserId] ?: getAvatarColor(current.participants.size),
                                isHost = participantObj?.optBoolean("isHost", false) ?: false,
                                isListening = true,
                                status = com.example.model.MemberStatus.ONLINE
                            )
                            val updatedParticipants = if (existingIndex != -1) {
                                current.participants.toMutableList().apply { set(existingIndex, newParticipant) }
                            } else {
                                current.participants + newParticipant
                            }
                            current.copy(participants = updatedParticipants.distinctBy { it.id })
                        } else null
                    }
                    if (isHost()) {
                        onMemberJoinedListener?.invoke(joinedUserId)
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling member-joined", e)
            }
        }

        socket?.on("room:member-left") { args ->
            try {
                val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
                val leftUserId = data?.optString("userId")
                if (!leftUserId.isNullOrBlank()) {
                    _activeRoom.update { current ->
                        current?.copy(participants = current.participants.filter { it.id != leftUserId })
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling member-left", e)
            }
        }

        socket?.on("queue:update") { args ->
            try {
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    val qJson = data.optJSONArray("sharedQueue")
                    val rev = data.optLong("queueRevision", 0L)
                    if (qJson != null) {
                        val items = mutableListOf<com.example.model.RoomQueueItem>()
                        for (i in 0 until qJson.length()) {
                            val itemObj = qJson.optJSONObject(i) ?: continue
                            val trackObjInQueue = itemObj.optJSONObject("track")
                            val tId = trackObjInQueue?.optString("id") ?: itemObj.optString("trackId", "trk_$i")
                            val track = com.example.model.Track(
                                id = tId,
                                title = trackObjInQueue?.optString("title") ?: itemObj.optString("title", "Track"),
                                artist = trackObjInQueue?.optString("artist") ?: itemObj.optString("artist", "Artist"),
                                artwork = trackObjInQueue?.optString("artwork") ?: itemObj.optString("artwork", ""),
                                durationSeconds = trackObjInQueue?.optInt("durationSeconds", 180) ?: itemObj.optInt("durationSeconds", 180)
                            )
                            items.add(
                                com.example.model.RoomQueueItem(
                                    opId = itemObj.optString("opId", "op_$i"),
                                    track = track,
                                    addedByUserId = itemObj.optString("addedByUserId", ""),
                                    addedByUserName = itemObj.optString("addedByUserName", "Participant"),
                                    addedAtMs = itemObj.optLong("addedAtMs", System.currentTimeMillis())
                                )
                            )
                        }
                        _activeRoom.update { c ->
                            c?.copy(
                                sharedQueue = items,
                                queueRevision = if (rev > 0) rev else (c.queueRevision + 1)
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling queue:update", e)
            }
        }

        socket?.off("chat:error")
        socket?.on("chat:error") { args ->
            val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
            val errMsg = data?.optString("error") ?: "Chat error"
            val msgId = data?.optString("messageId")
            Log.w("SocketIoService", "Received chat:error: $errMsg")
            if (!msgId.isNullOrBlank()) {
                val pending = pendingChatAcks.remove(msgId)
                if (pending?.isActive == true) {
                    pending.complete(Result.failure(IllegalStateException(errMsg)))
                }
            }
        }

        socket?.off("chat:message")
        socket?.on("chat:message") { args ->
            try {
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val msgObj = args[0] as JSONObject
                    val current = _activeRoom.value ?: return@on
                    val msgRoomCode = msgObj.optString("roomId").ifBlank { msgObj.optString("code") }
                    // Prevent cross-room chat message leaking
                    if (msgRoomCode.isNotBlank() && !msgRoomCode.equals(current.code, ignoreCase = true)) {
                        return@on
                    }
                    val msgId = msgObj.optString("id")
                    val sharedTrackObj = msgObj.optJSONObject("sharedTrack")
                    val sharedTrk = if (sharedTrackObj != null) {
                        com.example.model.Track(
                            id = sharedTrackObj.optString("id"),
                            title = sharedTrackObj.optString("title", "Track"),
                            artist = sharedTrackObj.optString("artist", "Artist"),
                            artwork = sharedTrackObj.optString("artwork", ""),
                            durationSeconds = sharedTrackObj.optInt("durationSeconds", 180)
                        )
                    } else null
                    val msg = com.example.model.ChatMessage(
                        id = msgObj.optString("id", UUID.randomUUID().toString()),
                        senderId = msgObj.optString("senderId"),
                        senderName = msgObj.optString("senderName", "User"),
                        senderAvatarColorHex = msgObj.optString("senderAvatarColorHex", "#00F0FF"),
                        text = msgObj.optString("text"),
                        timestampMs = msgObj.optLong("timestampMs", System.currentTimeMillis()),
                        sharedTrack = sharedTrk
                    )
                    _activeRoom.update { c ->
                        if (c != null && c.chatMessages.none { it.id == msg.id }) {
                            c.copy(chatMessages = (c.chatMessages + msg).takeLast(100))
                        } else c
                    }
                    // Immediately acknowledge delivery to sender if waiting on ACK
                    if (msgId.isNotBlank()) {
                        val pending = pendingChatAcks.remove(msgId)
                        if (pending?.isActive == true) {
                            pending.complete(Result.success(Unit))
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling chat:message", e)
            }
        }

        socket?.on("reaction:event") { args ->
            try {
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val rxObj = args[0] as JSONObject
                    val rx = com.example.model.LiveReaction(
                        id = rxObj.optString("id", UUID.randomUUID().toString()),
                        senderId = rxObj.optString("senderId"),
                        senderName = rxObj.optString("senderName", "User"),
                        type = rxObj.optString("type", "HEART"),
                        emoji = rxObj.optString("emoji", "❤️"),
                        timestampMs = rxObj.optLong("timestampMs", System.currentTimeMillis())
                    )
                    _activeRoom.update { c ->
                        if (c != null) {
                            c.copy(recentReactions = (c.recentReactions + rx).takeLast(10))
                        } else c
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling reaction:event", e)
            }
        }

        

        socket?.on("room:member-status") { args ->
            try {
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    val uId = data.optString("userId")
                    val statusStr = data.optString("status", "ONLINE").uppercase()
                    val st = try { com.example.model.MemberStatus.valueOf(statusStr) } catch (_: Throwable) { com.example.model.MemberStatus.ONLINE }
                    val isHostDisconn = data.optBoolean("isHost", false)
                    _activeRoom.update { c ->
                        if (c != null) {
                            val updatedParticipants = c.participants.map {
                                if (it.id == uId) it.copy(status = st) else it
                            }
                            c.copy(
                                participants = updatedParticipants,
                                isHostRecovering = if (isHostDisconn && st == com.example.model.MemberStatus.DISCONNECTED) true else c.isHostRecovering
                            )
                        } else c
                    }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling room:member-status", e)
            }
        }

        socket?.on("room_event") { args ->
            Log.d("SocketIoService", "Received room event: ${args.getOrNull(0)}")
        }

        socket?.on("room:kicked") { _ ->
            stopHeartbeat()
            isLocalHost = false
            _activeRoom.value = null
        }

        socket?.on("chat:deleted") { args ->
            val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
            val msgId = data?.optString("messageId")
            if (!msgId.isNullOrBlank()) {
                _activeRoom.update { c ->
                    c?.copy(chatMessages = c.chatMessages.filterNot { it.id == msgId })
                }
            }
        }

        socket?.on("song_request:update") { args ->
            try {
                val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
                val pendingArray = data?.optJSONArray("pendingRequests")
                if (pendingArray != null) {
                    val requests = mutableListOf<com.example.model.SongRequestItem>()
                    for (i in 0 until pendingArray.length()) {
                        val obj = pendingArray.optJSONObject(i) ?: continue
                        val trkObj = obj.optJSONObject("track")
                        val trk = com.example.model.Track(
                            id = trkObj?.optString("id") ?: "trk_$i",
                            title = trkObj?.optString("title") ?: "Requested Track",
                            artist = trkObj?.optString("artist") ?: "Artist",
                            artwork = trkObj?.optString("artwork") ?: "",
                            durationSeconds = trkObj?.optInt("durationSeconds", 180) ?: 180
                        )
                        requests.add(
                            com.example.model.SongRequestItem(
                                id = obj.optString("requestId", obj.optString("id", "req_$i")),
                                track = trk,
                                requestedByUserId = obj.optString("requestedByUserId"),
                                requestedByUserName = obj.optString("requestedByUserName", "Listener"),
                                timestampMs = obj.optLong("timestampMs", System.currentTimeMillis())
                            )
                        )
                    }
                    _activeRoom.update { it?.copy(pendingSongRequests = requests) }
                }
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Error handling song_request:update", e)
            }
        }

        socket?.on("room:lock_changed") { args ->
            val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
            val locked = data?.optBoolean("isLocked", false) ?: false
            _activeRoom.update { it?.copy(isLocked = locked) }
        }

        socket?.on("room:chat_mute_changed") { args ->
            val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
            val muted = data?.optBoolean("isChatMuted", false) ?: false
            _activeRoom.update { it?.copy(isChatMuted = muted) }
        }

        socket?.on("playback:modes") { args ->
            val data = if (args.isNotEmpty()) args[0] as? JSONObject else null
            val rep = data?.optString("repeatMode", "OFF") ?: "OFF"
            val shuf = data?.optBoolean("shuffleMode", false) ?: false
            _activeRoom.update { it?.copy(repeatMode = rep, shuffleMode = shuf) }
        }
    }

    private fun getAvatarColor(index: Int): String {
        val palette = listOf("#8B5CF6", "#38BDF8", "#EC4899", "#10B981", "#F59E0B", "#6366F1")
        return palette[index % palette.size]
    }

    fun parseRoom(json: JSONObject): Room {
        val currentRoom = _activeRoom.value
        val rawRoomId = when {
            json.has("roomId") && !json.isNull("roomId") && json.optString("roomId").isNotBlank() -> json.optString("roomId")
            json.has("roomCode") && !json.isNull("roomCode") && json.optString("roomCode").isNotBlank() -> json.optString("roomCode")
            json.has("id") && !json.isNull("id") && json.optString("id").isNotBlank() -> json.optString("id")
            else -> currentRoom?.id ?: ""
        }
        val rawCode = when {
            json.has("code") && !json.isNull("code") && json.optString("code").isNotBlank() -> json.optString("code")
            json.has("roomCode") && !json.isNull("roomCode") && json.optString("roomCode").isNotBlank() -> json.optString("roomCode")
            rawRoomId.isNotBlank() -> rawRoomId
            else -> currentRoom?.code ?: ""
        }
        val hostId = when {
            json.has("hostId") && !json.isNull("hostId") && json.optString("hostId").isNotBlank() -> json.optString("hostId")
            else -> currentRoom?.hostId ?: ""
        }
        val roomName = when {
            json.has("name") && !json.isNull("name") && json.optString("name").isNotBlank() -> json.optString("name")
            currentRoom != null && currentRoom.code.equals(rawCode, ignoreCase = true) && currentRoom.name.isNotBlank() -> currentRoom.name
            !pendingCreateName.isNullOrBlank() -> pendingCreateName!!
            rawCode.isNotBlank() -> "$rawCode Room"
            else -> currentRoom?.name ?: "Live Room"
        }

        val participants = mutableListOf<Participant>()
        val participantsJson = json.optJSONArray("participants")
        if (participantsJson != null && participantsJson.length() > 0) {
            for (i in 0 until participantsJson.length()) {
                val p = participantsJson.getJSONObject(i)
                val pId = p.optString("id")
                val pName = p.optString("name")
                val pColor = p.optString("avatarColorHex")
                if (pId.isNotBlank()) {
                    if (pName.isNotBlank() && !pName.startsWith("Listener ")) {
                        participantNames[pId] = pName
                    }
                    if (pColor.isNotBlank()) {
                        participantAvatarColors[pId] = pColor
                    }
                }
                val statusStr = p.optString("status", "ONLINE")
                val memberStatus = try {
                    com.example.model.MemberStatus.valueOf(statusStr.uppercase())
                } catch (e: Exception) {
                    com.example.model.MemberStatus.ONLINE
                }
                participants.add(
                    Participant(
                        id = pId,
                        name = pName.ifBlank { participantNames[pId] ?: "Participant ${i + 1}" },
                        avatarColorHex = pColor.ifBlank { participantAvatarColors[pId] ?: getAvatarColor(i) },
                        isHost = p.optBoolean("isHost", pId == hostId),
                        isListening = p.optBoolean("isListening", true),
                        status = memberStatus
                    )
                )
            }
        } else {
            val membersJson = json.optJSONArray("members")
            val existingMap = currentRoom?.participants?.associateBy { it.id }.orEmpty()
            if (membersJson != null && membersJson.length() > 0) {
                for (i in 0 until membersJson.length()) {
                    val memberId = membersJson.optString(i)
                    if (memberId.isNotBlank()) {
                        val isHost = (memberId == hostId)
                        val existing = existingMap[memberId]
                        val name = existing?.name?.takeIf { !it.startsWith("Listener ") }
                            ?: participantNames[memberId]
                            ?: existing?.name
                            ?: if (isHost) "Host" else "Listener ${i + 1}"
                        val color = existing?.avatarColorHex
                            ?: participantAvatarColors[memberId]
                            ?: if (isHost) "#00F0FF" else getAvatarColor(i)

                        // Preserve resolved name and color in cache so subsequent legacy broadcasts keep them
                        participantNames.putIfAbsent(memberId, name)
                        participantAvatarColors.putIfAbsent(memberId, color)

                        participants.add(
                            Participant(
                                id = memberId,
                                name = name,
                                avatarColorHex = color,
                                isHost = isHost,
                                isListening = true
                            )
                        )
                    }
                }
            } else if (hostId.isNotBlank()) {
                val existingHost = existingMap[hostId]
                val name = existingHost?.name ?: participantNames[hostId] ?: "Host"
                val color = existingHost?.avatarColorHex ?: participantAvatarColors[hostId] ?: "#00F0FF"
                participants.add(
                    Participant(
                        id = hostId,
                        name = name,
                        avatarColorHex = color,
                        isHost = true,
                        isListening = true
                    )
                )
            }
        }

        val finalParticipants = if (participants.isNotEmpty()) {
            participants.distinctBy { it.id.ifBlank { it.name } }
        } else if (currentRoom != null && currentRoom.participants.isNotEmpty()) {
            currentRoom.participants.distinctBy { it.id.ifBlank { it.name } }
        } else if (hostId.isNotBlank()) {
            listOf(Participant(id = hostId, name = participantNames[hostId] ?: "Host", avatarColorHex = "#00F0FF", isHost = true, isListening = true))
        } else {
            emptyList()
        }

        val rawTrackId = when {
            !json.isNull("currentTrackId") && json.optString("currentTrackId").isNotBlank() -> json.optString("currentTrackId")
            !json.isNull("trackId") && json.optString("trackId").isNotBlank() -> json.optString("trackId")
            !json.isNull("track_id") && json.optString("track_id").isNotBlank() -> json.optString("track_id")
            !json.isNull("songId") && json.optString("songId").isNotBlank() -> json.optString("songId")
            !json.isNull("currentSong") -> {
                val song = json.opt("currentSong")
                if (song is JSONObject) song.optString("id") else song?.toString()
            }
            !json.isNull("track") -> {
                val trk = json.opt("track")
                if (trk is JSONObject) trk.optString("id") else trk?.toString()
            }
            else -> currentRoom?.currentTrackId
        }?.takeIf { it.isNotBlank() && it != "null" }

        val isPlaying = when {
            json.has("isPlaying") -> json.optBoolean("isPlaying", false)
            json.has("playing") -> json.optBoolean("playing", false)
            json.has("state") -> json.optString("state").equals("playing", ignoreCase = true)
            else -> currentRoom?.isPlaying ?: false
        }

        val positionMs = when {
            json.has("positionMs") -> json.optLong("positionMs", 0L)
            json.has("position") -> json.optLong("position", 0L)
            json.has("playbackPositionMs") -> json.optLong("playbackPositionMs", 0L)
            json.has("currentTime") -> json.optLong("currentTime", 0L)
            else -> currentRoom?.playbackPositionMs ?: 0L
        }

        val trackObj = json.optJSONObject("track") ?: json.optJSONObject("currentSong")
        val parsedTrack = if (trackObj != null && rawTrackId != null) {
            com.example.model.Track(
                id = rawTrackId,
                title = trackObj.optString("title", "Shared Track"),
                artist = trackObj.optString("artist", "Together Session"),
                artistId = trackObj.optString("artistId", "art_together"),
                album = trackObj.optString("album", "Shared"),
                albumId = trackObj.optString("albumId", "alb_together"),
                artwork = trackObj.optString("artwork", ""),
                durationSeconds = trackObj.optInt("durationSeconds", 180).coerceAtLeast(10),
                audioUrl = null
            )
        } else if (rawTrackId != null && json.has("title")) {
            com.example.model.Track(
                id = rawTrackId,
                title = json.optString("title", "Shared Track"),
                artist = json.optString("artist", "Together Session"),
                artistId = json.optString("artistId", "art_together"),
                album = json.optString("album", "Shared"),
                albumId = json.optString("albumId", "alb_together"),
                artwork = json.optString("artwork", ""),
                durationSeconds = json.optInt("durationSeconds", 180).coerceAtLeast(10),
                audioUrl = null
            )
        } else {
            currentRoom?.currentTrack
        }

        val isPublic = if (json.has("isPublic")) json.optBoolean("isPublic", true) else (currentRoom?.isPublic ?: true)

        val incomingTs = json.optLong("timestamp", 0L)
        val incomingSeq = json.optLong("seq", 0L)
        val finalTs = if (incomingTs > 0L) incomingTs else (currentRoom?.lastEventTimestamp ?: System.currentTimeMillis())
        val finalSeq = if (incomingSeq > 0L) incomingSeq else ((currentRoom?.sequenceNumber ?: 0L) + 1L)

        // Parse sharedQueue
        val sharedQueue = mutableListOf<com.example.model.RoomQueueItem>()
        val queueJson = json.optJSONArray("sharedQueue")
        if (queueJson != null) {
            for (i in 0 until queueJson.length()) {
                val itemObj = queueJson.optJSONObject(i) ?: continue
                val trackObjInQueue = itemObj.optJSONObject("track")
                val tId = trackObjInQueue?.optString("id") ?: itemObj.optString("trackId", "trk_$i")
                val track = com.example.model.Track(
                    id = tId,
                    title = trackObjInQueue?.optString("title") ?: itemObj.optString("title", "Queue Track"),
                    artist = trackObjInQueue?.optString("artist") ?: itemObj.optString("artist", "Artist"),
                    artwork = trackObjInQueue?.optString("artwork") ?: itemObj.optString("artwork", ""),
                    durationSeconds = trackObjInQueue?.optInt("durationSeconds", 180) ?: itemObj.optInt("durationSeconds", 180)
                )
                sharedQueue.add(
                    com.example.model.RoomQueueItem(
                        opId = itemObj.optString("opId", "op_$i"),
                        track = track,
                        addedByUserId = itemObj.optString("addedByUserId", ""),
                        addedByUserName = itemObj.optString("addedByUserName", "Participant"),
                        addedAtMs = itemObj.optLong("addedAtMs", System.currentTimeMillis())
                    )
                )
            }
        } else if (currentRoom != null) {
            sharedQueue.addAll(currentRoom.sharedQueue)
        }

        val queueRevision = json.optLong("queueRevision", currentRoom?.queueRevision ?: 0L)

        // Parse chatMessages — IMPORTANT: server always sends chatMessages: [] (ephemeral, never stored).
        // Preserve local chat history unless the incoming JSON has actual messages.
        val chatMessages = mutableListOf<com.example.model.ChatMessage>()
        val chatJson = json.optJSONArray("chatMessages")
        if (chatJson != null && chatJson.length() > 0) {
            // Server sent non-empty chat array — parse it
            for (i in 0 until chatJson.length()) {
                val msgObj = chatJson.optJSONObject(i) ?: continue
                val sharedTrackObj = msgObj.optJSONObject("sharedTrack")
                val sharedTrk = if (sharedTrackObj != null) {
                    com.example.model.Track(
                        id = sharedTrackObj.optString("id"),
                        title = sharedTrackObj.optString("title", "Track"),
                        artist = sharedTrackObj.optString("artist", "Artist"),
                        artwork = sharedTrackObj.optString("artwork", ""),
                        durationSeconds = sharedTrackObj.optInt("durationSeconds", 180)
                    )
                } else null
                chatMessages.add(
                    com.example.model.ChatMessage(
                        id = msgObj.optString("id", "msg_$i"),
                        senderId = msgObj.optString("senderId"),
                        senderName = msgObj.optString("senderName", "User"),
                        senderAvatarColorHex = msgObj.optString("senderAvatarColorHex", "#00F0FF"),
                        text = msgObj.optString("text"),
                        timestampMs = msgObj.optLong("timestampMs", System.currentTimeMillis()),
                        sharedTrack = sharedTrk
                    )
                )
            }
        } else {
            // Server returned empty chatMessages (expected — ephemeral design).
            // Preserve the local room's existing chat so messages don't vanish on every room:state event.
            currentRoom?.chatMessages?.let { chatMessages.addAll(it) }
        }

        

        val isHostRecovering = json.optBoolean("isHostRecovering", currentRoom?.isHostRecovering ?: false)
        val serverHostName = json.optString("hostName", currentRoom?.hostName ?: "")
        val description = json.optString("description", currentRoom?.description ?: "")
        val visibility = json.optString("visibility", currentRoom?.visibility ?: "PUBLIC")
        val coverUrl = json.optString("coverUrl", currentRoom?.coverUrl ?: "")
        val maxParticipants = json.optInt("maxParticipants", currentRoom?.maxParticipants ?: 20)
        val isLocked = json.optBoolean("isLocked", currentRoom?.isLocked ?: false)
        val isChatMuted = json.optBoolean("isChatMuted", currentRoom?.isChatMuted ?: false)
        val repeatMode = json.optString("repeatMode", currentRoom?.repeatMode ?: "OFF")
        val shuffleMode = json.optBoolean("shuffleMode", currentRoom?.shuffleMode ?: false)

        val pendingRequests = mutableListOf<com.example.model.SongRequestItem>()
        val pendingJson = json.optJSONArray("pendingSongRequests")
        if (pendingJson != null) {
            for (i in 0 until pendingJson.length()) {
                val reqObj = pendingJson.optJSONObject(i) ?: continue
                val trkObj = reqObj.optJSONObject("track")
                val trk = com.example.model.Track(
                    id = trkObj?.optString("id") ?: "trk_$i",
                    title = trkObj?.optString("title") ?: "Requested Track",
                    artist = trkObj?.optString("artist") ?: "Artist",
                    artwork = trkObj?.optString("artwork") ?: "",
                    durationSeconds = trkObj?.optInt("durationSeconds", 180) ?: 180
                )
                pendingRequests.add(
                    com.example.model.SongRequestItem(
                        id = reqObj.optString("requestId", reqObj.optString("id", "req_$i")),
                        track = trk,
                        requestedByUserId = reqObj.optString("requestedByUserId"),
                        requestedByUserName = reqObj.optString("requestedByUserName", "Listener"),
                        timestampMs = reqObj.optLong("timestampMs", System.currentTimeMillis())
                    )
                )
            }
        } else if (currentRoom != null) {
            pendingRequests.addAll(currentRoom.pendingSongRequests)
        }

        return Room(
            id = rawRoomId.ifBlank { currentRoom?.id ?: "room_${System.currentTimeMillis()}" },
            name = roomName,
            code = rawCode,
            hostId = hostId,
            hostName = serverHostName,
            description = description,
            visibility = visibility,
            coverUrl = coverUrl,
            maxParticipants = maxParticipants,
            isLocked = isLocked,
            isChatMuted = isChatMuted,
            repeatMode = repeatMode,
            shuffleMode = shuffleMode,
            isPublic = isPublic,
            participants = finalParticipants,
            currentTrackId = rawTrackId,
            isPlaying = isPlaying,
            playbackPositionMs = positionMs,
            currentTrack = parsedTrack,
            lastEventTimestamp = finalTs,
            sequenceNumber = finalSeq,
            sharedQueue = sharedQueue,
            pendingSongRequests = pendingRequests,
            queueRevision = queueRevision,
            chatMessages = chatMessages,
            recentReactions = currentRoom?.recentReactions ?: emptyList(),
            isHostRecovering = isHostRecovering
        )
    }

    override suspend fun createRoom(name: String, isPublic: Boolean, hostName: String): Result<Room> {
        return createRoomAdvanced(
            name = name,
            description = "",
            visibility = if (isPublic) "PUBLIC" else "PRIVATE",
            coverUrl = "",
            maxParticipants = 20,
            hostName = hostName
        )
    }

    override suspend fun createRoomAdvanced(
        name: String,
        description: String,
        visibility: String,
        coverUrl: String,
        maxParticipants: Int,
        hostName: String
    ): Result<Room> {
        if (!isCreating.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("A room creation request is already in progress."))
        }

        val randomDigits = (1000..9999).random()
        val tempCode = "FM-$randomDigits"
        val roomName = name.ifBlank { "$hostName's Room" }
        val myUserId = localUserId
        participantNames[myUserId] = hostName
        participantNames["host_me"] = hostName
        participantAvatarColors[myUserId] = "#00F0FF"
        participantAvatarColors["host_me"] = "#00F0FF"
        isLocalHost = true

        return try {
            if (socket?.connected() != true) {
                try {
                    socket?.connect()
                } catch (e: Throwable) {
                    Log.e("SocketIoService", "Socket connect failed", e)
                }
                val connected = waitForConnection(3000L)
                if (!connected) {
                    isLocalHost = false
                    _activeRoom.value = null
                    return Result.failure(IllegalStateException("Unable to connect to server. Check your connection and try again."))
                }
            }

            val deferred = CompletableDeferred<Result<Room>>()
            pendingCreateDeferred = deferred
            pendingCreateCode = tempCode
            pendingCreateName = roomName

            val backendData = JSONObject().apply {
                put("roomId", tempCode)
                put("code", tempCode)
                put("name", roomName)
                put("description", description)
                put("visibility", visibility)
                put("coverUrl", coverUrl)
                put("maxParticipants", maxParticipants)
                put("isPublic", visibility == "PUBLIC")
                put("hostName", hostName)
                put("userName", hostName)
                put("userId", myUserId)
            }

            try {
                // Emit with ACK callback for confirmed room creation
                socket?.emit("room:create", backendData, io.socket.client.Ack { args ->
                    try {
                        val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                        val success = res?.optBoolean("success", false) ?: false
                        if (success) {
                            val roomJson = res?.optJSONObject("room")
                            val parsed = if (roomJson != null) {
                                parseRoom(roomJson)
                            } else {
                                Room(
                                    id = "room_${System.currentTimeMillis()}",
                                    name = roomName,
                                    code = tempCode,
                                    hostId = myUserId,
                                    hostName = hostName,
                                    description = description,
                                    visibility = visibility,
                                    coverUrl = coverUrl,
                                    maxParticipants = maxParticipants,
                                    isPublic = visibility == "PUBLIC",
                                    participants = listOf(
                                        Participant(myUserId, hostName, "#00F0FF", isHost = true, isListening = true)
                                    ),
                                    currentTrackId = null,
                                    isPlaying = true,
                                    playbackPositionMs = 0L,
                                    currentTrack = null
                                )
                            }
                            _activeRoom.value = parsed
                            if (deferred.isActive) deferred.complete(Result.success(parsed))
                        } else {
                            val errorMsg = res?.optString("error")?.takeIf { it.isNotBlank() }
                                ?: "Failed to create room."
                            if (deferred.isActive) deferred.complete(Result.failure(IllegalArgumentException(errorMsg)))
                        }
                    } catch (t: Throwable) {
                        if (deferred.isActive) deferred.complete(Result.failure(t))
                    }
                })
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Emit room:create failed", e)
                isLocalHost = false
                return Result.failure(e)
            }

            val res = withTimeout(5000L) {
                deferred.await()
            }
            if (res.isSuccess) {
                val codeToUse = res.getOrNull()?.code ?: tempCode
                startHeartbeat(codeToUse)
            } else {
                isLocalHost = false
                _activeRoom.value = null
            }
            res
        } catch (e: TimeoutCancellationException) {
            val current = _activeRoom.value
            if (current != null && current.code.equals(tempCode, ignoreCase = true)) {
                startHeartbeat(tempCode)
                Result.success(current)
            } else {
                isLocalHost = false
                _activeRoom.value = null
                Result.failure(IllegalStateException("Server did not acknowledge room creation. Please try again."))
            }
        } catch (e: Throwable) {
            isLocalHost = false
            _activeRoom.value = null
            Result.failure(e)
        } finally {
            pendingCreateDeferred = null
            pendingCreateCode = null
            pendingCreateName = null
            isCreating.set(false)
        }
    }

    override suspend fun joinRoom(code: String, participantName: String): Result<Room> {
        val normalizedCode = normalizeRoomCode(code)
        if (normalizedCode.isBlank()) {
            return Result.failure(IllegalArgumentException("Room code cannot be empty."))
        }

        if (!isJoining.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("A join request is already in progress."))
        }
        isLocalHost = false

        return try {
            if (socket?.connected() != true) {
                try {
                    socket?.connect()
                } catch (e: Throwable) {
                    Log.e("SocketIoService", "Socket connect failed", e)
                }
                val connected = waitForConnection(3000L)
                if (!connected) {
                    return Result.failure(IllegalStateException("Unable to connect to server. Check your connection and try again."))
                }
            }

            val deferred = CompletableDeferred<Result<Room>>()
            pendingJoinDeferred = deferred
            pendingJoinCode = normalizedCode

            val myUserId = localUserId
            if (participantName.isNotBlank()) {
                participantNames[myUserId] = participantName
            }

            val data = JSONObject().apply {
                put("roomId", normalizedCode)
                put("code", normalizedCode)
                put("userId", myUserId)
                put("participantName", participantName)
                put("userName", participantName)
            }

            try {
                // Emit with ACK callback for immediate, structured confirmation
                socket?.emit("room:join", data, io.socket.client.Ack { args ->
                    try {
                        val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                        val success = res?.optBoolean("success", false) ?: false
                        if (success) {
                            val roomJson = res?.optJSONObject("room")
                            if (roomJson != null) {
                                val parsed = parseRoom(roomJson)
                                _activeRoom.value = parsed
                                if (deferred.isActive) deferred.complete(Result.success(parsed))
                            }
                        } else {
                            val errorMsg = res?.optString("error")?.takeIf { it.isNotBlank() }
                                ?: "Room not found. Check the code and try again."
                            if (deferred.isActive) deferred.complete(Result.failure(IllegalArgumentException(errorMsg)))
                        }
                    } catch (t: Throwable) {
                        if (deferred.isActive) deferred.complete(Result.failure(t))
                    }
                })
            } catch (e: Throwable) {
                Log.e("SocketIoService", "Emit room:join failed", e)
                return Result.failure(e)
            }

            withTimeout(5000L) {
                deferred.await()
            }.also { result ->
                // Start heartbeat only if join succeeded
                if (result.isSuccess) {
                    startHeartbeat(normalizedCode)
                }
            }
        } catch (e: TimeoutCancellationException) {
            val current = _activeRoom.value
            if (current != null && current.code.equals(normalizedCode, ignoreCase = true)) {
                startHeartbeat(normalizedCode)
                Result.success(current)
            } else {
                Result.failure(IllegalStateException("Room not found. Check the code and try again."))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            pendingJoinDeferred = null
            pendingJoinCode = null
            isJoining.set(false)
        }
    }

    private suspend fun waitForConnection(timeoutMs: Long): Boolean {
        if (socket?.connected() == true) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val connectListener = io.socket.emitter.Emitter.Listener {
                    if (cont.isActive) cont.resume(true)
                }
                val errorListener = io.socket.emitter.Emitter.Listener {
                    if (cont.isActive) cont.resume(false)
                }
                socket?.once(Socket.EVENT_CONNECT, connectListener)
                socket?.once(Socket.EVENT_CONNECT_ERROR, errorListener)
                cont.invokeOnCancellation {
                    socket?.off(Socket.EVENT_CONNECT, connectListener)
                    socket?.off(Socket.EVENT_CONNECT_ERROR, errorListener)
                }
            }
        } ?: (socket?.connected() == true)
    }

    override suspend fun leaveRoom(): Result<Unit> {
        stopHeartbeat()
        isLocalHost = false
        val currentRoom = _activeRoom.value
        val myUserId = localUserId
        val roomCodeToLeave = currentRoom?.code?.ifBlank { pendingJoinCode.orEmpty() } ?: pendingJoinCode.orEmpty()
        try {
            val data = JSONObject().apply {
                if (roomCodeToLeave.isNotBlank()) {
                    put("roomId", roomCodeToLeave)
                    put("code", roomCodeToLeave)
                }
                put("userId", myUserId)
            }
            socket?.emit("room:leave", data)
        } catch (e: Throwable) {
            Log.e("SocketIoService", "Emit leave_room failed", e)
        }
        _activeRoom.value = null
        return Result.success(Unit)
    }

    override suspend fun endRoom(): Result<Unit> {
        stopHeartbeat()
        isLocalHost = false
        val currentRoom = _activeRoom.value
        try {
            val myUserId = localUserId
            val data = JSONObject().apply {
                if (currentRoom != null) {
                    put("roomId", currentRoom.code)
                    put("code", currentRoom.code)
                }
                put("userId", myUserId)
            }
            socket?.emit("room:end", data)
        } catch (e: Throwable) {
            Log.e("SocketIoService", "Emit room:end failed", e)
        }
        _activeRoom.value = null
        return Result.success(Unit)
    }

    override suspend fun sendRoomEvent(event: RoomEvent): Result<Unit> {
        val data = JSONObject().apply {
            put("type", event.type)
            put("payload", event.payload)
        }
        socket?.emit("room_event", data)
        socket?.emit("room:event", data)
        return Result.success(Unit)
    }

    override suspend fun syncPlayback(state: RemotePlaybackState): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.success(Unit)
        val myUserId = localUserId
        val track = state.track
        val seq = if (state.sequence > 0L) state.sequence else eventSequenceCounter.getAndIncrement()
        val ts = if (state.timestamp > 0L) state.timestamp else System.currentTimeMillis()
        val eventId = "ev_${UUID.randomUUID().toString().take(8)}"

        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("hostId", currentRoom.hostId.ifBlank { myUserId })
            put("userId", myUserId)
            put("trackId", state.trackId)
            put("currentTrackId", state.trackId)
            put("position", state.position)
            put("positionMs", state.position)
            put("playbackPositionMs", state.position)
            put("playing", state.playing)
            put("isPlaying", state.playing)
            put("action", state.action)
            put("timestamp", ts)
            put("seq", seq)
            put("eventId", eventId)
            if (track != null) {
                put("title", track.title)
                put("artist", track.artist)
                put("artwork", track.artwork)
                put("durationSeconds", track.durationSeconds)
                put("track", JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("artwork", track.artwork)
                    put("durationSeconds", track.durationSeconds)
                })
            }
        }
        _activeRoom.update {
            it?.copy(
                currentTrackId = state.trackId,
                isPlaying = state.playing,
                playbackPositionMs = state.position,
                currentTrack = state.track ?: it.currentTrack,
                lastEventTimestamp = ts,
                sequenceNumber = seq
            )
        }

        Log.d("SocketIoService", "[DIAG_SYNC_OUT] EventId=$eventId, Action=${state.action}, Room=${currentRoom.code}, HostId=${currentRoom.hostId}, TrackId=${state.trackId}, Pos=${state.position}, Playing=${state.playing}, Seq=$seq, Ts=$ts")

        try {
            if (state.action.equals("seek", ignoreCase = true)) {
                val seekData = JSONObject().apply {
                    put("roomId", currentRoom.code)
                    put("code", currentRoom.code)
                    put("trackId", state.trackId)
                    put("currentTrackId", state.trackId)
                    put("userId", myUserId)
                    put("senderId", myUserId)
                    put("position", state.position)
                    put("positionMs", state.position)
                    put("playbackPositionMs", state.position)
                    put("action", "seek")
                    put("timestamp", ts)
                    put("serverTimestamp", ts)
                    put("seq", seq)
                    put("sequenceNumber", seq)
                    put("eventId", eventId)
                }
                socket?.emit("playback:seek", seekData)
            }
            // Authoritative single sync emission
            socket?.emit("playback:sync", data)
        } catch (e: Throwable) {
            Log.e("SocketIoService", "Emit sync_playback failed", e)
        }
        return Result.success(Unit)
    }

    override suspend fun addToQueue(track: com.example.model.Track): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val myUserId = localUserId
        val opId = "op_${UUID.randomUUID().toString().take(8)}"
        val trackJson = JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("artwork", track.artwork)
            put("durationSeconds", track.durationSeconds)
        }
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", myUserId)
            put("userName", participantNames[myUserId] ?: "Participant")
            put("track", trackJson)
            put("opId", opId)
        }
        return try {
            socket?.emit("queue:add", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun removeFromQueue(opId: String): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val myUserId = localUserId
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", myUserId)
            put("opId", opId)
        }
        return try {
            socket?.emit("queue:remove", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun reorderQueue(fromIndex: Int, toIndex: Int): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val myUserId = localUserId
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", myUserId)
            put("fromIndex", fromIndex)
            put("toIndex", toIndex)
        }
        return try {
            socket?.emit("queue:reorder", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun advanceQueue(): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val myUserId = localUserId
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", myUserId)
        }
        return try {
            socket?.emit("queue:advance", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun changeTrack(track: com.example.model.Track, opId: String?): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val myUserId = localUserId
        val trackJson = JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("artwork", track.artwork)
            put("durationSeconds", track.durationSeconds)
        }
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", myUserId)
            put("senderId", myUserId)
            put("trackId", track.id)
            if (opId != null) put("opId", opId)
            put("track", trackJson)
            put("position", 0L)
            put("positionMs", 0L)
            put("playbackPositionMs", 0L)
            put("timestamp", System.currentTimeMillis())
        }
        return try {
            socket?.emit("track:change", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun sendChatMessage(text: String, sharedTrack: com.example.model.Track?): Result<Unit> {
        val trimmed = text.trim()
        if (trimmed.isBlank() && sharedTrack == null) {
            return Result.failure(IllegalArgumentException("Message cannot be empty"))
        }
        if (trimmed.length > 500) {
            return Result.failure(IllegalArgumentException("Message exceeds 500 characters"))
        }
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))

        val myUserId = localUserId
        val participant = currentRoom.participants.find { it.id == myUserId }
        val senderName = participant?.name ?: participantNames[myUserId] ?: if (isHost()) "Host" else "Listener"
        val avatarColor = participant?.avatarColorHex ?: if (isHost()) "#00F0FF" else "#38BDF8"
        val msgId = "msg_${UUID.randomUUID().toString().take(8)}"
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", myUserId)
            put("senderName", senderName)
            put("senderAvatarColorHex", avatarColor)
            put("messageId", msgId)
            put("text", trimmed)
            if (sharedTrack != null) {
                put("sharedTrack", JSONObject().apply {
                    put("id", sharedTrack.id)
                    put("title", sharedTrack.title)
                    put("artist", sharedTrack.artist)
                    put("artwork", sharedTrack.artwork)
                    put("durationSeconds", sharedTrack.durationSeconds)
                })
            }
        }

        val sock = socket ?: return Result.failure(IllegalStateException("Not connected to server"))
        if (!sock.connected()) {
            return Result.failure(IllegalStateException("Not connected to party server. Check your connection."))
        }

        val deferred = CompletableDeferred<Result<Unit>>()
        pendingChatAcks[msgId] = deferred

        try {
            sock.emit("chat:send", data, io.socket.client.Ack { args ->
                try {
                    val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                    val success = res?.optBoolean("success", true) ?: true
                    if (success) {
                        val msgObj = res?.optJSONObject("message")
                        val deliveredMsg = if (msgObj != null) {
                            val sharedTrackObj = msgObj.optJSONObject("sharedTrack")
                            val sharedTrk = if (sharedTrackObj != null) {
                                com.example.model.Track(
                                    id = sharedTrackObj.optString("id"),
                                    title = sharedTrackObj.optString("title", "Track"),
                                    artist = sharedTrackObj.optString("artist", "Artist"),
                                    artwork = sharedTrackObj.optString("artwork", ""),
                                    durationSeconds = sharedTrackObj.optInt("durationSeconds", 180)
                                )
                            } else sharedTrack
                            com.example.model.ChatMessage(
                                id = msgObj.optString("id", msgId),
                                senderId = msgObj.optString("senderId", myUserId),
                                senderName = msgObj.optString("senderName", senderName),
                                senderAvatarColorHex = msgObj.optString("senderAvatarColorHex", avatarColor),
                                text = msgObj.optString("text", trimmed),
                                timestampMs = msgObj.optLong("timestampMs", System.currentTimeMillis()),
                                sharedTrack = sharedTrk
                            )
                        } else {
                            com.example.model.ChatMessage(
                                id = msgId,
                                senderId = myUserId,
                                senderName = senderName,
                                senderAvatarColorHex = avatarColor,
                                text = trimmed,
                                timestampMs = System.currentTimeMillis(),
                                sharedTrack = sharedTrack
                            )
                        }
                        _activeRoom.update { c ->
                            if (c != null && c.chatMessages.none { it.id == deliveredMsg.id }) {
                                c.copy(chatMessages = (c.chatMessages + deliveredMsg).takeLast(100))
                            } else c
                        }
                        if (deferred.isActive) deferred.complete(Result.success(Unit))
                    } else {
                        val err = res?.optString("error")?.takeIf { it.isNotBlank() } ?: "Failed to send message"
                        if (deferred.isActive) deferred.complete(Result.failure(IllegalStateException(err)))
                    }
                } catch (e: Throwable) {
                    if (deferred.isActive) deferred.complete(Result.failure(e))
                }
            })
        } catch (e: Throwable) {
            pendingChatAcks.remove(msgId)
            return Result.failure(e)
        }

        return try {
            withTimeout(3000L) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            // Check if broadcast already inserted message into room state
            val alreadyDelivered = _activeRoom.value?.chatMessages?.any { it.id == msgId } == true
            if (alreadyDelivered) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Chat message send timed out. Check your connection."))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            pendingChatAcks.remove(msgId)
        }
    }

    override suspend fun sendReaction(type: String): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val myUserId = localUserId
        val rxId = "rx_${UUID.randomUUID().toString().take(8)}"
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", myUserId)
            put("senderName", participantNames[myUserId] ?: "Participant")
            put("reactionId", rxId)
            put("type", type.uppercase())
        }
        return try {
            socket?.emit("reaction:send", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    

    override suspend fun updateMemberStatus(status: com.example.model.MemberStatus): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.success(Unit)
        val myUserId = localUserId
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", myUserId)
            put("status", status.name)
        }
        return try {
            socket?.emit("player:status", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun transferHost(targetUserId: String): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", localUserId)
            put("targetUserId", targetUserId)
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        try {
            socket?.emit("room:transfer_host", data, io.socket.client.Ack { args ->
                val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                if (res?.optBoolean("success", false) == true) {
                    isLocalHost = (targetUserId == localUserId)
                    deferred.complete(Result.success(Unit))
                } else {
                    deferred.complete(Result.failure(IllegalStateException(res?.optString("error") ?: "Host transfer failed")))
                }
            })
            return withTimeout(3000L) { deferred.await() }
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    override suspend fun kickParticipant(targetUserId: String): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", localUserId)
            put("targetUserId", targetUserId)
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        try {
            socket?.emit("room:kick_member", data, io.socket.client.Ack { args ->
                val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                if (res?.optBoolean("success", false) == true) {
                    deferred.complete(Result.success(Unit))
                } else {
                    deferred.complete(Result.failure(IllegalStateException(res?.optString("error") ?: "Kick member failed")))
                }
            })
            return withTimeout(3000L) { deferred.await() }
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    override suspend fun setRoomLock(isLocked: Boolean): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", localUserId)
            put("isLocked", isLocked)
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        try {
            socket?.emit("room:set_lock", data, io.socket.client.Ack { args ->
                val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                if (res?.optBoolean("success", false) == true) {
                    _activeRoom.update { it?.copy(isLocked = isLocked) }
                    deferred.complete(Result.success(Unit))
                } else {
                    deferred.complete(Result.failure(IllegalStateException(res?.optString("error") ?: "Set lock failed")))
                }
            })
            return withTimeout(3000L) { deferred.await() }
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    override suspend fun setChatMuted(isMuted: Boolean): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", localUserId)
            put("isChatMuted", isMuted)
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        try {
            socket?.emit("room:set_chat_muted", data, io.socket.client.Ack { args ->
                val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                if (res?.optBoolean("success", false) == true) {
                    _activeRoom.update { it?.copy(isChatMuted = isMuted) }
                    deferred.complete(Result.success(Unit))
                } else {
                    deferred.complete(Result.failure(IllegalStateException(res?.optString("error") ?: "Set chat mute failed")))
                }
            })
            return withTimeout(3000L) { deferred.await() }
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    override suspend fun deleteChatMessage(messageId: String): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", localUserId)
            put("messageId", messageId)
        }
        _activeRoom.update { c ->
            c?.copy(chatMessages = c.chatMessages.filterNot { it.id == messageId })
        }
        return try {
            socket?.emit("chat:delete", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun submitSongRequest(track: com.example.model.Track): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", localUserId)
            put("userName", participantNames[localUserId] ?: "Listener")
            put("track", JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("artwork", track.artwork)
                put("durationSeconds", track.durationSeconds)
            })
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        try {
            socket?.emit("song_request:submit", data, io.socket.client.Ack { args ->
                val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                if (res?.optBoolean("success", false) == true) {
                    deferred.complete(Result.success(Unit))
                } else {
                    deferred.complete(Result.failure(IllegalStateException(res?.optString("error") ?: "Request failed")))
                }
            })
            return withTimeout(3000L) { deferred.await() }
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    override suspend fun reviewSongRequest(requestId: String, approved: Boolean): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", localUserId)
            put("requestId", requestId)
            put("approved", approved)
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        try {
            socket?.emit("song_request:review", data, io.socket.client.Ack { args ->
                val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                if (res?.optBoolean("success", false) == true) {
                    deferred.complete(Result.success(Unit))
                } else {
                    deferred.complete(Result.failure(IllegalStateException(res?.optString("error") ?: "Review failed")))
                }
            })
            return withTimeout(3000L) { deferred.await() }
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    override suspend fun setPlaybackModes(repeatMode: String, shuffleMode: Boolean): Result<Unit> {
        val currentRoom = _activeRoom.value ?: return Result.failure(IllegalStateException("Not in a room"))
        val data = JSONObject().apply {
            put("roomId", currentRoom.code)
            put("code", currentRoom.code)
            put("userId", localUserId)
            put("repeatMode", repeatMode)
            put("shuffleMode", shuffleMode)
        }
        _activeRoom.update { it?.copy(repeatMode = repeatMode, shuffleMode = shuffleMode) }
        return try {
            socket?.emit("playback:modes", data)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun getPublicRooms(query: String): Result<List<com.example.model.PublicRoomSummary>> {
        val deferred = CompletableDeferred<Result<List<com.example.model.PublicRoomSummary>>>()
        val data = JSONObject().apply {
            put("query", query)
            put("q", query)
            put("limit", 50)
        }
        try {
            socket?.emit("rooms:list_public", data, io.socket.client.Ack { args ->
                val res = if (args.isNotEmpty()) args[0] as? JSONObject else null
                val roomsArr = res?.optJSONArray("rooms")
                if (roomsArr != null) {
                    val list = mutableListOf<com.example.model.PublicRoomSummary>()
                    for (i in 0 until roomsArr.length()) {
                        val obj = roomsArr.optJSONObject(i) ?: continue
                        val trkObj = obj.optJSONObject("currentTrack")
                        val trk = if (trkObj != null) {
                            com.example.model.Track(
                                id = trkObj.optString("id"),
                                title = trkObj.optString("title", "Track"),
                                artist = trkObj.optString("artist", "Artist"),
                                artwork = trkObj.optString("artwork", ""),
                                durationSeconds = trkObj.optInt("durationSeconds", 180)
                            )
                        } else null
                        list.add(
                            com.example.model.PublicRoomSummary(
                                roomId = obj.optString("roomId", obj.optString("code")),
                                code = obj.optString("code"),
                                name = obj.optString("name", "Public Room"),
                                description = obj.optString("description", ""),
                                coverUrl = obj.optString("coverUrl", ""),
                                hostId = obj.optString("hostId"),
                                hostName = obj.optString("hostName", "Host"),
                                participantCount = obj.optInt("participantCount", 1),
                                maxParticipants = obj.optInt("maxParticipants", 20),
                                currentTrack = trk,
                                isPlaying = obj.optBoolean("isPlaying", false),
                                isLocked = obj.optBoolean("isLocked", false)
                            )
                        )
                    }
                    deferred.complete(Result.success(list))
                } else {
                    deferred.complete(Result.success(emptyList()))
                }
            })
            return withTimeout(3500L) { deferred.await() }
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    /**
     * Safely disconnects the socket, removes all event listeners, and purges state.
     */
    fun disconnectAndCleanup() {
        try {
            socket?.off()
            socket?.disconnect()
            socket?.close()
        } catch (e: Throwable) {
            Log.e("SocketIoService", "Error cleaning up socket", e)
        }
        socket = null
        _activeRoom.value = null
    }
}


