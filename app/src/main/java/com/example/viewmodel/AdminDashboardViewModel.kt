package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ServerConfigRepository
import com.example.model.ServerConnectionStatus
import com.example.model.SocketServerConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminDashboardViewModel(
    private val configRepository: ServerConfigRepository
) : ViewModel() {

    companion object {
        val ADMIN_EMAILS = listOf(
            "admin@flinsmusic.com",
            "gaurav@flinsmusic.com",
            "developer@flinsmusic.com",
            "gauravkushwaha9901@gmail.com"
        )

        /**
         * Pure UI gating helper: returns true if the email is present in the admin allowlist.
         * The backend remains the authoritative source of verification and authorization.
         */
        fun isUserAdmin(email: String?): Boolean {
            if (email.isNullOrBlank()) return false
            val cleanEmail = email.lowercase().trim()
            return ADMIN_EMAILS.any { it.equals(cleanEmail, ignoreCase = true) }
        }
    }

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    val servers: StateFlow<List<SocketServerConfig>> = configRepository.servers
    val activeServer: StateFlow<SocketServerConfig> = configRepository.activeServer

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _testingServerId = MutableStateFlow<String?>(null)
    val testingServerId: StateFlow<String?> = _testingServerId.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var messageDismissJob: Job? = null

    init {
        checkAdminAccess()
        refreshConfig()
    }

    fun checkAdminAccess(explicitEmail: String? = null) {
        val email = explicitEmail ?: try {
            val app = com.example.FlinsApplication.instance
            app?.let { com.example.service.integrations.ytmusic.YtAccountManager.getInstance(it).activeAccount.value?.email }
        } catch (_: Throwable) {
            null
        }

        // Strict requirement: Only signed-in users whose email matches allowlist.
        _isAdmin.value = !email.isNullOrBlank() && isUserAdmin(email)
    }

    fun setExplicitAdmin(authorized: Boolean) {
        _isAdmin.value = authorized
    }

    fun refreshConfig() {
        viewModelScope.launch {
            _isLoading.value = true
            if (_isAdmin.value) {
                configRepository.fetchAdminServers()
            } else {
                configRepository.refreshRemoteConfig()
            }
            _isLoading.value = false
        }
    }

    fun showFeedback(msg: String, isError: Boolean = false) {
        messageDismissJob?.cancel()
        if (isError) {
            _errorMessage.value = msg
        } else {
            _userMessage.value = msg
        }
        messageDismissJob = viewModelScope.launch {
            delay(3000L)
            _userMessage.value = null
            _errorMessage.value = null
        }
    }

    fun clearMessages() {
        _userMessage.value = null
        _errorMessage.value = null
    }

    fun addServer(name: String, url: String, isEnabled: Boolean, isActive: Boolean) {
        if (!_isAdmin.value) {
            showFeedback("Unauthorized: Administrator access required", isError = true)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = configRepository.addServerRemote(name, url, isEnabled, isActive)
            _isLoading.value = false
            result.fold(
                onSuccess = { srv ->
                    showFeedback("Server '${srv.name}' added successfully")
                    testConnection(srv)
                },
                onFailure = { err ->
                    showFeedback(err.message ?: "Failed to add server", isError = true)
                }
            )
        }
    }

    fun updateServer(id: String, name: String, url: String, isEnabled: Boolean) {
        if (!_isAdmin.value) {
            showFeedback("Unauthorized: Administrator access required", isError = true)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = configRepository.updateServerRemote(id, name, url, isEnabled)
            _isLoading.value = false
            result.fold(
                onSuccess = { srv ->
                    showFeedback("Server '${srv.name}' updated")
                },
                onFailure = { err ->
                    showFeedback(err.message ?: "Failed to update server", isError = true)
                }
            )
        }
    }

    fun activateServer(id: String) {
        if (!_isAdmin.value) {
            showFeedback("Unauthorized: Administrator access required", isError = true)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = configRepository.setActiveServerRemote(id)
            _isLoading.value = false
            result.fold(
                onSuccess = { srv ->
                    showFeedback("Active server switched to '${srv.name}'")
                },
                onFailure = { err ->
                    showFeedback(err.message ?: "Failed to activate server", isError = true)
                }
            )
        }
    }

    fun deleteServer(id: String) {
        if (!_isAdmin.value) {
            showFeedback("Unauthorized: Administrator access required", isError = true)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = configRepository.deleteServerRemote(id)
            _isLoading.value = false
            result.fold(
                onSuccess = {
                    showFeedback("Server removed")
                },
                onFailure = { err ->
                    showFeedback(err.message ?: "Failed to delete server", isError = true)
                }
            )
        }
    }

    fun testConnection(server: SocketServerConfig) {
        viewModelScope.launch {
            _testingServerId.value = server.id
            val (status, latency) = configRepository.testConnection(server.url)
            configRepository.updateServerStatus(server.id, status, latency)
            _testingServerId.value = null

            if (status == ServerConnectionStatus.CONNECTED) {
                showFeedback("${server.name} reached in ${latency}ms")
            } else {
                showFeedback("${server.name} is unreachable", isError = true)
            }
        }
    }
}
