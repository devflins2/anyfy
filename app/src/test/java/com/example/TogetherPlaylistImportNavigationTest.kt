package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room as AndroidRoom
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Artist
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.integrations.InMemoryRealtimeService
import com.example.viewmodel.MainTab
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.SubScreenDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
class TogetherPlaylistImportNavigationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository
    private lateinit var realtimeService: InMemoryRealtimeService
    private lateinit var playbackService: PlaybackService
    private lateinit var viewModel: MainViewModel

    private val testTrack = Track(
        id = "trk_together_1",
        title = "Midnight City",
        artist = "M83",
        artwork = "https://example.com/artwork.jpg",
        durationSeconds = 243
    )

    private val testArtist = Artist(
        id = "art_m83",
        name = "M83",
        artwork = "https://example.com/artist.jpg"
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
        repository.registerTracks(listOf(testTrack))

        realtimeService = InMemoryRealtimeService()
        playbackService = PlaybackService(context)
        viewModel = MainViewModel(repository, playbackService, realtimeService)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testInitialNavigationState_isDefaultHomeWithoutImportOverlay() {
        assertEquals(MainTab.HOME, viewModel.currentTab.value)
        assertFalse(viewModel.isImportPlaylistOpen.value)
        assertNull(viewModel.currentSubScreen.value)
        assertFalse(viewModel.isSettingsOpen.value)
        assertFalse(viewModel.isAdminDashboardOpen.value)
    }

    @Test
    fun testOpenImportPlaylistScreen_updatesFlagsAndPushesToSubScreenStack() {
        viewModel.openImportPlaylistScreen()

        assertTrue("isImportPlaylistOpen should be true", viewModel.isImportPlaylistOpen.value)
        assertEquals(SubScreenDestination.ImportPlaylistScreen, viewModel.currentSubScreen.value)
    }

    @Test
    fun testCloseImportPlaylistScreen_resetsFlagsAndPopsSubScreen() {
        viewModel.openImportPlaylistScreen()
        assertTrue(viewModel.isImportPlaylistOpen.value)

        viewModel.closeImportPlaylistScreen()
        assertFalse("isImportPlaylistOpen should be false after close", viewModel.isImportPlaylistOpen.value)
        assertNull(viewModel.currentSubScreen.value)
    }

    @Test
    fun testNavigateBack_dismissesImportPlaylistScreenGracefully() {
        viewModel.selectTab(MainTab.TOGETHER)
        viewModel.openImportPlaylistScreen()

        assertTrue(viewModel.isImportPlaylistOpen.value)
        assertEquals(SubScreenDestination.ImportPlaylistScreen, viewModel.currentSubScreen.value)

        viewModel.navigateBack()
        assertFalse("Import should be dismissed after navigateBack", viewModel.isImportPlaylistOpen.value)
        assertNull(viewModel.currentSubScreen.value)
        assertEquals(MainTab.TOGETHER, viewModel.currentTab.value)
    }

    @Test
    fun testTogetherListening_openImportThenNavigateToHome_dismissesImport() = runTest {
        // 1. Enter Together Listening session
        viewModel.createRoom("Party Room Alpha", isPublic = true)
        advanceUntilIdle()

        assertNotNull(viewModel.activeRoom.value)
        viewModel.selectTab(MainTab.TOGETHER)
        assertEquals(MainTab.TOGETHER, viewModel.currentTab.value)

        // 2. Open Playlist Import UI from Together Listening
        viewModel.openImportPlaylistScreen()
        assertTrue("Playlist import screen is opened", viewModel.isImportPlaylistOpen.value)
        assertEquals(SubScreenDestination.ImportPlaylistScreen, viewModel.currentSubScreen.value)

        // 3. User navigates away to Home tab via bottom bar
        viewModel.selectTab(MainTab.HOME)

        // Verify Playlist Import is completely dismissed and DOES NOT stick as an overlay
        assertFalse("Playlist import MUST be dismissed when navigating to Home", viewModel.isImportPlaylistOpen.value)
        assertNull("Sub-screen stack must be empty when navigating to Home", viewModel.currentSubScreen.value)
        assertEquals(MainTab.HOME, viewModel.currentTab.value)

        // Verify active party room remains intact in background
        assertNotNull("Together party room remains connected in background", viewModel.activeRoom.value)
        assertEquals("Party Room Alpha", viewModel.activeRoom.value?.name)
    }

    @Test
    fun testTogetherListening_navigatingAcrossAllTabs_ensuresImportScreenNeverSticks() = runTest {
        viewModel.createRoom("Bass Party", isPublic = true)
        advanceUntilIdle()
        viewModel.selectTab(MainTab.TOGETHER)

        val tabs: List<MainTab> = listOf(MainTab.LIBRARY, MainTab.SEARCH, MainTab.HOME, MainTab.TOGETHER)

        for (targetTab in tabs) {
            // Open import in Together room
            viewModel.openImportPlaylistScreen()
            assertTrue(viewModel.isImportPlaylistOpen.value)
            assertEquals(SubScreenDestination.ImportPlaylistScreen, viewModel.currentSubScreen.value)

            // Switch to destination tab
            viewModel.selectTab(targetTab)

            // Verify import screen is never stuck
            assertFalse(
                "Import screen stuck when switching to $targetTab",
                viewModel.isImportPlaylistOpen.value
            )
            assertNull(
                "SubScreen destination not cleared when switching to $targetTab",
                viewModel.currentSubScreen.value
            )
            assertEquals(targetTab, viewModel.currentTab.value)
        }
    }

    @Test
    fun testReopeningImportPlaylist_afterDismissal_worksCleanly() = runTest {
        viewModel.createRoom("Chill Vibes", isPublic = false)
        advanceUntilIdle()
        viewModel.selectTab(MainTab.TOGETHER)

        // Cycle 1: Open and dismiss via tab switch
        viewModel.openImportPlaylistScreen()
        assertTrue(viewModel.isImportPlaylistOpen.value)
        viewModel.selectTab(MainTab.LIBRARY)
        assertFalse(viewModel.isImportPlaylistOpen.value)

        // Cycle 2: Return to Together and re-open
        viewModel.selectTab(MainTab.TOGETHER)
        assertFalse(viewModel.isImportPlaylistOpen.value)
        viewModel.openImportPlaylistScreen()
        assertTrue(viewModel.isImportPlaylistOpen.value)
        assertEquals(SubScreenDestination.ImportPlaylistScreen, viewModel.currentSubScreen.value)

        // Cycle 3: Dismiss via BackHandler
        viewModel.navigateBack()
        assertFalse(viewModel.isImportPlaylistOpen.value)
        assertNull(viewModel.currentSubScreen.value)
        assertEquals(MainTab.TOGETHER, viewModel.currentTab.value)

        // Cycle 4: Re-open again
        viewModel.openImportPlaylistScreen()
        assertTrue(viewModel.isImportPlaylistOpen.value)
        viewModel.closeImportPlaylistScreen()
        assertFalse(viewModel.isImportPlaylistOpen.value)
    }

    @Test
    fun testDirectSubScreenNavigation_dismissesImportPlaylistScreen() {
        viewModel.openImportPlaylistScreen()
        assertTrue(viewModel.isImportPlaylistOpen.value)

        // User navigates directly to an Artist subscreen
        viewModel.navigateToArtist(testArtist)

        assertFalse("Import screen should be closed when opening ArtistScreen", viewModel.isImportPlaylistOpen.value)
        assertTrue(viewModel.currentSubScreen.value is SubScreenDestination.ArtistScreen)

        // Open import again
        viewModel.openImportPlaylistScreen()
        assertTrue(viewModel.isImportPlaylistOpen.value)

        // User opens Settings
        viewModel.openSettings()
        assertFalse("Import screen should be closed when opening Settings", viewModel.isImportPlaylistOpen.value)
        assertTrue(viewModel.isSettingsOpen.value)
    }

    @Test
    fun testRapidTabSwitchingAndImportStressTest() = runTest {
        viewModel.createRoom("Stress Test Room", isPublic = true)
        advanceUntilIdle()

        for (i in 1..25) {
            viewModel.selectTab(MainTab.TOGETHER)
            viewModel.openImportPlaylistScreen()
            assertTrue(viewModel.isImportPlaylistOpen.value)

            val destTab: MainTab = when (i % 4) {
                0 -> MainTab.HOME
                1 -> MainTab.SEARCH
                2 -> MainTab.LIBRARY
                else -> MainTab.TOGETHER
            }

            viewModel.selectTab(destTab)
            assertFalse(
                "Import remained open during iteration $i with dest $destTab",
                viewModel.isImportPlaylistOpen.value
            )
            assertNull(viewModel.currentSubScreen.value)
            assertEquals(destTab, viewModel.currentTab.value)
        }
    }

    @Test
    fun testLeavingTogetherRoom_cleansUpRoomAndState() = runTest {
        viewModel.createRoom("Temporary Party", isPublic = true)
        advanceUntilIdle()
        assertNotNull(viewModel.activeRoom.value)

        viewModel.selectTab(MainTab.TOGETHER)
        viewModel.openImportPlaylistScreen()
        assertTrue(viewModel.isImportPlaylistOpen.value)

        // User leaves room
        viewModel.leaveRoom()
        advanceUntilIdle()

        assertNull("activeRoom should be null after leaving room", viewModel.activeRoom.value)
        // Tab switch back to Home
        viewModel.selectTab(MainTab.HOME)
        assertFalse(viewModel.isImportPlaylistOpen.value)
        assertNull(viewModel.currentSubScreen.value)
    }
}
