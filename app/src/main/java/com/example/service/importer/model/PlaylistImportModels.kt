package com.example.service.importer.model

import com.example.model.Track

enum class PlatformType(val displayName: String, val badgeColorHex: String) {
    SPOTIFY("Spotify", "#1DB954"),
    YOUTUBE_MUSIC("YouTube Music", "#FF0000")
}

data class ImportTrackItem(
    val sourceTrackId: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val artwork: String = "",
    val durationMs: Long = 0L,
    val position: Int = 0
) {
    val primaryArtist: String
        get() = artists.firstOrNull()?.trim() ?: "Unknown Artist"

    val displayArtists: String
        get() = if (artists.isEmpty()) "Unknown Artist" else artists.joinToString(", ")
}

data class PlaylistImportData(
    val source: PlatformType,
    val sourcePlaylistId: String,
    val playlistName: String,
    val playlistDescription: String = "",
    val artwork: String = "",
    val totalTracks: Int,
    val tracks: List<ImportTrackItem>
)

enum class MatchStatus {
    MATCHED,
    NEEDS_REVIEW,
    MULTIPLE_MATCHES,
    NOT_FOUND,
    UNAVAILABLE
}

enum class MatchConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}

data class TrackMatchResult(
    val original: ImportTrackItem,
    val matchedTrack: Track? = null,
    val status: MatchStatus = MatchStatus.NOT_FOUND,
    val confidence: MatchConfidence = MatchConfidence.NONE,
    val confidenceScore: Float = 0f,
    val candidates: List<Track> = emptyList()
) {
    val isMatched: Boolean
        get() = matchedTrack != null && (status == MatchStatus.MATCHED || status == MatchStatus.NEEDS_REVIEW)
}

data class PlaylistImportSummary(
    val playlistName: String,
    val originalPlatform: PlatformType,
    val totalTracks: Int,
    val matchedCount: Int,
    val needsReviewCount: Int,
    val multipleMatchesCount: Int,
    val notFoundCount: Int
) {
    val canImport: Boolean
        get() = matchedCount > 0
}

