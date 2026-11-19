package com.example.data.remote.api

import com.example.data.remote.dto.MusicSearchResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Public music metadata API service definition powered by Retrofit.
 */
interface MusicApiService {
    @GET("search")
    suspend fun searchTracks(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 25
    ): MusicSearchResponseDto
}
