package com.example.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MusicSearchResponseDto(
    @param:Json(name = "resultCount") val resultCount: Int? = 0,
    @param:Json(name = "results") val results: List<MusicTrackDto>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class MusicTrackDto(
    @param:Json(name = "trackId") val trackId: Long? = null,
    @param:Json(name = "trackName") val trackName: String? = null,
    @param:Json(name = "artistName") val artistName: String? = null,
    @param:Json(name = "collectionName") val collectionName: String? = null,
    @param:Json(name = "artworkUrl100") val artworkUrl100: String? = null,
    @param:Json(name = "artworkUrl60") val artworkUrl60: String? = null,
    @param:Json(name = "previewUrl") val previewUrl: String? = null,
    @param:Json(name = "trackTimeMillis") val trackTimeMillis: Long? = null,
    @param:Json(name = "primaryGenreName") val primaryGenreName: String? = null,
    @param:Json(name = "releaseDate") val releaseDate: String? = null,
    @param:Json(name = "collectionId") val collectionId: Long? = null,
    @param:Json(name = "artistId") val artistId: Long? = null
)
