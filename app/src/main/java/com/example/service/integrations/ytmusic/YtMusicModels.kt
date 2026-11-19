/*
 * Copyright (C) 2024–2026 Nguyễn Đức Tuấn Minh (maxrave-dev) <ndtminh2608@gmail.com>
 * Portions adapted from SimpMusic (commit f037043d714450bac5aaac2f9dbf77e17b019303)
 * and maxrave-dev/core (commit 7d11cf437e59ba4f348ec92f7558732ee3d34c1a).
 *
 * Licensed under the GNU General Public License Version 3.0 (GPL-3.0-only).
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *     https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Modified for FlinsMusic on 2026-10-19:
 * Models for public YouTube Music metadata and domain conversion to FlinsMusic models.
 */

package com.example.service.integrations.ytmusic

import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.Track

data class YtTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val album: String = "",
    val albumId: String = "",
    val artwork: String = "",
    val bannerArtwork: String = "",
    val durationSeconds: Int = 180,
    val isExplicit: Boolean = false
) {
    fun toTrack(): Track {
        val videoId = id.removePrefix("yt_").trim()
        val resolvedBanner = bannerArtwork.ifBlank {
            if (videoId.length == 11) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else artwork
        }
        val resolvedArtwork = artwork.ifBlank {
            if (videoId.length == 11) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else resolvedBanner
        }
        return Track(
            id = "yt_$videoId",
            title = title.ifBlank { "Unknown Track" },
            artist = artist.ifBlank { "YouTube Music" },
            artistId = artistId.ifBlank { "art_${artist.lowercase().replace(" ", "_")}" },
            album = album.ifBlank { "Single" },
            albumId = albumId.ifBlank { "alb_yt" },
            artwork = resolvedArtwork,
            bannerArtwork = resolvedBanner,
            durationSeconds = durationSeconds.coerceAtLeast(10),
            audioUrl = null, // Audio stream is resolved on-demand via on-device yt-dlp
            genre = "Trending",
            bpm = 120,
            energy = 0.8f,
            mood = "Stream",
            paletteColorHex = "#00F0FF"
        )
    }
}

data class YtAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val artwork: String = "",
    val bannerArtwork: String = "",
    val year: String = "",
    val tracks: List<YtTrack> = emptyList(),
    val description: String = "",
    val trackCount: Int = 0,
    val durationText: String = "",
    val type: String = "Album",
    val category: String = ""
) {
    fun toAlbum(fallbackAlbum: Album? = null): Album {
        val resolvedTitle = when {
            title.isNotBlank() && title != "Unknown Album" -> title
            !fallbackAlbum?.title.isNullOrBlank() && fallbackAlbum?.title != "Unknown Album" -> fallbackAlbum!!.title
            else -> title.ifBlank { "Unknown Album" }
        }
        val resolvedArtist = when {
            artist.isNotBlank() && artist != "Various Artists" -> artist
            !fallbackAlbum?.artist.isNullOrBlank() && fallbackAlbum?.artist != "Various Artists" -> fallbackAlbum!!.artist
            else -> artist.ifBlank { "Various Artists" }
        }
        val resolvedArtistId = when {
            artistId.isNotBlank() -> artistId
            !fallbackAlbum?.artistId.isNullOrBlank() -> fallbackAlbum!!.artistId
            else -> ""
        }
        val resolvedArtwork = when {
            artwork.isNotBlank() -> artwork
            !fallbackAlbum?.artwork.isNullOrBlank() -> fallbackAlbum!!.artwork
            bannerArtwork.isNotBlank() -> bannerArtwork
            !fallbackAlbum?.bannerArtwork.isNullOrBlank() -> fallbackAlbum!!.bannerArtwork
            else -> ""
        }
        val resolvedBanner = when {
            bannerArtwork.isNotBlank() && bannerArtwork != artwork -> bannerArtwork
            !fallbackAlbum?.bannerArtwork.isNullOrBlank() -> fallbackAlbum!!.bannerArtwork
            bannerArtwork.isNotBlank() -> bannerArtwork
            resolvedArtwork.isNotBlank() -> resolvedArtwork
            else -> ""
        }
        val resolvedYear = when {
            year.isNotBlank() -> year
            !fallbackAlbum?.releaseYear.isNullOrBlank() -> fallbackAlbum!!.releaseYear
            else -> "2024"
        }
        val resolvedType = when {
            type.isNotBlank() -> type
            !fallbackAlbum?.type.isNullOrBlank() -> fallbackAlbum!!.type
            resolvedTitle.contains("EP", ignoreCase = true) -> "EP"
            resolvedTitle.contains("Single", ignoreCase = true) || tracks.size == 1 -> "Single"
            else -> "Album"
        }
        val resolvedId = when {
            id.isNotBlank() && !id.startsWith("alb_") -> id
            !fallbackAlbum?.id.isNullOrBlank() && !fallbackAlbum!!.id.startsWith("alb_") -> fallbackAlbum!!.id
            id.isNotBlank() -> id
            !fallbackAlbum?.id.isNullOrBlank() -> fallbackAlbum!!.id
            else -> "alb_${System.currentTimeMillis()}"
        }
        val domainTracks = tracks.map { it.toTrack() }
        return Album(
            id = resolvedId,
            title = resolvedTitle,
            artist = resolvedArtist,
            artistId = resolvedArtistId,
            artwork = resolvedArtwork,
            bannerArtwork = resolvedBanner,
            releaseYear = resolvedYear,
            genre = if (resolvedType != "Album") resolvedType else (fallbackAlbum?.genre?.takeIf { it.isNotBlank() } ?: "Pop / Hits"),
            type = resolvedType,
            category = category.ifBlank { fallbackAlbum?.category ?: "" },
            trackIds = domainTracks.map { it.id },
            tracks = domainTracks,
            description = description.ifBlank { fallbackAlbum?.description ?: "" },
            trackCount = if (trackCount > 0) trackCount else if (domainTracks.isNotEmpty()) domainTracks.size else fallbackAlbum?.trackCount ?: 0
        )
    }
}

