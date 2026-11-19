package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserEntity(
    @PrimaryKey val id: String = "me",
    val name: String = "Gaurav",
    val visualQuality: String = "BALANCED",
    val themeMode: String = "DARK"
)

@Entity(
    tableName = "liked_tracks",
    primaryKeys = ["userId", "trackId"]
)
data class LikedTrackEntity(
    val userId: String,
    val trackId: String,
    val likedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlists",
    indices = [androidx.room.Index(value = ["userId"])]
)
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val description: String = "",
    val artwork: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [
        androidx.room.Index(value = ["userId"]),
        androidx.room.Index(value = ["playlistId"])
    ]
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val userId: String,
    val position: Int = 0
)

@Entity(
    tableName = "recently_played",
    primaryKeys = ["userId", "trackId"]
)
data class RecentlyPlayedEntity(
    val userId: String,
    val trackId: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "track_metadata")
data class TrackMetadataEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "art_1",
    val album: String = "Flins Records",
    val albumId: String = "alb_1",
    val artwork: String,
    val bannerArtwork: String = "",
    val durationSeconds: Int,
    val audioUrl: String? = null,
    val genre: String = "Electronic",
    val bpm: Int = 120,
    val energy: Float = 0.7f,
    val mood: String = "Chill",
    val paletteColorHex: String = "#00F0FF",
    val cachedAt: Long = System.currentTimeMillis()
)
