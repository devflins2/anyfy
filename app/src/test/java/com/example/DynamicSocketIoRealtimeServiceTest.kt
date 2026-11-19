package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.ServerConfigRepository
import com.example.model.Participant
import com.example.model.Room
import com.example.service.integrations.DynamicSocketIoRealtimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DynamicSocketIoRealtimeServiceTest {

    private lateinit var context: Context
    private lateinit var repository: ServerConfigRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ServerConfigRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository = ServerConfigRepository(context)
    }

    @Test
    fun dynamicService_initializesWithActiveRepositoryUrl() = runTest {
        val childJob = SupervisorJob()
        val testScope = CoroutineScope(childJob + StandardTestDispatcher(testScheduler))
        val dynamicService = DynamicSocketIoRealtimeService(repository, testScope)

        assertEquals("https://socketformusic.onrender.com/", dynamicService.getCurrentEndpointUrl())
        assertFalse(dynamicService.hasPendingSwitch())
        childJob.cancel()
    }

    @Test
    fun dynamicService_defersSwitch_whenUserInActiveRoom() = runTest {
        val childJob = SupervisorJob()
        val testScope = CoroutineScope(childJob + StandardTestDispatcher(testScheduler))
        val dynamicService = DynamicSocketIoRealtimeService(repository, testScope)
        testScheduler.advanceUntilIdle()

        // Simulate user joining an active room
        val fakeRoom = Room(
            id = "room_1",
            name = "Test Room",
            code = "FM-TEST",
            hostId = "host_1",
            isPublic = true,
            participants = listOf(Participant("p1", "User 1", "#00F0FF", isHost = true, isListening = true)),
            currentTrackId = null,
            isPlaying = true
        )

        // Inject active room reflection or mock state to test deferral
        val activeRoomField = DynamicSocketIoRealtimeService::class.java.getDeclaredField("_activeRoom")
        activeRoomField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = activeRoomField.get(dynamicService) as kotlinx.coroutines.flow.MutableStateFlow<Room?>
        stateFlow.value = fakeRoom

        // Admin adds & activates a new server
        repository.addServerLocal(
            name = "Dedicated Host",
            url = "https://dedicated.flinsmusic.com",
            isEnabled = true,
            isActive = true
        )
        testScheduler.advanceUntilIdle()

        // Endpoint switch must be deferred because user is in an active room!
        assertTrue(dynamicService.hasPendingSwitch())
        assertEquals("https://dedicated.flinsmusic.com/", dynamicService.getPendingEndpointUrl())
        // Current endpoint still remains the previous one during the room session
        assertEquals("https://socketformusic.onrender.com/", dynamicService.getCurrentEndpointUrl())

        // User now leaves the room
        dynamicService.leaveRoom()
        testScheduler.advanceUntilIdle()

        // Deferred switch must now be applied cleanly!
        assertEquals("https://dedicated.flinsmusic.com/", dynamicService.getCurrentEndpointUrl())
        assertFalse(dynamicService.hasPendingSwitch())

        childJob.cancel()
    }

}
