package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.model.ServerConnectionStatus
import com.example.model.SocketServerConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ServerConfigRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val backendBaseUrl: String = DEFAULT_ADMIN_BACKEND_URL,
    private val tokenProvider: (suspend () -> String?)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val PREFS_NAME = "flinsmusic_server_prefs"
        const val KEY_ACTIVE_SERVER_URL = "active_server_url"
        const val KEY_ACTIVE_SERVER_NAME = "active_server_name"
        const val KEY_ACTIVE_SERVER_ID = "active_server_id"
        const val KEY_CACHED_SERVERS = "cached_servers_json"

        const val DEFAULT_ADMIN_BACKEND_URL = "https://flinsmusicbackend.onrender.com"
        const val DEFAULT_SERVER_URL = "https://socketformusic.onrender.com/"
        const val DEFAULT_SERVER_NAME = "Render Production Cluster"
        const val DEFAULT_SERVER_ID = "srv_default_render"

        @Volatile
        private var instance: ServerConfigRepository? = null

        fun getInstance(context: Context): ServerConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: ServerConfigRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cleanBackendBaseUrl: String get() = backendBaseUrl.trim().trimEnd('/')

    private val _activeServer = MutableStateFlow(loadInitialActiveServer())
    val activeServer: StateFlow<SocketServerConfig> = _activeServer.asStateFlow()

    private val _servers = MutableStateFlow<List<SocketServerConfig>>(loadInitialServers())
    val servers: StateFlow<List<SocketServerConfig>> = _servers.asStateFlow()

    private fun loadInitialActiveServer(): SocketServerConfig {
        val url = prefs.getString(KEY_ACTIVE_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val name = prefs.getString(KEY_ACTIVE_SERVER_NAME, DEFAULT_SERVER_NAME) ?: DEFAULT_SERVER_NAME
        val id = prefs.getString(KEY_ACTIVE_SERVER_ID, DEFAULT_SERVER_ID) ?: DEFAULT_SERVER_ID
        return SocketServerConfig(
            id = id,
            name = name,
            url = normalizeUrl(url),
            isEnabled = true,
            isActive = true,
            status = ServerConnectionStatus.NOT_TESTED
        )
    }

    private fun loadInitialServers(): List<SocketServerConfig> {
        val jsonStr = prefs.getString(KEY_CACHED_SERVERS, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<SocketServerConfig>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        SocketServerConfig(
                            id = obj.optString("id", "srv_$i"),
                            name = obj.optString("name", "Server $i"),
                            url = normalizeUrl(obj.optString("url", DEFAULT_SERVER_URL)),
                            isEnabled = obj.optBoolean("isEnabled", true),
                            isActive = obj.optBoolean("isActive", false),
                            status = ServerConnectionStatus.NOT_TESTED
                        )
                    )
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {}
        }
        return listOf(loadInitialActiveServer())
    }

    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /**
     * Client-side SSRF validation as defense-in-depth before submitting or testing.
     */
    fun validateServerUrlClient(url: String): Pair<Boolean, String?> {
        if (url.isBlank()) return Pair(false, "Server URL cannot be empty")
        val trimmed = url.trim()
        val parsed = try {
            Uri.parse(trimmed)
        } catch (_: Exception) {
            return Pair(false, "Invalid URL format")
        }

        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && scheme != "wss") {
            return Pair(false, "Protocol must be HTTPS or WSS for production Socket.IO endpoints")
        }

        val host = parsed.host?.lowercase() ?: return Pair(false, "URL must include a valid host")

        // Reject localhost and loopback hostnames
        val blockedHostnames = listOf("localhost", "127.0.0.1", "::1", "0.0.0.0")
        if (blockedHostnames.contains(host) || host.endsWith(".local") || host.endsWith(".internal")) {
            return Pair(false, "Localhost and internal addresses are blocked (SSRF protection)")
        }

        // Reject private IPv4 ranges
        if (host.matches(Regex("""^10\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) ||
            host.matches(Regex("""^192\.168\.\d{1,3}\.\d{1,3}$""")) ||
            host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\.\d{1,3}\.\d{1,3}$""")) ||
            host.startsWith("169.254.")
        ) {
            return Pair(false, "Private and cloud metadata network ranges are blocked (SSRF protection)")
        }

        return Pair(true, null)
    }

    /**
     * Refresh active server config from remote backend safely.
     * Caches result locally; falls back gracefully on network error without disrupting playback.
     */
    suspend fun refreshRemoteConfig(): Result<SocketServerConfig> = withContext(ioDispatcher) {
        try {
            val request = Request.Builder()
                .url("$cleanBackendBaseUrl/api/servers")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val root = JSONObject(body)
                    val data = root.optJSONObject("data")
                    val activeObj = data?.optJSONObject("activeServer")
                    if (activeObj != null) {
                        val activeUrl = normalizeUrl(activeObj.getString("url"))
                        val activeName = activeObj.optString("name", DEFAULT_SERVER_NAME)
                        val activeId = activeObj.optString("id", DEFAULT_SERVER_ID)

                        val config = SocketServerConfig(
                            id = activeId,
                            name = activeName,
                            url = activeUrl,
                            isEnabled = true,
                            isActive = true,
                            status = ServerConnectionStatus.CONNECTED
                        )

                        // Save to preferences cache
                        prefs.edit()
                            .putString(KEY_ACTIVE_SERVER_URL, activeUrl)
                            .putString(KEY_ACTIVE_SERVER_NAME, activeName)
                            .putString(KEY_ACTIVE_SERVER_ID, activeId)
                            .apply()

                        _activeServer.value = config

                        // Also parse server list if available
                        val srvArray = data.optJSONArray("servers")
                        if (srvArray != null) {
                            val list = mutableListOf<SocketServerConfig>()
                            for (i in 0 until srvArray.length()) {
                                val item = srvArray.getJSONObject(i)
                                val itemUrl = normalizeUrl(item.getString("url"))
                                val isAct = item.optBoolean("isActive", itemUrl == activeUrl)
                                list.add(
                                    SocketServerConfig(
                                        id = item.optString("id", "srv_$i"),
                                        name = item.optString("name", "Server $i"),
                                        url = itemUrl,
                                        isEnabled = true,
                                        isActive = isAct,
                                        status = if (isAct) ServerConnectionStatus.CONNECTED else ServerConnectionStatus.NOT_TESTED
                                    )
                                )
                            }
                            if (list.isNotEmpty()) {
                                _servers.value = list
                                persistServers(list)
                            }
                        }
                        return@withContext Result.success(config)
                    }
                }
            }
            Result.success(_activeServer.value)
        } catch (e: Exception) {
            Result.success(_activeServer.value)
        }
    }

    private suspend fun getAuthToken(): String? {
        return if (tokenProvider != null) {
            tokenProvider.invoke()
        } else {
            try {
                val app = com.example.FlinsApplication.instance
                if (app != null) {
                    val authHeaders = com.example.service.integrations.ytmusic.YtAccountManager.getInstance(app).getAuthHeaders()
                    authHeaders["Authorization"] ?: authHeaders["Cookie"]
                } else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun parseServerJson(obj: JSONObject, fallbackIndex: Int): SocketServerConfig {
        val rawUrl = obj.optString("url", DEFAULT_SERVER_URL)
        val id = obj.optString("id", "srv_$fallbackIndex")
        val name = obj.optString("name", "Server $fallbackIndex")
        val isEnabled = obj.optBoolean("isEnabled", true)
        val isActive = obj.optBoolean("isActive", false)
        return SocketServerConfig(
            id = id,
            name = name,
            url = normalizeUrl(rawUrl),
            isEnabled = isEnabled,
            isActive = isActive,
            status = if (isActive) ServerConnectionStatus.CONNECTED else ServerConnectionStatus.NOT_TESTED
        )
    }

    private fun parseErrorMessage(body: String, defaultMsg: String): String {
        return try {
            val root = JSONObject(body)
            root.optString("error", defaultMsg).ifBlank { defaultMsg }
        } catch (_: Exception) {
            defaultMsg
        }
    }

    /**
     * Fetch complete list of servers (enabled and disabled) via authenticated Admin API.
     */
    suspend fun fetchAdminServers(): Result<List<SocketServerConfig>> = withContext(ioDispatcher) {
        val token = getAuthToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Authentication required. Please sign in as an administrator."))
        }

        try {
            val request = Request.Builder()
                .url("$cleanBackendBaseUrl/api/admin/servers")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body, "Failed to fetch admin servers (HTTP ${response.code})")
                return@withContext Result.failure(SecurityException(errorMsg))
            }

            val root = JSONObject(body)
            val srvArray = root.optJSONArray("data")
            if (srvArray != null) {
                val list = mutableListOf<SocketServerConfig>()
                for (i in 0 until srvArray.length()) {
                    val item = srvArray.getJSONObject(i)
                    list.add(parseServerJson(item, i))
                }
                _servers.value = list
                val active = list.find { it.isActive }
                if (active != null) {
                    _activeServer.value = active
                    prefs.edit()
                        .putString(KEY_ACTIVE_SERVER_URL, active.url)
                        .putString(KEY_ACTIVE_SERVER_NAME, active.name)
                        .putString(KEY_ACTIVE_SERVER_ID, active.id)
                        .apply()
                }
                persistServers(list)
                return@withContext Result.success(list)
            }
            Result.success(_servers.value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Add a server via authenticated Admin API.
     */
    suspend fun addServerRemote(name: String, url: String, isEnabled: Boolean, isActive: Boolean): Result<SocketServerConfig> = withContext(ioDispatcher) {
        val (valid, error) = validateServerUrlClient(url)
        if (!valid) return@withContext Result.failure(IllegalArgumentException(error ?: "Invalid URL"))

        val token = getAuthToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Authentication required. Please sign in as an administrator."))
        }

        try {
            val jsonBody = JSONObject().apply {
                put("name", name.trim())
                put("url", normalizeUrl(url))
                put("isEnabled", isEnabled)
                put("isActive", isActive)
            }
            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$cleanBackendBaseUrl/api/admin/servers")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body, "Failed to add server (HTTP ${response.code})")
                return@withContext Result.failure(Exception(errorMsg))
            }

            val root = JSONObject(body)
            val dataObj = root.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("Malformed response from server"))

            val newServer = parseServerJson(dataObj, 0)
            // Re-fetch servers from backend to preserve authoritative state
            fetchAdminServers()
            Result.success(newServer)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update an existing server via authenticated Admin API.
     */
    suspend fun updateServerRemote(id: String, name: String, url: String, isEnabled: Boolean): Result<SocketServerConfig> = withContext(ioDispatcher) {
        val (valid, error) = validateServerUrlClient(url)
        if (!valid) return@withContext Result.failure(IllegalArgumentException(error ?: "Invalid URL"))

        val token = getAuthToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Authentication required. Please sign in as an administrator."))
        }

        try {
            val jsonBody = JSONObject().apply {
                put("name", name.trim())
                put("url", normalizeUrl(url))
                put("isEnabled", isEnabled)
            }
            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$cleanBackendBaseUrl/api/admin/servers/$id")
                .header("Authorization", "Bearer $token")
                .put(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body, "Failed to update server (HTTP ${response.code})")
                return@withContext Result.failure(Exception(errorMsg))
            }

            val root = JSONObject(body)
            val dataObj = root.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("Malformed response from server"))

            val updatedServer = parseServerJson(dataObj, 0)
            fetchAdminServers()
            Result.success(updatedServer)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set a server as active via authenticated Admin API.
     */
    suspend fun setActiveServerRemote(id: String): Result<SocketServerConfig> = withContext(ioDispatcher) {
        val token = getAuthToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Authentication required. Please sign in as an administrator."))
        }

        try {
            val emptyBody = "".toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$cleanBackendBaseUrl/api/admin/servers/$id/activate")
                .header("Authorization", "Bearer $token")
                .post(emptyBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body, "Failed to activate server (HTTP ${response.code})")
                return@withContext Result.failure(Exception(errorMsg))
            }

            val root = JSONObject(body)
            val dataObj = root.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("Malformed response from server"))

            val activeServer = parseServerJson(dataObj, 0)
            fetchAdminServers()
            Result.success(activeServer)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a server via authenticated Admin API.
     */
    suspend fun deleteServerRemote(id: String): Result<Unit> = withContext(ioDispatcher) {
        val token = getAuthToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Authentication required. Please sign in as an administrator."))
        }

        try {
            val request = Request.Builder()
                .url("$cleanBackendBaseUrl/api/admin/servers/$id")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body, "Failed to delete server (HTTP ${response.code})")
                return@withContext Result.failure(Exception(errorMsg))
            }

            fetchAdminServers()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Performs a real connection test to a Socket.IO endpoint.
     * Uses backend /api/admin/servers/test probe when authenticated; falls back gracefully to direct probe.
     */
    suspend fun testConnection(url: String): Pair<ServerConnectionStatus, Long> = withContext(ioDispatcher) {
        val (isValid, _) = validateServerUrlClient(url)
        if (!isValid) {
            return@withContext Pair(ServerConnectionStatus.UNREACHABLE, 0L)
        }

        val token = getAuthToken()
        if (!token.isNullOrBlank()) {
            try {
                val jsonBody = JSONObject().apply { put("url", normalizeUrl(url)) }
                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("$cleanBackendBaseUrl/api/admin/servers/test")
                    .header("Authorization", "Bearer $token")
                    .post(requestBody)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val root = JSONObject(body)
                    val dataObj = root.optJSONObject("data")
                    if (dataObj != null) {
                        val statusStr = dataObj.optString("status", "Unreachable")
                        val latency = dataObj.optLong("latencyMs", 0L)
                        val status = if (statusStr.equals("Connected", ignoreCase = true)) {
                            ServerConnectionStatus.CONNECTED
                        } else {
                            ServerConnectionStatus.UNREACHABLE
                        }
                        return@withContext Pair(status, latency)
                    }
                }
            } catch (_: Exception) {
                // Fall back to direct probe if remote endpoint is unreachable
            }
        }

        testConnectionDirect(url)
    }

    private fun testConnectionDirect(url: String): Pair<ServerConnectionStatus, Long> {
        val startTime = System.currentTimeMillis()
        return try {
            val host = Uri.parse(url).host ?: return Pair(ServerConnectionStatus.UNREACHABLE, 0L)
            InetAddress.getByName(host)

            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Anyfy-Probe/1.0")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            val isReachable = response.code in 200..499
            if (isReachable) {
                Pair(ServerConnectionStatus.CONNECTED, latency)
            } else {
                Pair(ServerConnectionStatus.UNREACHABLE, latency)
            }
        } catch (_: Exception) {
            Pair(ServerConnectionStatus.UNREACHABLE, System.currentTimeMillis() - startTime)
        }
    }

    /**
     * Admin: Add a server locally & update state.
     */
    fun addServerLocal(name: String, url: String, isEnabled: Boolean, isActive: Boolean): Result<SocketServerConfig> {
        val (valid, error) = validateServerUrlClient(url)
        if (!valid) return Result.failure(IllegalArgumentException(error ?: "Invalid URL"))

        val normUrl = normalizeUrl(url)
        val newId = "srv_${System.currentTimeMillis()}"
        val newServer = SocketServerConfig(
            id = newId,
            name = name.trim(),
            url = normUrl,
            isEnabled = isEnabled,
            isActive = isActive,
            status = ServerConnectionStatus.NOT_TESTED
        )

        val updated = _servers.value.toMutableList()
        if (isActive) {
            for (i in updated.indices) {
                updated[i] = updated[i].copy(isActive = false)
            }
            _activeServer.value = newServer
            prefs.edit()
                .putString(KEY_ACTIVE_SERVER_URL, normUrl)
                .putString(KEY_ACTIVE_SERVER_NAME, name.trim())
                .putString(KEY_ACTIVE_SERVER_ID, newId)
                .apply()
        }
        updated.add(newServer)
        _servers.value = updated
        persistServers(updated)
        return Result.success(newServer)
    }

    /**
     * Admin: Edit a server.
     */
    fun updateServerLocal(id: String, name: String, url: String, isEnabled: Boolean): Result<SocketServerConfig> {
        val (valid, error) = validateServerUrlClient(url)
        if (!valid) return Result.failure(IllegalArgumentException(error ?: "Invalid URL"))

        val normUrl = normalizeUrl(url)
        val list = _servers.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return Result.failure(IllegalArgumentException("Server not found"))

        val existing = list[index]
        val updatedServer = existing.copy(
            name = name.trim(),
            url = normUrl,
            isEnabled = isEnabled
        )
        list[index] = updatedServer

        if (existing.isActive) {
            if (!isEnabled) {
                // Must activate another enabled server
                val candidate = list.find { it.id != id && it.isEnabled }
                if (candidate != null) {
                    val candidateIdx = list.indexOfFirst { it.id == candidate.id }
                    list[candidateIdx] = candidate.copy(isActive = true)
                    _activeServer.value = list[candidateIdx]
                }
            } else {
                _activeServer.value = updatedServer
                prefs.edit()
                    .putString(KEY_ACTIVE_SERVER_URL, normUrl)
                    .putString(KEY_ACTIVE_SERVER_NAME, name.trim())
                    .apply()
            }
        }
        _servers.value = list
        persistServers(list)
        return Result.success(updatedServer)
    }

    /**
     * Admin: Set a server as active.
     */
    fun setActiveServer(id: String): Result<SocketServerConfig> {
        val list = _servers.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return Result.failure(IllegalArgumentException("Server not found"))

        val target = list[index]
        if (!target.isEnabled) return Result.failure(IllegalArgumentException("Cannot activate a disabled server"))

        for (i in list.indices) {
            list[i] = list[i].copy(isActive = list[i].id == id)
        }
        val newActive = target.copy(isActive = true)
        _activeServer.value = newActive
        _servers.value = list

        prefs.edit()
            .putString(KEY_ACTIVE_SERVER_URL, newActive.url)
            .putString(KEY_ACTIVE_SERVER_NAME, newActive.name)
            .putString(KEY_ACTIVE_SERVER_ID, newActive.id)
            .apply()

        persistServers(list)
        return Result.success(newActive)
    }

    /**
     * Admin: Delete a server.
     */
    fun deleteServer(id: String): Result<Unit> {
        val list = _servers.value.toMutableList()
        val target = list.find { it.id == id } ?: return Result.failure(IllegalArgumentException("Server not found"))

        if (list.size <= 1) {
            return Result.failure(IllegalArgumentException("Cannot delete the only configured server"))
        }

        if (target.isActive) {
            return Result.failure(IllegalArgumentException("Cannot delete the active server. Switch to another server first."))
        }

        list.removeAll { it.id == id }
        _servers.value = list
        persistServers(list)
        return Result.success(Unit)
    }

    fun updateServerStatus(id: String, status: ServerConnectionStatus, latencyMs: Long?) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx != -1) {
            val updated = list[idx].copy(
                status = status,
                latencyMs = latencyMs,
                lastTestedAt = System.currentTimeMillis()
            )
            list[idx] = updated
            _servers.value = list
            if (updated.isActive) {
                _activeServer.value = updated
            }
        }
    }

    private fun persistServers(list: List<SocketServerConfig>) {
        try {
            val array = JSONArray()
            list.forEach { s ->
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("url", s.url)
                    put("isEnabled", s.isEnabled)
                    put("isActive", s.isActive)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_CACHED_SERVERS, array.toString()).apply()
        } catch (_: Exception) {}
    }
}