data class YtArtist(
    val id: String,
    val name: String,
    val artwork: String = "",
    val bannerArtwork: String = "",
    val bio: String = "",
    val monthlyListeners: String = "",
    val topTracks: List<YtTrack> = emptyList(),
    val albums: List<YtAlbum> = emptyList(),
    val singles: List<YtAlbum> = emptyList(),
    val videos: List<YtTrack> = emptyList(),
    val featuredOn: List<YtPlaylist> = emptyList(),
    val similarArtists: List<YtArtist> = emptyList(),
    val songsBrowseEndpoint: Pair<String, String?>? = null,
    val albumsBrowseEndpoint: Pair<String, String?>? = null,
    val singlesBrowseEndpoint: Pair<String, String?>? = null,
    val featuredBrowseEndpoint: Pair<String, String?>? = null
) {
    fun toArtist(): Artist {
        val mappedTracks = topTracks.map { it.toTrack() }
        val resolvedArtwork = artwork.ifBlank { bannerArtwork }
        return Artist(
            id = id.ifBlank { "art_${name.lowercase().replace(" ", "_")}" },
            name = name.ifBlank { "Unknown Artist" },
            artwork = resolvedArtwork,
            bannerArtwork = bannerArtwork.ifBlank { resolvedArtwork },
            monthlyListeners = monthlyListeners.ifBlank { "" },
            bio = bio,
            genres = listOf("Trending", "Global Hits"),
            popularTrackIds = mappedTracks.map { it.id },
            tracks = mappedTracks,
            albums = albums.map { it.toAlbum() },
            singles = singles.map { it.toAlbum() },
            videos = videos.map { it.toTrack() },
            featuredOn = featuredOn.map { it.toPlaylist() },
            similarArtists = similarArtists.map { it.toArtist() }
        )
    }
}

data class YtPlaylist(
    val id: String,
    val title: String,
    val author: String = "YouTube Music",
    val description: String = "",
    val artwork: String = "",
    val trackCount: Int = 0,
    val tracks: List<YtTrack> = emptyList(),
    val continuationToken: String? = null
) {
    fun toPlaylist(): Playlist {
        val mappedTracks = tracks.map { it.toTrack() }
        return Playlist(
            id = id.ifBlank { "pl_yt_${System.currentTimeMillis()}" },
            name = title.ifBlank { "YouTube Playlist" },
            description = description.ifBlank { "Public YouTube Music playlist by $author" },
            artwork = artwork.ifBlank { mappedTracks.firstOrNull()?.artwork ?: "" },
            trackIds = mappedTracks.map { it.id },
            isUserCreated = false,
            createdAt = System.currentTimeMillis()
        )
    }
}

data class YtHomeShelf(
    val title: String,
    val subtitle: String = "",
    val items: List<YtTrack> = emptyList(),
    val albums: List<YtAlbum> = emptyList(),
    val artists: List<YtArtist> = emptyList(),
    val playlists: List<YtPlaylist> = emptyList()
)

data class YtSearchResult(
    val tracks: List<YtTrack> = emptyList(),
    val albums: List<YtAlbum> = emptyList(),
    val artists: List<YtArtist> = emptyList(),
    val playlists: List<YtPlaylist> = emptyList(),
    val continuationToken: String? = null
)

data class YtAccount(
    val id: String,
    val name: String,
    val email: String = "",
    val avatarUrl: String = "",
    val cookie: String = "",
    val pageId: String? = null,
    val authUser: Int = 0,
    val isActive: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

