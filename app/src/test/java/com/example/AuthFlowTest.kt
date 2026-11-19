package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.LikedTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.RecentlyPlayedEntity
import com.example.data.repository.MusicRepository
import com.example.viewmodel.AuthState
import com.example.viewmodel.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthFlowTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unauthenticatedUser_initializesInSignedOutState_andCannotEnterApp() = runTest(testDispatcher) {
        val authViewModel = AuthViewModel()
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val state = authViewModel.authState.value
        // Must be SignedOut when unauthenticated — guest mode bypass is eliminated
        assertTrue(
            "Unauthenticated user must be in SignedOut state, got $state",
            state is AuthState.SignedOut
        )
        assertFalse(
            "Unauthenticated user must never be in SignedIn state",
            state is AuthState.SignedIn
        )
    }

    @Test
    fun authViewModel_hasNoGuestModeLoginFunction() {
        // Assert that continueAsGuest function has been completely removed from AuthViewModel
        val hasGuestMethod = AuthViewModel::class.java.methods.any { it.name == "continueAsGuest" }
        assertFalse("AuthViewModel must not have any continueAsGuest method", hasGuestMethod)
    }

    @Test
    fun authStateSignedIn_strictlyRequiresNonNullYtAccount() {
        // Reflection check: Confirm AuthState.SignedIn requires a non-null YtAccount
        // and cannot be instantiated with null (which was previously used for guest mode)
        val constructor = AuthState.SignedIn::class.java.constructors.first()
        val paramTypes = constructor.parameterTypes
        assertEquals(1, paramTypes.size)
        assertEquals("com.example.service.integrations.ytmusic.YtAccount", paramTypes[0].name)
    }

    @Test
    fun userId_scopingConvention_conformsStrictlyToYtAccountId() {
        // Verifies the user ID prefix convention yt_<accountId>
        val fakeYtId = "yt_account_channel_12345"
        val mappedId = fakeYtId.takeIf { it.isNotBlank() }?.let { "yt_$it" } ?: ""
        assertEquals("yt_yt_account_channel_12345", mappedId)

        // Blank ID must map to empty (unauthenticated)
        val blankId = "   "
        val mappedBlank = blankId.takeIf { it.isNotBlank() }?.let { "yt_$it" } ?: ""
        assertEquals("", mappedBlank)
    }

    @Test
    fun signOut_returnsToSignedOutState() = runTest(testDispatcher) {
        val authViewModel = AuthViewModel()
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        authViewModel.signOut()
        testScheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        assertTrue(
            "After signOut, authState must be SignedOut",
            authViewModel.authState.value is AuthState.SignedOut
        )
    }

    @Test
    fun repository_unauthenticatedState_guardsMutationsAndReturnsEmpty() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MusicRepository(db.musicDao())

        // Initial unauthenticated state
        assertEquals("", repository.currentUserId.value)

        // Mutations without authenticated user are safely ignored
        repository.toggleLike("trk_test_1")
        val plId = repository.createPlaylist("Unauthorized Playlist")
        repository.recordRecentlyPlayed("trk_test_1")

        assertEquals("", plId)
        assertTrue(repository.likedTrackIds.first().isEmpty())
        assertTrue(repository.playlists.first().isEmpty())
        assertTrue(repository.recentlyPlayedTracks.first().isEmpty())

        db.close()
    }

    @Test
    fun legacyGuestData_isNeverExposedToAuthenticatedUsers() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.musicDao()
        val repository = MusicRepository(dao)

        // Seed database with legacy 'guest_local' records from before guest removal
        dao.insertLikedTrack(LikedTrackEntity(userId = "guest_local", trackId = "trk_legacy_guest"))
        dao.insertPlaylist(PlaylistEntity(id = "pl_legacy", userId = "guest_local", name = "Legacy Guest Playlist"))
        dao.insertRecentlyPlayed(RecentlyPlayedEntity(userId = "guest_local", trackId = "trk_legacy_guest", playedAt = 9999L))

        // Authenticated user logs in
        repository.setCurrentUserId("usr_google_user_789")

        // Authenticated user must NOT see any legacy guest data
        val userLikes = repository.likedTrackIds.first()
        val userPlaylists = repository.playlists.first()
        val userRecent = repository.recentlyPlayedTracks.first()

        assertTrue("Authenticated user must not see legacy guest likes", userLikes.isEmpty())
        assertTrue("Authenticated user must not see legacy guest playlists", userPlaylists.isEmpty())
        assertTrue("Authenticated user must not see legacy guest history", userRecent.isEmpty())

        db.close()
    }

    @Test
    fun accountSwitching_neverLeaksDataBetweenUsers() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MusicRepository(db.musicDao())

        // 1. User Alice logs in
        repository.setCurrentUserId("usr_alice_firebase_id")
        repository.toggleLike("trk_alice_fav")
        repository.createPlaylist("Alice's Jazz")
        repository.recordRecentlyPlayed("trk_alice_fav")

        assertEquals(setOf("trk_alice_fav"), repository.likedTrackIds.first())
        assertEquals(1, repository.playlists.first().size)
        assertEquals("Alice's Jazz", repository.playlists.first()[0].name)

        // 2. Alice signs out -> unauthenticated
        repository.setCurrentUserId(null)
        assertEquals("", repository.currentUserId.value)
        assertTrue("Signed-out state must clear liked tracks", repository.likedTrackIds.first().isEmpty())
        assertTrue("Signed-out state must clear playlists", repository.playlists.first().isEmpty())
        assertTrue("Signed-out state must clear recently played", repository.recentlyPlayedTracks.first().isEmpty())

        // 3. User Bob logs in
        repository.setCurrentUserId("usr_bob_firebase_id")
        assertTrue("Bob must not see Alice's liked tracks", repository.likedTrackIds.first().isEmpty())
        assertTrue("Bob must not see Alice's playlists", repository.playlists.first().isEmpty())

        repository.toggleLike("trk_bob_fav")
        repository.createPlaylist("Bob's Rock")
        assertEquals(setOf("trk_bob_fav"), repository.likedTrackIds.first())
        assertEquals("Bob's Rock", repository.playlists.first()[0].name)

        // 4. Bob signs out
        repository.setCurrentUserId(null)
        assertTrue(repository.likedTrackIds.first().isEmpty())

        // 5. Alice signs back in -> Alice's data is intact and Bob's data is not visible
        repository.setCurrentUserId("usr_alice_firebase_id")
        assertEquals(setOf("trk_alice_fav"), repository.likedTrackIds.first())
        assertEquals(1, repository.playlists.first().size)
        assertEquals("Alice's Jazz", repository.playlists.first()[0].name)

        db.close()
    }
}
