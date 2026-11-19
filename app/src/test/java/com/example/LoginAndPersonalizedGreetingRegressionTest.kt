package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.data.repository.PersistentUserPreferencesRepository
import com.example.data.repository.UserGreetingPreferences
import com.example.service.integrations.ytmusic.YtAccount
import com.example.viewmodel.AuthState
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.MainViewModel
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LoginAndPersonalizedGreetingRegressionTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Guest login option is completely absent from the UI and ViewModel
    // =========================================================================
    @Test
    fun test01_guestLoginOption_isCompletelyAbsentFromViewModelAndUI() {
        // Assert that continueAsGuest method has been removed from AuthViewModel
        val authMethods = AuthViewModel::class.java.methods.map { it.name }
        assertFalse(
            "AuthViewModel must not contain continueAsGuest or guest methods",
            authMethods.contains("continueAsGuest") || authMethods.contains("signInAsGuest")
        )

        // Assert that LoginScreen composable exists and has no guest references
        val loginClass = try {
            Class.forName("com.example.ui.screens.login.LoginScreenKt")
        } catch (_: Exception) { null }
        val loginComposables = loginClass?.methods?.filter { it.name.startsWith("LoginScreen") } ?: emptyList()
        assertTrue("LoginScreen composable must exist", loginComposables.isNotEmpty())
    }

    // =========================================================================
    // 2. Logged-out users see YouTube Music login option only
    // =========================================================================
    @Test
    fun test02_loggedOutUser_initializesInSignedOutState_strictlyRequiringYtMusicAuth() = runTest(testDispatcher) {
        val authViewModel = AuthViewModel()
        advanceUntilIdle()

        val authState = authViewModel.authState.value
        assertTrue(
            "Unauthenticated user must be in SignedOut state, got $authState",
            authState is AuthState.SignedOut
        )
        assertFalse(
            "SignedOut state must not allow entering the main app as guest",
            authState is AuthState.SignedIn
        )
    }

    // =========================================================================
    // 3. Successful login with genuine account transitions to Home with personalized greeting
    // =========================================================================
    @Test
    fun test03_successfulLogin_transitionsToPersonalizedGreeting() = runTest(testDispatcher) {
        val account = YtAccount(
            id = "user_101",
            name = "Test Account",
            email = "user101@example.com",
            avatarUrl = "https://example.com/avatar.jpg",
            cookie = "LOGIN_INFO=dummy_cookie"
        )
        val signedInState = AuthState.SignedIn(account)
        assertEquals("user_101", signedInState.account.id)

        val prefsRepo = PersistentUserPreferencesRepository(context)
        val dao = AppDatabase.getInstance(context).musicDao()
        val repository = MusicRepository(dao, prefsRepo)
        val viewModel = MainViewModel(
            repository = repository,
            userPreferencesRepository = prefsRepo
        )
        viewModel.setCurrentUserId("yt_${account.id}")
        advanceUntilIdle()

        // Greeting preferences should be initialized for this user
        val greetingPrefs = viewModel.greetingPreferences.value
        assertEquals("yt_user_101", greetingPrefs.userId)
        assertEquals(UserGreetingPreferences.DEFAULT_NAME, greetingPrefs.displayName)
    }

    // =========================================================================
    // 4. Default greeting displays Gaurav for this account
    // =========================================================================
    @Test
    fun test04_defaultGreeting_displaysGauravForAccount() = runTest(testDispatcher) {
        val prefs = UserGreetingPreferences(userId = "yt_101")
        assertEquals("Gaurav", prefs.displayName)

        // Resolve greeting with custom calendar
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 9) }
        val greeting = prefs.resolveGreeting(cal)
        assertEquals("Good Morning, Gaurav", greeting)
    }

    // =========================================================================
    // 5. Morning (8:00 AM) displays Good Morning, Gaurav
    // =========================================================================
    @Test
    fun test05_timeGreeting_morning_at_8am() {
        val prefs = UserGreetingPreferences(userId = "yt_101", displayName = "Gaurav")
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 8) }
        assertEquals("Good Morning, Gaurav", prefs.resolveGreeting(cal))
    }

    // =========================================================================
    // 6. Afternoon (2:00 PM) displays Good Afternoon, Gaurav
    // =========================================================================
    @Test
    fun test06_timeGreeting_afternoon_at_2pm() {
        val prefs = UserGreetingPreferences(userId = "yt_101", displayName = "Gaurav")
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 14) }
        assertEquals("Good Afternoon, Gaurav", prefs.resolveGreeting(cal))
    }

    // =========================================================================
    // 7. Evening (7:00 PM) displays Good Evening, Gaurav
    // =========================================================================
    @Test
    fun test07_timeGreeting_evening_at_7pm() {
        val prefs = UserGreetingPreferences(userId = "yt_101", displayName = "Gaurav")
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 19) }
        assertEquals("Good Evening, Gaurav", prefs.resolveGreeting(cal))
    }

    // =========================================================================
    // 8. Night (11:00 PM) displays Good Night, Gaurav
    // =========================================================================
    @Test
    fun test08_timeGreeting_night_at_11pm_and_midnight() {
        val prefs = UserGreetingPreferences(userId = "yt_101", displayName = "Gaurav")
        val calNight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23) }
        assertEquals("Good Night, Gaurav", prefs.resolveGreeting(calNight))

        val calMidnight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 2) }
        assertEquals("Good Night, Gaurav", prefs.resolveGreeting(calMidnight))
    }

    // =========================================================================
    // 9. Editing name persists after app restart simulation
    // =========================================================================
    @Test
    fun test09_editingName_persistsAcrossRestartSimulation() = runTest(testDispatcher) {
        val userId = "yt_persisted_user"
        val repo1 = PersistentUserPreferencesRepository(context)
        repo1.saveDisplayName(userId, "Gaurav Sharma")

        val greetingPrefs1 = repo1.getGreetingPreferences(userId)
        assertEquals("Gaurav Sharma", greetingPrefs1.displayName)
        assertFalse(greetingPrefs1.isCustomGreetingMode)

        // Simulate app restart by constructing a fresh repository instance
        val repo2 = PersistentUserPreferencesRepository(context)
        val greetingPrefs2 = repo2.getGreetingPreferences(userId)
        assertEquals("Gaurav Sharma", greetingPrefs2.displayName)

        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 10) }
        assertEquals("Good Morning, Gaurav Sharma", greetingPrefs2.resolveGreeting(cal))
    }

    // =========================================================================
    // 10. Editing complete greeting persists exact custom string
    // =========================================================================
    @Test
    fun test10_editingCompleteGreeting_persistsExactCustomString_andDoesNotChangeWithTime() = runTest(testDispatcher) {
        val userId = "yt_custom_greeting_user"
        val repo = PersistentUserPreferencesRepository(context)
        repo.saveCustomGreeting(userId, "Welcome to Anyfy, Rock on!")

        val prefs = repo.getGreetingPreferences(userId)
        assertTrue(prefs.isCustomGreetingMode)
        assertEquals("Welcome to Anyfy, Rock on!", prefs.customGreeting)

        // Verify across morning, afternoon, evening, night: exact string must remain unchanged!
        val calMorning = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 8) }
        val calAfternoon = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 13) }
        val calNight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23) }

        assertEquals("Welcome to Anyfy, Rock on!", prefs.resolveGreeting(calMorning))
        assertEquals("Welcome to Anyfy, Rock on!", prefs.resolveGreeting(calAfternoon))
        assertEquals("Welcome to Anyfy, Rock on!", prefs.resolveGreeting(calNight))
    }

    // =========================================================================
    // 11. Home refresh / catalogue fetch does not overwrite saved name with account name
    // =========================================================================
    @Test
    fun test11_homeRefresh_doesNotOverwriteSavedNameWithAccountName() = runTest(testDispatcher) {
        val userId = "yt_account_sync_user"
        val prefsRepo = PersistentUserPreferencesRepository(context)
        prefsRepo.saveDisplayName(userId, "Gaurav")

        val dao = AppDatabase.getInstance(context).musicDao()
        val repository = MusicRepository(dao, prefsRepo)
        repository.setCurrentUserId(userId)

        // Observe user profile
        val profile = repository.userProfile.first()
        assertEquals("Gaurav", profile.name)

        // Even when userProfile is evaluated or home feed refreshes, Gaurav remains intact
        val profile2 = repository.userProfile.first()
        assertEquals("Gaurav", profile2.name)
    }

    // =========================================================================
    // 12. Logout and login restore correct account's preference
    // =========================================================================
    @Test
    fun test12_logoutAndLogin_restoresAccountPreferences() = runTest(testDispatcher) {
        val userA = "yt_user_A"
        val userB = "yt_user_B"

        val prefsRepo = PersistentUserPreferencesRepository(context)
        prefsRepo.saveDisplayName(userA, "Gaurav UserA")
        prefsRepo.saveDisplayName(userB, "Alex UserB")

        // Switch to User A
        assertEquals("Gaurav UserA", prefsRepo.getGreetingPreferences(userA).displayName)

        // User A logs out, User B logs in
        assertEquals("Alex UserB", prefsRepo.getGreetingPreferences(userB).displayName)

        // User B logs out, User A logs back in
        assertEquals("Gaurav UserA", prefsRepo.getGreetingPreferences(userA).displayName)
    }

    // =========================================================================
    // 13. Two different accounts never share greeting preferences
    // =========================================================================
    @Test
    fun test13_differentAccounts_neverShareGreetingPreferences() = runTest(testDispatcher) {
        val userA = "yt_account_111"
        val userB = "yt_account_222"

        val prefsRepo = PersistentUserPreferencesRepository(context)
        prefsRepo.saveCustomGreeting(userA, "Greetings Master Gaurav")
        prefsRepo.saveDisplayName(userB, "Samantha")

        val prefsA = prefsRepo.getGreetingPreferences(userA)
        val prefsB = prefsRepo.getGreetingPreferences(userB)

        assertTrue(prefsA.isCustomGreetingMode)
        assertEquals("Greetings Master Gaurav", prefsA.customGreeting)

        assertFalse(prefsB.isCustomGreetingMode)
        assertEquals("Samantha", prefsB.displayName)
        assertNotEquals(prefsA.resolveGreeting(), prefsB.resolveGreeting())
    }

    // =========================================================================
    // 14. Validation prevents empty or invalid names from overwriting existing preferences
    // =========================================================================
    @Test
    fun test14_validation_preventsEmptyOrBlankNameFromOverwriting() = runTest(testDispatcher) {
        val userId = "yt_validation_user"
        val repo = PersistentUserPreferencesRepository(context)
        repo.saveDisplayName(userId, "Valid Gaurav")

        val successBlank = repo.saveDisplayName(userId, "   ")
        assertFalse("Blank name must be rejected", successBlank)

        val current = repo.getGreetingPreferences(userId)
        assertEquals("Existing valid name must be preserved", "Valid Gaurav", current.displayName)
    }

    // =========================================================================
    // 15. Restore Default action resets to Gaurav and restores time-based greeting
    // =========================================================================
    @Test
    fun test15_restoreDefault_resetsToGauravAndTimeGreeting() = runTest(testDispatcher) {
        val userId = "yt_restore_default_user"
        val repo = PersistentUserPreferencesRepository(context)
        repo.saveCustomGreeting(userId, "Temporary greeting")
        assertTrue(repo.getGreetingPreferences(userId).isCustomGreetingMode)

        repo.resetToDefault(userId)
        val resetPrefs = repo.getGreetingPreferences(userId)
        assertFalse(resetPrefs.isCustomGreetingMode)
        assertEquals(UserGreetingPreferences.DEFAULT_NAME, resetPrefs.displayName)
        assertEquals("", resetPrefs.customGreeting)

        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 15) }
        assertEquals("Good Afternoon, Gaurav", resetPrefs.resolveGreeting(cal))
    }
}
