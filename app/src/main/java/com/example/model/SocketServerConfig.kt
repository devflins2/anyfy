package com.example.model

enum class ServerConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    UNREACHABLE,
    NOT_TESTED
}

data class SocketServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val isEnabled: Boolean = true,
    val isActive: Boolean = false,
    val status: ServerConnectionStatus = ServerConnectionStatus.NOT_TESTED,
    val latencyMs: Long? = null,
    val lastTestedAt: Long? = null
)
