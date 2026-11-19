package com.example.data.repository

import android.content.Context
import com.example.FlinsApplication
import com.example.service.integrations.ytmusic.YtAccount
import com.example.service.integrations.ytmusic.YtAccountManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * YouTube Music Account Authentication Repository.
 * Replaces Firebase Authentication with SimpMusic's native YouTube Music account architecture.
 * Automatically persists sessions across app restarts via YtAccountManager.
 */
class AuthRepository(context: Context? = null) {

    private val accountManager: YtAccountManager? = run {
        val targetContext = context ?: FlinsApplication.instance
        targetContext?.let { YtAccountManager.getInstance(it) }
    }

    /**
     * Emits the currently active [YtAccount] whenever auth state changes.
     * Null = signed out.
     */
    val currentAccount: Flow<YtAccount?> = run {
        accountManager?.activeAccount ?: kotlinx.coroutines.flow.flowOf(null)
    }

    /**
     * Returns the active account synchronously for one-time checks.
     */
    fun getCurrentAccountSync(): YtAccount? = accountManager?.activeAccount?.value

    /**
     * Signs in using a YouTube Music session cookie (captured via WebView or manual entry).
     * Automatically resolves verified account profile, handle, avatar, pageId, and authuser.
     */
    suspend fun signInWithCookie(cookie: String): Result<YtAccount> {
        val manager = accountManager
            ?: return Result.failure(IllegalStateException("YtAccountManager not initialized"))
        return manager.addAccountFromCookie(cookie)
    }

    /**
     * Switches the active signed-in YouTube Music account.
     */
    fun switchAccount(accountId: String): Boolean {
        return accountManager?.switchAccount(accountId) ?: false
    }

    /**
     * Removes an account from stored sessions.
     */
    fun removeAccount(accountId: String): Boolean {
        return accountManager?.removeAccount(accountId) ?: false
    }

    /**
     * Signs out the current account, clearing stored session cookies and resetting player.
     */
    fun signOut() {
        accountManager?.clearAccounts()
    }
}
