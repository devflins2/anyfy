package com.example.model

data class LyricsLine(
    val timeMs: Long,
    val text: String
)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "art_1",
    val album: String = "Flins Records",
    val albumId: String = "alb_1",
    val artwork: String,
    val bannerArtwork: String = "",
    val durationSeconds: Int,
    val audioUrl: String? = null,
    val lyrics: List<LyricsLine> = emptyList(),
    val genre: String = "Electronic",
    val bpm: Int = 120,
    val energy: Float = 0.7f,
    val mood: String = "Chill",
    val paletteColorHex: String = "#00F0FF" // Primary color to influence liquid glass
) {
    val durationFormatted: String
        get() {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val cleanVideoId: String?
        get() {
            val candidate = id.removePrefix("yt_")
                .removePrefix("online_")
                .removePrefix("api_")
                .removePrefix("track_")
                .trim()
            return if (candidate.length == 11 && !candidate.startsWith("PL") && !candidate.startsWith("UC") && !candidate.startsWith("trk_") && !candidate.startsWith("local_") && candidate.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                candidate
            } else null
        }

    val displayArtwork: String
        get() {
            if (artwork.isNotBlank()) return artwork
            if (bannerArtwork.isNotBlank()) return bannerArtwork
            cleanVideoId?.let { return "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            return ""
        }

    val displayBannerArtwork: String
        get() {
            if (bannerArtwork.isNotBlank()) return bannerArtwork
            cleanVideoId?.let { return "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            return displayArtwork
        }

    fun resolveBannerHierarchy(albumArtworkProvider: ((String) -> String?)? = null): List<BannerCandidate> {
        val candidates = mutableListOf<BannerCandidate>()
        val seenUrls = mutableSetOf<String>()

        fun addCandidate(level: BannerFallbackLevel, url: String?) {
            val clean = url?.trim() ?: return
            if (clean.isBlank() || !clean.startsWith("http")) return
            if (seenUrls.add(clean)) {
                candidates.add(BannerCandidate(level, clean))
            }
        }

        // Priority 1: Genuine song-specific wide banner, if available
        val wide = bannerArtwork.trim()
        if (wide.isNotBlank() && isDedicatedWideBanner(wide) && wide != artwork.trim()) {
            addCandidate(BannerFallbackLevel.LEVEL_1_GENUINE_WIDE, wide)
        }

        // Priority 2: Genuine associated album's wide artwork, if available
        if (albumArtworkProvider != null && albumId.isNotBlank() && albumId != "alb_1" && albumId != "alb_yt") {
            val albumArt = albumArtworkProvider(albumId)?.trim()
            if (!albumArt.isNullOrBlank() && isDedicatedWideBanner(albumArt)) {
                addCandidate(BannerFallbackLevel.LEVEL_2_ASSOCIATED_ALBUM, albumArt)
            }
        }

        // Priority 3: The same song's verified square artwork transformed into a landscape background
        val rawSquare = artwork.trim()
        if (rawSquare.isNotBlank() && rawSquare.startsWith("http") && !rawSquare.contains("i.ytimg.com/vi/")) {
            val wideTransformed = transformToLandscapeUrl(rawSquare)
            if (!wideTransformed.isNullOrBlank() && wideTransformed != rawSquare) {
                addCandidate(BannerFallbackLevel.LEVEL_3_SONG_SQUARE_LANDSCAPE, wideTransformed)
            }
            addCandidate(BannerFallbackLevel.LEVEL_3_SONG_SQUARE_LANDSCAPE, rawSquare)
        }

        // Priority 4: The correct associated album's square artwork transformed into a landscape background
        if (albumArtworkProvider != null && albumId.isNotBlank() && albumId != "alb_1" && albumId != "alb_yt") {
            val albumArt = albumArtworkProvider(albumId)?.trim()
            if (!albumArt.isNullOrBlank() && albumArt.startsWith("http")) {
                val albumWide = transformToLandscapeUrl(albumArt)
                if (!albumWide.isNullOrBlank() && albumWide != albumArt) {
                    addCandidate(BannerFallbackLevel.LEVEL_4_ALBUM_ID_ARTWORK, albumWide)
                }
                addCandidate(BannerFallbackLevel.LEVEL_4_ALBUM_ID_ARTWORK, albumArt)
            }
        }

        // Priority 5 Safety Net: Verified song thumbnail from its YouTube Music video ID
        val cleanVid = id.removePrefix("yt_").removePrefix("online_").removePrefix("api_").trim()
        if (cleanVid.length == 11 && !cleanVid.startsWith("PL") && !cleanVid.startsWith("UC")) {
            addCandidate(BannerFallbackLevel.LEVEL_5_VERIFIED_VIDEO_THUMBNAIL, "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg")
        }

        // Final safety net: if candidates is still empty, try displayArtwork or displayBannerArtwork
        if (candidates.isEmpty()) {
            val fallback = displayArtwork.ifBlank { displayBannerArtwork }.trim()
            if (fallback.isNotBlank() && fallback.startsWith("http")) {
                addCandidate(BannerFallbackLevel.LEVEL_3_SONG_SQUARE_LANDSCAPE, fallback)
            }
        }

        return candidates
    }
}

fun transformToLandscapeUrl(url: String?): String? {
    if (url.isNullOrBlank() || !url.startsWith("http")) return null
    var u = url.trim()
    if (u.startsWith("//")) {
        u = "https:$u"
    }
    if (u.contains("googleusercontent.com") || u.contains("=w") || u.contains("=s")) {
        u = u.replace(Regex("=w\\d+-h\\d+.*"), "=w1280-h720-l90-rj")
            .replace(Regex("(?<=[/=])s\\d+(-c)?(?=[/=.-]|$)"), "s1280")
            .replace(Regex("=s\\d+.*"), "=w1280-h720-l90-rj")
    }
    return u
}

fun isDedicatedWideBanner(url: String?): Boolean {
    if (url.isNullOrBlank() || !url.startsWith("http")) return false
    val u = url.trim()
    return u.contains("w1280-h720") || u.contains("w1280") || u.contains("w720") ||
           u.contains("s1280") || u.contains("maxres") || u.contains("hq720") ||
           (u.contains("hqdefault") && (u.contains("sqp=") || u.contains("ratio=")))
}

enum class BannerFallbackLevel(val code: Int, val description: String) {
    LEVEL_1_GENUINE_WIDE(1, "Genuine song-specific wide banner"),
    LEVEL_2_ASSOCIATED_ALBUM(2, "Genuine associated album wide artwork"),
    LEVEL_3_SONG_SQUARE_LANDSCAPE(3, "The same song's verified square artwork transformed into landscape background"),
    LEVEL_4_ALBUM_ID_ARTWORK(4, "The correct associated album's square artwork transformed into landscape background"),
    LEVEL_5_VERIFIED_VIDEO_THUMBNAIL(5, "Verified song thumbnail from YouTube Music video ID");

    companion object {
        val LEVEL_3_VERIFIED_VIDEO_THUMBNAIL get() = LEVEL_5_VERIFIED_VIDEO_THUMBNAIL
        val LEVEL_4_ALBUM_SQUARE_LANDSCAPE get() = LEVEL_4_ALBUM_ID_ARTWORK
        val LEVEL_5_SQUARE_LANDSCAPE get() = LEVEL_3_SONG_SQUARE_LANDSCAPE
    }
}

data class BannerCandidate(
    val level: BannerFallbackLevel,
    val url: String
)

data class Artist(
    val id: String,
    val name: String,
    val artwork: String,
    val bannerArtwork: String = "",
    val monthlyListeners: String = "2.4M",
    val bio: String = "Creating cinematic soundscapes and future-ambient electronics.",
    val genres: List<String> = listOf("Ambient", "Electronic", "Downtempo"),
    val popularTrackIds: List<String> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val singles: List<Album> = emptyList(),
    val videos: List<Track> = emptyList(),
    val featuredOn: List<Playlist> = emptyList(),
    val similarArtists: List<Artist> = emptyList()
) {
    val displayArtwork: String
        get() = artwork.ifBlank { bannerArtwork }
}

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val artwork: String,
    val bannerArtwork: String = "",
    val releaseYear: String = "2026",
    val genre: String = "Electronic",
    val type: String = "Album",
    val category: String = "",
    val trackIds: List<String> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val description: String = "",
    val trackCount: Int = if (tracks.isNotEmpty()) tracks.size else trackIds.size
) {
    val displayArtwork: String
        get() = artwork.ifBlank { bannerArtwork }
}

data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val artwork: String = "",
    val trackIds: List<String> = emptyList(),
    val isUserCreated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

enum class MemberStatus {
    ONLINE,
    RECONNECTING,
    BUFFERING,
    DISCONNECTED
}

data class Participant(
    val id: String,
    val name: String,
    val avatarColorHex: String = "#8B5CF6",
    val isHost: Boolean = false,
    val isListening: Boolean = true,
    val status: MemberStatus = MemberStatus.ONLINE
)

data class RoomQueueItem(
    val opId: String,
    val track: Track,
    val addedByUserId: String,
    val addedByUserName: String,
    val addedAtMs: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val senderAvatarColorHex: String = "#00F0FF",
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val sharedTrack: Track? = null,
    val isPending: Boolean = false,
    val isFailed: Boolean = false
)

data class LiveReaction(
    val id: String,
    val senderId: String,
    val senderName: String,
    val type: String,
    val emoji: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class SongRequestItem(
    val id: String,
    val track: Track,
    val requestedByUserId: String,
    val requestedByUserName: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class PublicRoomSummary(
    val roomId: String,
    val code: String,
    val id: String = roomId,
    val name: String,
    val description: String = "",
    val coverUrl: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val participantCount: Int = 1,
    val maxParticipants: Int = 20,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isLocked: Boolean = false
)

data class Room(
    val id: String,
    val name: String,
    val code: String,
    val hostId: String,
    val hostName: String = "",
    val description: String = "",
    val visibility: String = "PUBLIC",
    val coverUrl: String = "",
    val maxParticipants: Int = 20,
    val isLocked: Boolean = false,
    val isChatMuted: Boolean = false,
    val repeatMode: String = "OFF",
    val shuffleMode: Boolean = false,
    val isPublic: Boolean = true,
    val participants: List<Participant> = emptyList(),
    val currentTrackId: String? = null,
    val isPlaying: Boolean = true,
    val playbackPositionMs: Long = 0L,
    val currentTrack: Track? = null,
    val lastEventTimestamp: Long = 0L,
    val sequenceNumber: Long = 0L,
    val sharedQueue: List<RoomQueueItem> = emptyList(),
    val pendingSongRequests: List<SongRequestItem> = emptyList(),
    val queueRevision: Long = 0L,
    val chatMessages: List<ChatMessage> = emptyList(),
    val recentReactions: List<LiveReaction> = emptyList(),
    val isHostRecovering: Boolean = false
)

enum class VisualQuality(val label: String, val blobCount: Int, val description: String) {
    AUTO("Auto", 4, "Dynamically adjusts according to battery and device capabilities"),
    HIGH("High", 5, "Maximum fluid deformation, layered translucent depth & specular glass"),
    BALANCED("Balanced", 4, "Optimal 60 FPS performance and fluid elegance"),
    BATTERY_SAVER("Battery Saver", 3, "Subtle motion with reduced computational load")
}

data class QuickMix(
    val id: String,
    val name: String,
    val accentHex: String,
    val filterKeyword: String
)
