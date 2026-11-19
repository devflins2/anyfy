package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.ServerConfigRepository
import com.example.viewmodel.AdminDashboardViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AdminDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var repository: ServerConfigRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ServerConfigRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository = ServerConfigRepository(context, ioDispatcher = testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createMockRepository(
        token: String? = "mock-admin-token",
        ioDispatcher: CoroutineDispatcher = testDispatcher
    ): ServerConfigRepository {
        val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val auth = req.header("Authorization")
            if (auth == null || !auth.startsWith("Bearer mock-admin-token")) {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"success":false,"error":"Authentication required"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else if (req.url.encodedPath == "/api/admin/servers" && req.method == "POST") {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body("""{"success":true,"data":{"id":"srv_authorized","name":"Authorized Node","url":"https://authorized.flinsmusic.com/","isEnabled":true,"isActive":false}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else if (req.url.encodedPath == "/api/admin/servers" && req.method == "GET") {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"success":true,"data":[{"id":"srv_default_render","name":"Render Production Cluster","url":"https://socketformusic.onrender.com/","isEnabled":true,"isActive":true},{"id":"srv_authorized","name":"Authorized Node","url":"https://authorized.flinsmusic.com/","isEnabled":true,"isActive":false}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else if (req.url.encodedPath == "/api/admin/servers/test" && req.method == "POST") {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"success":true,"data":{"status":"Connected","latencyMs":42}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody("application/json".toMediaType())).build()
            }
        }.build()

        return ServerConfigRepository(
            context = context,
            okHttpClient = mockClient,
            tokenProvider = { token },
            ioDispatcher = ioDispatcher
        )
    }

    @Test
    fun isUserAdmin_rejectsNullEmptyAndNonAdminEmails() {
        assertFalse(AdminDashboardViewModel.isUserAdmin(null))
        assertFalse(AdminDashboardViewModel.isUserAdmin(""))
        assertFalse(AdminDashboardViewModel.isUserAdmin("    "))
        assertFalse(AdminDashboardViewModel.isUserAdmin("guest@example.com"))
        assertFalse(AdminDashboardViewModel.isUserAdmin("regular_user@gmail.com"))
        assertFalse(AdminDashboardViewModel.isUserAdmin("admin@otherdomain.com"))
    }

    @Test
    fun isUserAdmin_authorizesAdminAllowlistEmailsCaseInsensitively() {
        assertTrue(AdminDashboardViewModel.isUserAdmin("admin@flinsmusic.com"))
        assertTrue(AdminDashboardViewModel.isUserAdmin("ADMIN@FLINSMUSIC.COM"))
        assertTrue(AdminDashboardViewModel.isUserAdmin("  gaurav@flinsmusic.com  "))
        assertTrue(AdminDashboardViewModel.isUserAdmin("DEVELOPER@FLINSMUSIC.COM"))
        assertTrue(AdminDashboardViewModel.isUserAdmin("gauravkushwaha9901@gmail.com"))
        assertTrue(AdminDashboardViewModel.isUserAdmin("  GAURAVKUSHWAHA9901@GMAIL.COM  "))
    }

    @Test
    fun checkAdminAccess_rejectsGuestUserByDefault() {
        val viewModel = AdminDashboardViewModel(repository)
        // In Robolectric environment with no signed-in FirebaseUser, isAdmin must be FALSE (no guest bypass!)
        assertFalse("Guest user must never be authorized as admin", viewModel.isAdmin.value)
    }

    @Test
    fun mutations_blockedWhenUserIsNotAdmin() = runTest(testDispatcher) {
        val viewModel = AdminDashboardViewModel(repository)
        viewModel.setExplicitAdmin(false)

        val initialCount = viewModel.servers.value.size

        // Attempt to add a server while unauthorized
        viewModel.addServer("Hacker Server", "https://hacker.com", isEnabled = true, isActive = false)
        testScheduler.runCurrent()

        // Mutation must be rejected immediately!
        assertEquals("Server list size must not change when unauthorized", initialCount, viewModel.servers.value.size)
        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("Unauthorized"))

        // After 3000ms delay, auto-dismiss should clear message
        testScheduler.advanceTimeBy(3500)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun mutations_allowedWhenUserIsAdmin() = runTest(testDispatcher) {
        val mockRepo = createMockRepository("mock-admin-token", ioDispatcher = testDispatcher)
        val viewModel = AdminDashboardViewModel(mockRepo)
        viewModel.setExplicitAdmin(true)
        assertTrue(viewModel.isAdmin.value)

        val initialCount = viewModel.servers.value.size

        // Attempt to add a server while authorized
        viewModel.addServer("Authorized Node", "https://authorized.flinsmusic.com", isEnabled = true, isActive = false)
        testScheduler.runCurrent()

        assertEquals("Server list size must increment when authorized", initialCount + 1, viewModel.servers.value.size)
        assertNull(viewModel.errorMessage.value)
        assertNotNull(viewModel.userMessage.value)
        val msg = viewModel.userMessage.value!!
        assertTrue(msg.contains("added successfully") || msg.contains("reached in"))

        // After 3000ms delay, auto-dismiss should clear message
        testScheduler.advanceTimeBy(3500)
        assertNull(viewModel.userMessage.value)
    }

    @Test
    fun mutations_showErrorWhenRemoteBackendFails() = runTest(testDispatcher) {
        val failingClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("""{"success":false,"error":"Database connection failed"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val failingRepo = ServerConfigRepository(
            context = context,
            okHttpClient = failingClient,
            tokenProvider = { "mock-admin-token" },
            ioDispatcher = testDispatcher
        )
        val viewModel = AdminDashboardViewModel(failingRepo)
        viewModel.setExplicitAdmin(true)

        viewModel.addServer("Failing Node", "https://fail.flinsmusic.com", isEnabled = true, isActive = false)
        testScheduler.runCurrent()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("Database connection failed"))

        // After 3000ms delay, auto-dismiss should clear message
        testScheduler.advanceTimeBy(3500)
        assertNull(viewModel.errorMessage.value)
    }
}
