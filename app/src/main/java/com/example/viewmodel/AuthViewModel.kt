package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.AuthRepository
import com.example.service.PlaybackService
import com.example.service.integrations.ytmusic.YtAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthState {
    object Loading : AuthState()
    data class SignedIn(val account: YtAccount) : AuthState()
    object SignedOut : AuthState()
}

/**
 * Manages the application-wide authentication state using YouTube Music account architecture.
 * Replaces Firebase Auth with genuine InnerTube session credentials.
 */
class AuthViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val playbackService: PlaybackService? = null
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.currentAccount.collect { account ->
                if (account != null && account.cookie.isNotBlank()) {
                    _authState.value = AuthState.SignedIn(account)
                } else {
                    _authState.value = AuthState.SignedOut
                    playbackService?.stopAndClearPlayback()
                }
            }
        }
    }

    /**
     * Signs in using session cookies obtained from the in-app YouTube Music WebView or manual import.
     */
    fun signInWithCookie(cookie: String, onFinished: ((Boolean, String) -> Unit)? = null) {
        if (_isSigningIn.value) return
        viewModelScope.launch {
            _isSigningIn.value = true
            _errorMessage.value = null
            try {
                val result = authRepository.signInWithCookie(cookie)
                if (result.isSuccess) {
                    val account = result.getOrThrow()
                    _authState.value = AuthState.SignedIn(account)
                    onFinished?.invoke(true, account.name)
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "YouTube Music authentication failed"
                    _errorMessage.value = errorMsg
                    onFinished?.invoke(false, errorMsg)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Authentication failed"
                _errorMessage.value = msg
                onFinished?.invoke(false, msg)
            } finally {
                _isSigningIn.value = false
            }
        }
    }

    fun switchAccount(accountId: String): Boolean {
        return authRepository.switchAccount(accountId)
    }

    fun signOut() {
        playbackService?.stopAndClearPlayback()
        authRepository.signOut()
        _authState.value = AuthState.SignedOut
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
