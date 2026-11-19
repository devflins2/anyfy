package com.example.data.repository

import com.example.model.UnifiedSearchResult
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

class SearchCache(
    private val maxCapacity: Int = 100,
    private val ttlMs: Long = TimeUnit.MINUTES.toMillis(15)
) {
    private data class CacheEntry(
        val result: UnifiedSearchResult,
        val timestamp: Long
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxCapacity
        }
    }

    private val lock = Any()

    fun get(query: String): UnifiedSearchResult? = synchronized(lock) {
        val key = normalizeKey(query)
        val entry = cache[key] ?: return null
        val now = System.currentTimeMillis()
        if (now - entry.timestamp > ttlMs) {
            cache.remove(key)
            return null
        }
        return entry.result.copy(isFromCache = true)
    }

    fun put(query: String, result: UnifiedSearchResult) = synchronized(lock) {
        val key = normalizeKey(query)
        if (key.isNotBlank()) {
            cache[key] = CacheEntry(result.copy(isFromCache = false), System.currentTimeMillis())
        }
    }

    fun clear() = synchronized(lock) {
        cache.clear()
    }

    fun size(): Int = synchronized(lock) {
        cache.size
    }

    private fun normalizeKey(raw: String): String {
        return raw.trim().lowercase()
    }
}

