package com.example.service.importer.url

import com.example.service.importer.model.PlatformType
import java.net.URI

data class ParsedPlaylistUrl(
    val platform: PlatformType,
    val playlistId: String,
    val canonicalUrl: String
)

object PlaylistUrlParser {

    private val ALLOWED_HOSTS = setOf(
        "open.spotify.com",
        "music.youtube.com",
        "www.youtube.com",
        "youtube.com",
        "m.youtube.com",
        "youtu.be"
    )

    private val SPOTIFY_PLAYLIST_REGEX = Regex("""^([a-zA-Z0-9]{15,30})$""")
    private val YOUTUBE_PLAYLIST_REGEX = Regex("""^([a-zA-Z0-9_-]{12,64})$""")

    fun parse(rawInput: String): Result<ParsedPlaylistUrl> {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter a playlist link."))
        }

        // Support direct spotify:playlist:URI
        if (trimmed.startsWith("spotify:playlist:", ignoreCase = true)) {
            val id = trimmed.substringAfter("spotify:playlist:").substringBefore("?").trim()
            if (SPOTIFY_PLAYLIST_REGEX.matches(id)) {
                return Result.success(
                    ParsedPlaylistUrl(
                        platform = PlatformType.SPOTIFY,
                        playlistId = id,
                        canonicalUrl = "https://open.spotify.com/playlist/$id"
                    )
                )
            }
            return Result.failure(IllegalArgumentException("Invalid Spotify playlist ID format."))
        }

        val uri = try {
            val uriWithScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)) {
                "https://$trimmed"
            } else {
                trimmed
            }
            URI(uriWithScheme)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid URL syntax: ${e.message}"))
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return Result.failure(SecurityException("Only HTTPS/HTTP playlist links are supported."))
        }

        val host = uri.host?.lowercase()
        if (host == null || !ALLOWED_HOSTS.contains(host)) {
            return Result.failure(
                SecurityException("Unsupported or untrusted domain. Please use Spotify or YouTube Music playlist links.")
            )
        }

        // SSRF protection: reject loopback, internal IP ranges or hostnames
        if (isPrivateHost(host)) {
            return Result.failure(SecurityException("Access to internal network addresses is forbidden."))
        }

        val path = uri.path ?: ""

        // Spotify parser
        if (host == "open.spotify.com") {
            val segments = path.split("/").filter { it.isNotBlank() }
            val playlistIndex = segments.indexOf("playlist")
            if (playlistIndex != -1 && playlistIndex + 1 < segments.size) {
                val candidateId = segments[playlistIndex + 1].substringBefore("?").trim()
                if (SPOTIFY_PLAYLIST_REGEX.matches(candidateId)) {
                    return Result.success(
                        ParsedPlaylistUrl(
                            platform = PlatformType.SPOTIFY,
                            playlistId = candidateId,
                            canonicalUrl = "https://open.spotify.com/playlist/$candidateId"
                        )
                    )
                }
            }
            return Result.failure(IllegalArgumentException("Invalid Spotify playlist URL. Expected https://open.spotify.com/playlist/{id}"))
        }

        // YouTube Music & YouTube parser
        if (host.contains("youtube.com") || host == "youtu.be") {
            val queryParams = parseQueryParams(uri.rawQuery ?: "")
            var listId = queryParams["list"]

            // Check /playlist?list=...
            if (listId.isNullOrBlank() && path.contains("playlist")) {
                listId = queryParams["list"]
            }

            if (!listId.isNullOrBlank()) {
                val cleanId = listId.trim()
                if (YOUTUBE_PLAYLIST_REGEX.matches(cleanId)) {
                    return Result.success(
                        ParsedPlaylistUrl(
                            platform = PlatformType.YOUTUBE_MUSIC,
                            playlistId = cleanId,
                            canonicalUrl = "https://music.youtube.com/playlist?list=$cleanId"
                        )
                    )
                }
            }

            return Result.failure(IllegalArgumentException("Invalid YouTube Music playlist URL. Expected https://music.youtube.com/playlist?list={id}"))
        }

        return Result.failure(IllegalArgumentException("Unsupported playlist platform."))
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.isNotEmpty()) {
                val key = parts[0].trim()
                val value = if (parts.size > 1) parts[1].trim() else ""
                key to value
            } else null
        }.toMap()
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return true
        if (host.matches(Regex("""^127\.\d+\.\d+\.\d+$"""))) return true
        if (host.matches(Regex("""^10\.\d+\.\d+\.\d+$"""))) return true
        if (host.matches(Regex("""^192\.168\.\d+\.\d+$"""))) return true
        if (host.matches(Regex("""^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$"""))) return true
        if (host.matches(Regex("""^169\.254\.\d+\.\d+$"""))) return true
        return false
    }
}

