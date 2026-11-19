package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class UserGreetingPreferences(
    val userId: String = "",
    val displayName: String = DEFAULT_NAME,
    val customGreeting: String = "",
    val isCustomGreetingMode: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_NAME = "Gaurav"

        fun getTimeGreeting(hourOfDay: Int): String {
            return when (hourOfDay) {
                in 5..11 -> "Good Morning"
                in 12..16 -> "Good Afternoon"
                in 17..20 -> "Good Evening"
                else -> "Good Night"
            }
        }
    }

    /**
     * Resolves the greeting string.
     * If in custom greeting mode with a non-blank custom greeting, returns the exact custom string.
     * Otherwise formats time-of-day greeting: "$timeGreeting, $displayName" (defaulting to Gaurav).
     */
    fun resolveGreeting(calendar: Calendar = Calendar.getInstance()): String {
        if (isCustomGreetingMode && customGreeting.isNotBlank()) {
            return customGreeting
        }
        val name = displayName.trim().ifBlank { DEFAULT_NAME }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val timeGreeting = getTimeGreeting(hour)
        return "$timeGreeting, $name"
    }
}

interface UserPreferencesRepository {
    fun getGreetingPreferencesFlow(userId: String): StateFlow<UserGreetingPreferences>
    fun getGreetingPreferences(userId: String): UserGreetingPreferences
    suspend fun saveDisplayName(userId: String, name: String): Boolean
    suspend fun saveCustomGreeting(userId: String, customGreeting: String): Boolean
    suspend fun resetToDefault(userId: String): Boolean
}

class PersistentUserPreferencesRepository(
    private val context: Context? = null
) : UserPreferencesRepository {

    // Thread-safe in-memory cache for ultra-fast, zero-IO reads and reactive StateFlow observation
    private val memoryCache = ConcurrentHashMap<String, MutableStateFlow<UserGreetingPreferences>>()

    private fun cleanKey(userId: String): String {
        return userId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "default_user" }
    }

    private fun getPrefs(cleanUserKey: String): SharedPreferences? {
        return context?.getSharedPreferences("anyfy_user_prefs_$cleanUserKey", Context.MODE_PRIVATE)
    }

    private fun loadFromStorage(cleanUserKey: String): UserGreetingPreferences {
        val prefs = getPrefs(cleanUserKey)
        if (prefs != null) {
            val name = prefs.getString("display_name", UserGreetingPreferences.DEFAULT_NAME)
                ?: UserGreetingPreferences.DEFAULT_NAME
            val customGreeting = prefs.getString("custom_greeting", "") ?: ""
            val isCustomMode = prefs.getBoolean("is_custom_greeting_mode", false)
            val lastUpdated = prefs.getLong("last_updated", System.currentTimeMillis())
            return UserGreetingPreferences(
                userId = cleanUserKey,
                displayName = name.ifBlank { UserGreetingPreferences.DEFAULT_NAME },
                customGreeting = customGreeting,
                isCustomGreetingMode = isCustomMode,
                lastUpdated = lastUpdated
            )
        }
        return UserGreetingPreferences(userId = cleanUserKey)
    }

    private fun getOrCreateStateFlow(userId: String): MutableStateFlow<UserGreetingPreferences> {
        val key = cleanKey(userId)
        return memoryCache.computeIfAbsent(key) {
            MutableStateFlow(loadFromStorage(key))
        }
    }

    override fun getGreetingPreferencesFlow(userId: String): StateFlow<UserGreetingPreferences> {
        return getOrCreateStateFlow(userId).asStateFlow()
    }

    override fun getGreetingPreferences(userId: String): UserGreetingPreferences {
        return getOrCreateStateFlow(userId).value
    }

    override suspend fun saveDisplayName(userId: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val key = cleanKey(userId)
        val flow = getOrCreateStateFlow(userId)
        val now = System.currentTimeMillis()
        val updated = flow.value.copy(
            displayName = trimmed,
            isCustomGreetingMode = false,
            lastUpdated = now
        )
        flow.value = updated

        getPrefs(key)?.edit()
            ?.putString("display_name", trimmed)
            ?.putBoolean("is_custom_greeting_mode", false)
            ?.putLong("last_updated", now)
            ?.apply()

        return true
    }

    override suspend fun saveCustomGreeting(userId: String, customGreeting: String): Boolean {
        val trimmed = customGreeting.trim()
        if (trimmed.isBlank()) return false
        val key = cleanKey(userId)
        val flow = getOrCreateStateFlow(userId)
        val now = System.currentTimeMillis()
        val updated = flow.value.copy(
            customGreeting = trimmed,
            isCustomGreetingMode = true,
            lastUpdated = now
        )
        flow.value = updated

        getPrefs(key)?.edit()
            ?.putString("custom_greeting", trimmed)
            ?.putBoolean("is_custom_greeting_mode", true)
            ?.putLong("last_updated", now)
            ?.apply()

        return true
    }

    override suspend fun resetToDefault(userId: String): Boolean {
        val key = cleanKey(userId)
        val flow = getOrCreateStateFlow(userId)
        val now = System.currentTimeMillis()
        val updated = UserGreetingPreferences(
            userId = key,
            displayName = UserGreetingPreferences.DEFAULT_NAME,
            customGreeting = "",
            isCustomGreetingMode = false,
            lastUpdated = now
        )
        flow.value = updated

        getPrefs(key)?.edit()
            ?.putString("display_name", UserGreetingPreferences.DEFAULT_NAME)
            ?.putString("custom_greeting", "")
            ?.putBoolean("is_custom_greeting_mode", false)
            ?.putLong("last_updated", now)
            ?.apply()

        return true
    }

    companion object {
        @Volatile
        private var instance: PersistentUserPreferencesRepository? = null

        fun getInstance(context: Context): PersistentUserPreferencesRepository {
            return instance ?: synchronized(this) {
                instance ?: PersistentUserPreferencesRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
