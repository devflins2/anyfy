package com.example.data.repository

import com.example.data.remote.RetrofitClient
import com.example.data.remote.api.MusicApiService
import com.example.model.MusicSearchUiItem
import com.example.model.toUiItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MusicSearchRepository {
    suspend fun searchTracks(query: String, limit: Int = 25): Result<List<MusicSearchUiItem>>
}

class NetworkMusicSearchRepository(
    private val apiService: MusicApiService = RetrofitClient.apiService
) : MusicSearchRepository {
    override suspend fun searchTracks(query: String, limit: Int): Result<List<MusicSearchUiItem>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                return@withContext Result.success(emptyList())
            }
            try {
                val response = apiService.searchTracks(term = query.trim(), limit = limit)
                val dtoList = response.results ?: emptyList()
                val uiItems = dtoList.map { it.toUiItem() }
                Result.success(uiItems)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
