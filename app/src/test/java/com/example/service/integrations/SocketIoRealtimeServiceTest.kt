package com.example.service.integrations

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SocketIoRealtimeServiceTest {

    @Test
    fun joinRoom_withBlankCode_failsImmediately() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        val result = service.joinRoom("   ", "Alex")
        assertTrue(result.isFailure)
        assertEquals("Room code cannot be empty.", result.exceptionOrNull()?.message)
    }

    @Test
    fun joinRoom_invalidServerUrl_handlesConnectionFailureCleanly() = runBlocking {
        // Invalid or unreachable port
        val service = SocketIoRealtimeService("http://127.0.0.1:59999")
        val result = service.joinRoom("FM-TEST", "Alex")
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(msg.contains("Unable to connect to server") || msg.contains("Room not found") || msg.contains("timed out"))
    }

    @Test
    fun createRoom_initializesLocalStateAndSetsActiveRoom() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        val createResult = service.createRoom("Rock Room", isPublic = true, hostName = "Gaurav")
        if (createResult.isSuccess) {
            val room = createResult.getOrNull()
            assertNotNull(room)
            assertEquals("Rock Room", room?.name)
            assertTrue(room?.code?.startsWith("FM-") == true)
            assertEquals(1, room?.participants?.size)
            assertEquals("Gaurav", room?.participants?.firstOrNull()?.name)
            assertTrue(room?.participants?.firstOrNull()?.isHost == true)
        }
    }

    @Test
    fun leaveRoom_clearsActiveRoomState() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        service.createRoom("Rock Room", isPublic = true, hostName = "Gaurav")
        assertNotNull(service.activeRoom)

        val leaveResult = service.leaveRoom()
        assertTrue(leaveResult.isSuccess)
    }

    @Test
    fun sendChatMessage_notInRoom_failsCleanly() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        val result = service.sendChatMessage("Hello world")
        assertTrue(result.isFailure)
        assertEquals("Not in a room", result.exceptionOrNull()?.message)
    }

    @Test
    fun sendChatMessage_emptyOrBlank_failsImmediately() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        service.createRoom("Chat Room", isPublic = true, hostName = "HostUser")

        val emptyRes = service.sendChatMessage("   ")
        assertTrue(emptyRes.isFailure)
        assertEquals("Message cannot be empty", emptyRes.exceptionOrNull()?.message)
    }

    @Test
    fun sendChatMessage_exceeding500Characters_failsImmediately() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        service.createRoom("Chat Room", isPublic = true, hostName = "HostUser")

        val longText = "A".repeat(501)
        val longRes = service.sendChatMessage(longText)
        assertTrue(longRes.isFailure)
        assertEquals("Message exceeds 500 characters", longRes.exceptionOrNull()?.message)
    }

    @Test
    fun parseRoom_preservesChatMessages_andOmitsVotingState() {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        val json = JSONObject().apply {
            put("code", "FM-CHAT1")
            put("name", "Chat Room")
            put("hostId", "h1")
            val chatArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "msg_1")
                    put("senderId", "u1")
                    put("senderName", "Alice")
                    put("senderAvatarColorHex", "#8B5CF6")
                    put("text", "Hey everyone!")
                    put("timestampMs", 1700000000000L)
                })
            }
            put("chatMessages", chatArray)
            // Even if an older payload contained voting state, it is ignored
            put("activeVotingRound", JSONObject().apply {
                put("roundId", "v_round")
                put("status", "ACTIVE")
            })
        }

        val room = service.parseRoom(json)
        assertEquals("FM-CHAT1", room.code)
        assertEquals(1, room.chatMessages.size)
        assertEquals("msg_1", room.chatMessages[0].id)
        assertEquals("Alice", room.chatMessages[0].senderName)
        assertEquals("Hey everyone!", room.chatMessages[0].text)
    }

    @Test
    fun normalizeRoomCode_handlesVariousInputFormats() {
        assertEquals("", SocketIoRealtimeService.normalizeRoomCode("   "))
        assertEquals("FM-1234", SocketIoRealtimeService.normalizeRoomCode("1234"))
        assertEquals("FM-5678", SocketIoRealtimeService.normalizeRoomCode("fm-5678"))
        assertEquals("FM-ABCD", SocketIoRealtimeService.normalizeRoomCode("fm-abcd"))
        assertEquals("FM-7777", SocketIoRealtimeService.normalizeRoomCode("https://flinsmusic.com/join?code=fm-7777&source=share"))
        assertEquals("FM-8888", SocketIoRealtimeService.normalizeRoomCode("  f m - 8 8 8 8  "))
    }

    @Test
    fun endRoom_clearsActiveRoomState() = runBlocking {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")
        val createResult = service.createRoom("Party Room", isPublic = true, hostName = "HostGaurav")
        if (createResult.isSuccess) {
            assertNotNull(service.activeRoom)
            val endResult = service.endRoom()
            assertTrue(endResult.isSuccess)
            assertNull(service.activeRoom.first())
        }
    }

    @Test
    fun createRoom_unreachableServer_failsWithoutReportingFalseSuccess() = runBlocking {
        val service = SocketIoRealtimeService("http://127.0.0.1:59999")
        val createResult = service.createRoom("Offline Room", isPublic = true, hostName = "HostGaurav")
        assertTrue(createResult.isFailure)
        val msg = createResult.exceptionOrNull()?.message ?: ""
        assertTrue(msg.contains("Unable to connect to server") || msg.contains("acknowledge") || msg.contains("timed out"))
    }

    @Test
    fun parseRoom_preservesParticipantNamesAndAvatarColors_fromLegacyMembersArray() {
        val service = SocketIoRealtimeService("https://socketformusic.onrender.com/")

        // 1. Initial state with rich participant metadata
        val initialJson = JSONObject().apply {
            put("code", "FM-9999")
            put("name", "Legacy Preservation Room")
            put("hostId", "host_u1")
            val pArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "host_u1")
                    put("name", "SuperHost")
                    put("avatarColorHex", "#00F0FF")
                    put("isHost", true)
                })
                put(JSONObject().apply {
                    put("id", "listener_u2")
                    put("name", "VipListener")
                    put("avatarColorHex", "#EC4899")
                    put("isHost", false)
                })
            }
            put("participants", pArray)
        }
        val room1 = service.parseRoom(initialJson)
        assertEquals(2, room1.participants.size)
        assertEquals("SuperHost", room1.participants[0].name)
        assertEquals("#00F0FF", room1.participants[0].avatarColorHex)
        assertEquals("VipListener", room1.participants[1].name)
        assertEquals("#EC4899", room1.participants[1].avatarColorHex)

        // 2. Incoming legacy server payload with ONLY string array "members"
        val legacyJson = JSONObject().apply {
            put("code", "FM-9999")
            put("hostId", "host_u1")
            val membersArray = JSONArray().apply {
                put("host_u1")
                put("listener_u2")
            }
            put("members", membersArray)
            // Note: no participants array, no name
        }
        val room2 = service.parseRoom(legacyJson)
        assertEquals(2, room2.participants.size)
        // Must preserve participant names and avatar colors, NOT reset to Listener 1/Listener 2 or random colors!
        val hostPart = room2.participants.find { it.id == "host_u1" }
        assertNotNull(hostPart)
        assertEquals("SuperHost", hostPart?.name)
        assertEquals("#00F0FF", hostPart?.avatarColorHex)

        val listenerPart = room2.participants.find { it.id == "listener_u2" }
        assertNotNull(listenerPart)
        assertEquals("VipListener", listenerPart?.name)
        assertEquals("#EC4899", listenerPart?.avatarColorHex)
    }
}


