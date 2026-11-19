package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

interface SearchHistoryRepository {
    fun getRecentSearches(userId: String): List<String>
    fun addRecentSearch(userId: String, query: String)
    fun removeRecentSearch(userId: String, query: String)
    fun clearRecentSearches(userId: String)
}

class PersistentSearchHistoryRepository(
    private val context: Context? = null
) : SearchHistoryRepository {

    // In-memory fallback cache used for tests or zero-IO memory access
    private val memoryStore = ConcurrentHashMap<String, MutableList<String>>()

    private fun getPrefs(userId: String): SharedPreferences? {
        val cleanUserId = userId.ifBlank { "guest" }.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return context?.getSharedPreferences("anyfy_search_history_$cleanUserId", Context.MODE_PRIVATE)
    }

    override fun getRecentSearches(userId: String): List<String> {
        val cleanUser = userId.ifBlank { "guest" }
        val prefs = getPrefs(cleanUser)
        if (prefs != null && prefs.contains("history_items")) {
            val json = prefs.getString("history_items", null)
            if (!json.isNullOrBlank()) {
                try {
                    val arr = JSONArray(json)
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getString(i))
                    }
                    memoryStore[cleanUser] = list
                    return list
                } catch (_: Exception) {}
            }
        }
        return memoryStore[cleanUser]?.toList() ?: emptyList()
    }

    override fun addRecentSearch(userId: String, query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return
        val cleanUser = userId.ifBlank { "guest" }

        val current = getRecentSearches(cleanUser).toMutableList()
        current.removeAll { it.equals(cleanQuery, ignoreCase = true) }
        current.add(0, cleanQuery)
        val trimmed = current.take(10)

        memoryStore[cleanUser] = trimmed.toMutableList()

        val prefs = getPrefs(cleanUser)
        if (prefs != null) {
            val arr = JSONArray()
            trimmed.forEach { arr.put(it) }
            prefs.edit().putString("history_items", arr.toString()).apply()
        }
    }

    override fun removeRecentSearch(userId: String, query: String) {
        val cleanUser = userId.ifBlank { "guest" }
        val current = getRecentSearches(cleanUser).toMutableList()
        current.removeAll { it.equals(query.trim(), ignoreCase = true) }

        memoryStore[cleanUser] = current

        val prefs = getPrefs(cleanUser)
        if (prefs != null) {
            val arr = JSONArray()
            current.forEach { arr.put(it) }
            prefs.edit().putString("history_items", arr.toString()).apply()
        }
    }

    override fun clearRecentSearches(userId: String) {
        val cleanUser = userId.ifBlank { "guest" }
        memoryStore[cleanUser] = mutableListOf()
        val prefs = getPrefs(cleanUser)
        prefs?.edit()?.remove("history_items")?.apply()
    }
}
