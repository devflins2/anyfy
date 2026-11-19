package com.example.model

import com.example.data.remote.dto.MusicTrackDto

/**
 * UI-friendly data class representing search results tailored for the presentation layer.
 */
data class MusicSearchUiItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val durationFormatted: String,
    val durationSeconds: Int,
    val audioPreviewUrl: String?,
    val genre: String,
    val releaseYear: String,
    val isStreamable: Boolean = true
)

/**
 * Maps raw network DTO into clean, UI-ready data representation.
 */
fun MusicTrackDto.toUiItem(): MusicSearchUiItem {
    val totalSeconds = ((trackTimeMillis ?: 0L) / 1000).toInt().coerceAtLeast(0)
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    val formattedTime = if (totalSeconds > 0) "%d:%02d".format(mins, secs) else "--:--"

    // Enhance artwork resolution from 100x100 to 600x600 for sharp rendering on high-density screens
    val highResArtwork = (artworkUrl100 ?: artworkUrl60 ?: "")
        .replace("100x100bb", "600x600bb")
        .replace("60x60bb", "600x600bb")
        .ifBlank { "" }

    val year = releaseDate?.take(4) ?: "Unknown"

    return MusicSearchUiItem(
        id = "api_${trackId ?: System.currentTimeMillis()}",
        title = trackName ?: "Unknown Track",
        artist = artistName ?: "Unknown Artist",
        album = collectionName ?: "Single",
        artworkUrl = highResArtwork,
        durationFormatted = formattedTime,
        durationSeconds = totalSeconds.coerceAtLeast(30),
        audioPreviewUrl = previewUrl,
        genre = primaryGenreName ?: "Music",
        releaseYear = year,
        isStreamable = !previewUrl.isNullOrBlank()
    )
}

/**
 * Converts UI item into standard player Track model for seamless playback in FlinsMusic player.
 */
fun MusicSearchUiItem.toTrack(): Track {
    return Track(
        id = id,
        title = title,
        artist = artist,
        artistId = "art_$artist",
        album = album,
        albumId = "alb_$album",
        artwork = artworkUrl,
        durationSeconds = durationSeconds,
        audioUrl = audioPreviewUrl,
        genre = genre,
        bpm = 120,
        energy = 0.75f,
        mood = "Online Stream",
        paletteColorHex = "#00F0FF"
    )
}
