package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.ServerConfigRepository
import com.example.model.ServerConnectionStatus
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerConfigRepositoryTest {

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
    fun initialActiveServer_defaultsToRenderProductionCluster() {
        val active = repository.activeServer.value
        assertNotNull(active)
        assertEquals("https://socketformusic.onrender.com/", active.url)
        assertEquals("Render Production Cluster", active.name)
        assertTrue(active.isActive)
        assertTrue(active.isEnabled)
    }

    @Test
    fun ssrfValidation_rejectsInsecureAndPrivateEndpoints() {
        // Insecure scheme
        val (httpValid, httpErr) = repository.validateServerUrlClient("http://my-insecure-server.com")
        assertFalse(httpValid)
        assertNotNull(httpErr)

        // Localhost
        val (localValid, localErr) = repository.validateServerUrlClient("https://localhost:8080")
        assertFalse(localValid)
        assertNotNull(localErr)

        // Loopback IP
        val (loopValid, _) = repository.validateServerUrlClient("https://127.0.0.1:443")
        assertFalse(loopValid)

        // Private 10.0.0.0/8 range
        val (priv10Valid, _) = repository.validateServerUrlClient("https://10.0.1.5:3000")
        assertFalse(priv10Valid)

        // Private 192.168.0.0/16 range
        val (priv192Valid, _) = repository.validateServerUrlClient("https://192.168.1.50")
        assertFalse(priv192Valid)

        // AWS/Cloud metadata
        val (metaValid, _) = repository.validateServerUrlClient("https://169.254.169.254")
        assertFalse(metaValid)

        // Valid public HTTPS endpoint
        val (publicHttpsValid, publicErr) = repository.validateServerUrlClient("https://socketformusic.onrender.com")
        assertTrue(publicHttpsValid)
        assertEquals(null, publicErr)

        // Valid public WSS endpoint
        val (publicWssValid, _) = repository.validateServerUrlClient("wss://socket.flinsmusic.com")
        assertTrue(publicWssValid)
    }

    @Test
    fun addServer_addsToServersList_andPersistsInPrefs() {
        val result = repository.addServerLocal(
            name = "Secondary EU Cluster",
            url = "https://eu-socket.flinsmusic.com",
            isEnabled = true,
            isActive = false
        )

        assertTrue(result.isSuccess)
        val server = result.getOrNull()
        assertNotNull(server)
        assertEquals("Secondary EU Cluster", server?.name)
        assertEquals("https://eu-socket.flinsmusic.com/", server?.url)

        val all = repository.servers.value
        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "Secondary EU Cluster" })

        // Check persistent SharedPreferences
        val prefs = context.getSharedPreferences(ServerConfigRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(ServerConfigRepository.KEY_CACHED_SERVERS, "")
        assertNotNull(jsonStr)
        assertTrue(jsonStr!!.contains("Secondary EU Cluster"))
    }

    @Test
    fun setActiveServer_switchesActiveCluster() {
        val addResult = repository.addServerLocal(
            name = "Asia-South Fast Cluster",
            url = "https://in-socket.flinsmusic.com",
            isEnabled = true,
            isActive = false
        )
        val newServer = addResult.getOrThrow()

        val activateResult = repository.setActiveServer(newServer.id)
        assertTrue(activateResult.isSuccess)

        val active = repository.activeServer.value
        assertEquals(newServer.id, active.id)
        assertEquals("https://in-socket.flinsmusic.com/", active.url)
        assertTrue(active.isActive)

        // Original default must now have isActive = false
        val original = repository.servers.value.find { it.name == "Render Production Cluster" }
        assertNotNull(original)
        assertFalse(original!!.isActive)
    }

    @Test
    fun deleteServer_cannotDeleteActiveOrOnlyServer() {
        // Attempting to delete the only server must fail
        val onlyServer = repository.servers.value.first()
        val deleteOnlyResult = repository.deleteServer(onlyServer.id)
        assertFalse(deleteOnlyResult.isSuccess)

        // Add a second server
        val addResult = repository.addServerLocal(
            name = "Backup Node",
            url = "https://backup.flinsmusic.com",
            isEnabled = true,
            isActive = false
        )
        val backupServer = addResult.getOrThrow()

        // Attempting to delete the active server must fail
        val deleteActiveResult = repository.deleteServer(onlyServer.id)
        assertFalse(deleteActiveResult.isSuccess)

        // Deleting non-active server succeeds
        val deleteBackupResult = repository.deleteServer(backupServer.id)
        assertTrue(deleteBackupResult.isSuccess)
        assertEquals(1, repository.servers.value.size)
    }

    @Test
    fun updateServerStatus_updatesStatusAndLatency() {
        val active = repository.activeServer.value
        repository.updateServerStatus(active.id, ServerConnectionStatus.CONNECTED, 45L)

        val updated = repository.activeServer.value
        assertEquals(ServerConnectionStatus.CONNECTED, updated.status)
        assertEquals(45L, updated.latencyMs)
    }

    @Test
    fun remoteMutations_failWhenTokenIsMissing() = runBlocking {
        val repoNoToken = ServerConfigRepository(
            context = context,
            tokenProvider = { null }
        )
        val result = repoNoToken.addServerRemote("Test", "https://test.flinsmusic.com", true, false)
        assertTrue("Request must fail when token is missing", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Authentication required"))
    }

    @Test
    fun remoteMutations_failWhenBackendReturnsUnauthorized401() = runBlocking {
        val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"success":false,"error":"Authentication token has expired. Please sign in again."}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val repoAuthFail = ServerConfigRepository(
            context = context,
            okHttpClient = mockClient,
            tokenProvider = { "expired-token" }
        )
        val result = repoAuthFail.addServerRemote("Test", "https://test.flinsmusic.com", true, false)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("expired"))
    }

    @Test
    fun remoteMutations_failWhenBackendReturnsForbidden403() = runBlocking {
        val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body("""{"success":false,"error":"Forbidden. User is not authorized as an administrator."}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val repoForbidden = ServerConfigRepository(
            context = context,
            okHttpClient = mockClient,
            tokenProvider = { "non-admin-token" }
        )
        val result = repoForbidden.deleteServerRemote("srv_123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not authorized"))
    }

    @Test
    fun addServerRemote_attachesBearerToken_andUpdatesStateUponSuccess() = runBlocking {
        var capturedAuthHeader: String? = null
        val capturedMethods = mutableListOf<String>()

        val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            capturedAuthHeader = req.header("Authorization")
            capturedMethods.add(req.method)
            if (req.url.encodedPath == "/api/admin/servers" && req.method == "POST") {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body("""{"success":true,"data":{"id":"srv_new_node","name":"New Node","url":"https://node.flinsmusic.com/","isEnabled":true,"isActive":false}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else if (req.url.encodedPath == "/api/admin/servers" && req.method == "GET") {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"success":true,"data":[{"id":"srv_default_render","name":"Render Production Cluster","url":"https://socketformusic.onrender.com/","isEnabled":true,"isActive":true},{"id":"srv_new_node","name":"New Node","url":"https://node.flinsmusic.com/","isEnabled":true,"isActive":false}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").body("{}".toResponseBody("application/json".toMediaType())).build()
            }
        }.build()

        val repoSuccess = ServerConfigRepository(
            context = context,
            okHttpClient = mockClient,
            tokenProvider = { "valid-firebase-id-token" }
        )

        val result = repoSuccess.addServerRemote("New Node", "https://node.flinsmusic.com", true, false)
        assertTrue(result.isSuccess)
        assertEquals("Bearer valid-firebase-id-token", capturedAuthHeader)
        assertTrue("Expected POST request", capturedMethods.contains("POST"))
        assertTrue("Expected GET request to refresh servers", capturedMethods.contains("GET"))
        assertEquals(2, repoSuccess.servers.value.size)
    }

    @Test
    fun testConnection_usesBackendProbeWhenTokenAvailable() = runBlocking {
        var testEndpointCalled = false
        val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            if (req.url.encodedPath == "/api/admin/servers/test") {
                testEndpointCalled = true
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"success":true,"data":{"status":"Connected","latencyMs":88}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(500).message("Error").body("{}".toResponseBody("application/json".toMediaType())).build()
            }
        }.build()

        val repoTest = ServerConfigRepository(
            context = context,
            okHttpClient = mockClient,
            tokenProvider = { "valid-token" }
        )

        val (status, latency) = repoTest.testConnection("https://node.flinsmusic.com")
        assertTrue(testEndpointCalled)
        assertEquals(ServerConnectionStatus.CONNECTED, status)
        assertEquals(88L, latency)
    }
}
