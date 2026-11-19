package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.LikedTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistTrackEntity
import com.example.data.local.entity.RecentlyPlayedEntity
import com.example.data.local.entity.TrackMetadataEntity
import com.example.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    // User Profile
    @Query("SELECT * FROM user_profile WHERE id = 'me'")
    fun getUserProfile(): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateUser(user: UserEntity)

    // Liked Tracks (Isolated per userId)
    @Query("SELECT * FROM liked_tracks WHERE userId = :userId ORDER BY likedAt DESC")
    fun getLikedTracks(userId: String): Flow<List<LikedTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedTrack(likedTrack: LikedTrackEntity)

    @Query("DELETE FROM liked_tracks WHERE userId = :userId AND trackId = :trackId")
    suspend fun deleteLikedTrack(userId: String, trackId: String)

    @Query("DELETE FROM liked_tracks WHERE userId = :userId")
    suspend fun clearLikedTracks(userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE userId = :userId AND trackId = :trackId)")
    fun isTrackLiked(userId: String, trackId: String): Flow<Boolean>

    // Playlists (Isolated per userId)
    @Query("SELECT * FROM playlists WHERE userId = :userId ORDER BY createdAt DESC")
    fun getAllPlaylists(userId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun getPlaylistsSnapshot(userId: String): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId AND userId = :userId")
    suspend fun getPlaylistById(playlistId: String, userId: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :newName WHERE id = :playlistId AND userId = :userId")
    suspend fun renamePlaylist(playlistId: String, newName: String, userId: String)

    @Query("UPDATE playlists SET name = :newName, description = :newDescription, artwork = :newArtwork WHERE id = :playlistId AND userId = :userId")
    suspend fun updatePlaylistDetails(playlistId: String, newName: String, newDescription: String, newArtwork: String, userId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId AND userId = :userId")
    suspend fun deletePlaylist(playlistId: String, userId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND userId = :userId")
    suspend fun deletePlaylistTracks(playlistId: String, userId: String)

    // Playlist Tracks (Isolated per userId)
    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId AND userId = :userId ORDER BY position ASC")
    fun getPlaylistTrackIds(playlistId: String, userId: String): Flow<List<String>>

    @Query("""
        SELECT tm.* FROM track_metadata tm
        INNER JOIN playlist_tracks pt ON tm.id = pt.trackId
        WHERE pt.playlistId = :playlistId AND pt.userId = :userId
        ORDER BY pt.position ASC
    """)
    fun getPlaylistTracksMetadata(playlistId: String, userId: String): Flow<List<TrackMetadataEntity>>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getPlaylistTrackIds(playlistId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(entry: PlaylistTrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(entry: PlaylistTrackEntity): Long

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId AND userId = :userId")
    suspend fun updateTrackPosition(playlistId: String, trackId: String, position: Int, userId: String)

    @Query("SELECT position FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId AND userId = :userId LIMIT 1")
    suspend fun getPlaylistTrackPosition(playlistId: String, trackId: String, userId: String): Int?

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId AND userId = :userId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String, userId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId IN (:trackIds) AND userId = :userId")
    suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>, userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId AND userId = :userId)")
    fun isTrackInPlaylist(playlistId: String, trackId: String, userId: String): Flow<Boolean>

    @Query("SELECT COUNT(*) > 0 FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId AND userId = :userId")
    suspend fun hasTrack(playlistId: String, trackId: String, userId: String): Boolean

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId AND userId = :userId")
    suspend fun getPlaylistTrackCount(playlistId: String, userId: String): Int

    // Recently Played (Isolated per userId)
    @Query("SELECT * FROM recently_played WHERE userId = :userId ORDER BY playedAt DESC LIMIT 30")
    fun getRecentlyPlayed(userId: String): Flow<List<RecentlyPlayedEntity>>

    @Query("""
        SELECT tm.* FROM track_metadata tm
        INNER JOIN recently_played rp ON tm.id = rp.trackId
        WHERE rp.userId = :userId
        ORDER BY rp.playedAt DESC LIMIT 30
    """)
    fun getRecentlyPlayedMetadata(userId: String): Flow<List<TrackMetadataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentlyPlayed(recent: RecentlyPlayedEntity)

    @Query("DELETE FROM recently_played WHERE userId = :userId")
    suspend fun clearRecentlyPlayed(userId: String)

    // Cached Track Metadata
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackMetadata(track: TrackMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracksMetadata(tracks: List<TrackMetadataEntity>)

    @Query("SELECT * FROM track_metadata WHERE id = :trackId")
    suspend fun getTrackMetadataById(trackId: String): TrackMetadataEntity?

    @Query("SELECT * FROM track_metadata")
    fun getAllCachedTrackMetadata(): Flow<List<TrackMetadataEntity>>
}
