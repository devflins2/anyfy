package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.LikedTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistTrackEntity
import com.example.data.local.entity.RecentlyPlayedEntity
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migration_fromVersion2To3_preservesAllRecordsAndAssignsGuestLocal() = runTest {
        val dbFile = context.getDatabasePath("test_migration_v2_to_v3.db")
        if (dbFile.exists()) dbFile.delete()

        // 1. Create a raw SQLite database at Schema Version 2
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_v2_to_v3.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create v2 tables exactly as they existed in Version 2
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `user_profile` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `visualQuality` TEXT NOT NULL,
                            `themeMode` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `liked_tracks` (
                            `trackId` TEXT NOT NULL,
                            `likedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`trackId`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `playlists` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                            `playlistId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `position` INTEGER NOT NULL,
                            PRIMARY KEY(`playlistId`, `trackId`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `recently_played` (
                            `trackId` TEXT NOT NULL,
                            `playedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`trackId`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `track_metadata` (
                            `id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `artist` TEXT NOT NULL,
                            `artistId` TEXT NOT NULL,
                            `album` TEXT NOT NULL,
                            `albumId` TEXT NOT NULL,
                            `artwork` TEXT NOT NULL,
                            `durationSeconds` INTEGER NOT NULL,
                            `audioUrl` TEXT,
                            `genre` TEXT NOT NULL,
                            `bpm` INTEGER NOT NULL,
                            `energy` REAL NOT NULL,
                            `mood` TEXT NOT NULL,
                            `paletteColorHex` TEXT NOT NULL,
                            `cachedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
        val v2Db = openHelper.writableDatabase

        // Populate Version 2 test data
        v2Db.execSQL("INSERT INTO `user_profile` VALUES ('me', 'Gaurav', 'BALANCED', 'DARK')")
        v2Db.execSQL("INSERT INTO `liked_tracks` VALUES ('trk_kesariya', 1000)")
        v2Db.execSQL("INSERT INTO `liked_tracks` VALUES ('trk_blinding_lights', 2000)")
        v2Db.execSQL("INSERT INTO `playlists` VALUES ('pl_rock', 'Rock Hits', 'Classic rock collection', 3000)")
        v2Db.execSQL("INSERT INTO `playlist_tracks` VALUES ('pl_rock', 'trk_believer', 0)")
        v2Db.execSQL("INSERT INTO `playlist_tracks` VALUES ('pl_rock', 'trk_lover', 1)")
        v2Db.execSQL("INSERT INTO `recently_played` VALUES ('trk_kesariya', 4000)")
        v2Db.execSQL("INSERT INTO `recently_played` VALUES ('trk_lover', 5000)")
        v2Db.execSQL("""
            INSERT INTO `track_metadata` VALUES (
                'trk_kesariya', 'Kesariya', 'Arijit Singh', 'art_arijit', 'Brahmāstra', 'alb_brahmastra',
                'https://art.jpg', 268, 'https://audio.mp4', 'Bollywood', 98, 0.72, 'Romance', '#F59E0B', 6000
            )
        """.trimIndent())
        v2Db.close()

        // 2. Open via Room database at Version 6 with MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, and MIGRATION_5_6
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_migration_v2_to_v3.db")
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()

        val dao = roomDb.musicDao()

        // 3. Verify user profile preserved
        val user = dao.getUserProfile().first()
        assertNotNull(user)
        assertEquals("Gaurav", user?.name)

        // 4. Verify liked tracks preserved and migrated to 'guest_local'
        val guestLikes = dao.getLikedTracks("guest_local").first()
        assertEquals(2, guestLikes.size)
        val kesariyaLike = guestLikes.find { it.trackId == "trk_kesariya" }
        assertNotNull(kesariyaLike)
        assertEquals("guest_local", kesariyaLike?.userId)
        assertEquals(1000L, kesariyaLike?.likedAt)

        val blindingLike = guestLikes.find { it.trackId == "trk_blinding_lights" }
        assertNotNull(blindingLike)
        assertEquals("guest_local", blindingLike?.userId)
        assertEquals(2000L, blindingLike?.likedAt)

        // 5. Verify playlists preserved with userId = 'guest_local'
        val guestPlaylists = dao.getAllPlaylists("guest_local").first()
        assertEquals(1, guestPlaylists.size)
        val playlist = guestPlaylists[0]
        assertEquals("pl_rock", playlist.id)
        assertEquals("guest_local", playlist.userId)
        assertEquals("Rock Hits", playlist.name)
        assertEquals("Classic rock collection", playlist.description)
        assertEquals(3000L, playlist.createdAt)

        // 6. Verify playlist tracks and ordering remain intact and are scoped to guest_local
        val tracks = dao.getPlaylistTrackIds("pl_rock", "guest_local").first()
        assertEquals(listOf("trk_believer", "trk_lover"), tracks)

        // 7. Verify recently played preserved with userId = 'guest_local'
        val guestRecent = dao.getRecentlyPlayed("guest_local").first()
        assertEquals(2, guestRecent.size)
        // ORDER BY playedAt DESC: trk_lover (5000) before trk_kesariya (4000)
        assertEquals("trk_lover", guestRecent[0].trackId)
        assertEquals("guest_local", guestRecent[0].userId)
        assertEquals(5000L, guestRecent[0].playedAt)
        assertEquals("trk_kesariya", guestRecent[1].trackId)
        assertEquals("guest_local", guestRecent[1].userId)
        assertEquals(4000L, guestRecent[1].playedAt)

        // 8. Verify track metadata cache preserved
        val metadata = dao.getTrackMetadataById("trk_kesariya")
        assertNotNull(metadata)
        assertEquals("Kesariya", metadata?.title)
        assertEquals("Arijit Singh", metadata?.artist)

        roomDb.close()
        dbFile.delete()
    }

    @Test
    fun multiUser_independentLikes_twoUsersCanLikeSameTrackWithoutConflict() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.musicDao()

        // User A and User B like the same track
        dao.insertLikedTrack(LikedTrackEntity(userId = "usr_alice", trackId = "trk_kesariya", likedAt = 100L))
        dao.insertLikedTrack(LikedTrackEntity(userId = "usr_bob", trackId = "trk_kesariya", likedAt = 200L))

        // Both records exist independently
        val aliceLikes = dao.getLikedTracks("usr_alice").first()
        val bobLikes = dao.getLikedTracks("usr_bob").first()

        assertEquals(1, aliceLikes.size)
        assertEquals("trk_kesariya", aliceLikes[0].trackId)
        assertEquals("usr_alice", aliceLikes[0].userId)

        assertEquals(1, bobLikes.size)
        assertEquals("trk_kesariya", bobLikes[0].trackId)
        assertEquals("usr_bob", bobLikes[0].userId)

        // Alice unlikes the track -> Bob's like remains intact
        dao.deleteLikedTrack("usr_alice", "trk_kesariya")

        val aliceLikesAfter = dao.getLikedTracks("usr_alice").first()
        val bobLikesAfter = dao.getLikedTracks("usr_bob").first()

        assertTrue(aliceLikesAfter.isEmpty())
        assertEquals(1, bobLikesAfter.size)
        assertEquals("trk_kesariya", bobLikesAfter[0].trackId)

        db.close()
    }

    @Test
    fun multiUser_dataIsolation_likesAndHistoryNeverAppearForAnotherUser() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.musicDao()

        // Alice adds likes and history
        dao.insertLikedTrack(LikedTrackEntity(userId = "usr_alice", trackId = "trk_starboy"))
        dao.insertRecentlyPlayed(RecentlyPlayedEntity(userId = "usr_alice", trackId = "trk_blinding_lights", playedAt = 1000L))

        // Bob adds likes and history
        dao.insertLikedTrack(LikedTrackEntity(userId = "usr_bob", trackId = "trk_lover"))
        dao.insertRecentlyPlayed(RecentlyPlayedEntity(userId = "usr_bob", trackId = "trk_kesariya", playedAt = 2000L))

        // Verify Alice sees only her data
        val aliceLikes = dao.getLikedTracks("usr_alice").first().map { it.trackId }
        val aliceRecent = dao.getRecentlyPlayed("usr_alice").first().map { it.trackId }
        assertEquals(listOf("trk_starboy"), aliceLikes)
        assertEquals(listOf("trk_blinding_lights"), aliceRecent)

        // Verify Bob sees only his data
        val bobLikes = dao.getLikedTracks("usr_bob").first().map { it.trackId }
        val bobRecent = dao.getRecentlyPlayed("usr_bob").first().map { it.trackId }
        assertEquals(listOf("trk_lover"), bobLikes)
        assertEquals(listOf("trk_kesariya"), bobRecent)

        // Verify guest_local sees neither
        val guestLikes = dao.getLikedTracks("guest_local").first()
        val guestRecent = dao.getRecentlyPlayed("guest_local").first()
        assertTrue(guestLikes.isEmpty())
        assertTrue(guestRecent.isEmpty())

        db.close()
    }

    @Test
    fun multiUser_playlists_areIsolatedPerUser() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.musicDao()

        dao.insertPlaylist(PlaylistEntity(id = "pl_alice_1", userId = "usr_alice", name = "Alice Playlist"))
        dao.insertPlaylist(PlaylistEntity(id = "pl_bob_1", userId = "usr_bob", name = "Bob Playlist"))

        val alicePlaylists = dao.getAllPlaylists("usr_alice").first()
        val bobPlaylists = dao.getAllPlaylists("usr_bob").first()

        assertEquals(1, alicePlaylists.size)
        assertEquals("Alice Playlist", alicePlaylists[0].name)

        assertEquals(1, bobPlaylists.size)
        assertEquals("Bob Playlist", bobPlaylists[0].name)

        // Alice renames her playlist
        dao.renamePlaylist("pl_alice_1", "Alice Favorites", "usr_alice")
        val aliceUpdated = dao.getAllPlaylists("usr_alice").first()
        assertEquals("Alice Favorites", aliceUpdated[0].name)

        // Bob cannot rename Alice's playlist
        dao.renamePlaylist("pl_alice_1", "Hacked By Bob", "usr_bob")
        val aliceStillSafe = dao.getAllPlaylists("usr_alice").first()
        assertEquals("Alice Favorites", aliceStillSafe[0].name)

        // Bob cannot delete Alice's playlist
        dao.deletePlaylist("pl_alice_1", "usr_bob")
        val aliceStillExists = dao.getAllPlaylists("usr_alice").first()
        assertEquals(1, aliceStillExists.size)

        // Alice deletes her own playlist
        dao.deletePlaylist("pl_alice_1", "usr_alice")
        assertTrue(dao.getAllPlaylists("usr_alice").first().isEmpty())
        assertEquals(1, dao.getAllPlaylists("usr_bob").first().size)

        db.close()
    }

    @Test
    fun repository_userIsolationAndSignOut_clearsSessionWithoutLeakingData() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MusicRepository(db.musicDao())

        // 1. Initial unauthenticated state: currentUserId is empty string
        assertEquals("", repository.currentUserId.value)
        assertTrue(repository.likedTrackIds.first().isEmpty())
        assertTrue(repository.playlists.first().isEmpty())
        assertTrue(repository.recentlyPlayedTracks.first().isEmpty())

        // Unauthenticated mutations are safely ignored
        repository.toggleLike("trk_lover")
        val emptyPlaylistId = repository.createPlaylist("Unauth Party")
        assertEquals("", emptyPlaylistId)
        assertTrue(repository.likedTrackIds.first().isEmpty())
        assertTrue(repository.playlists.first().isEmpty())

        // 2. User signs in as Alice (usr_alice)
        repository.setCurrentUserId("usr_alice")
        assertEquals("usr_alice", repository.currentUserId.value)

        // Alice's library is initially empty
        assertTrue(repository.likedTrackIds.first().isEmpty())
        assertTrue(repository.playlists.first().isEmpty())

        // Alice likes a track and creates a playlist
        repository.toggleLike("trk_kesariya")
        val alicePlaylistId = repository.createPlaylist("Alice Melodies")
        repository.recordRecentlyPlayed("trk_kesariya")

        assertEquals(setOf("trk_kesariya"), repository.likedTrackIds.first())
        assertEquals(1, repository.playlists.first().size)
        assertEquals("Alice Melodies", repository.playlists.first()[0].name)

        // 3. Alice signs out -> active userId is cleared to empty string
        repository.setCurrentUserId(null)
        assertEquals("", repository.currentUserId.value)

        // UI flows are immediately cleared — no data leakage
        assertTrue(repository.likedTrackIds.first().isEmpty())
        assertTrue(repository.playlists.first().isEmpty())
        assertTrue(repository.recentlyPlayedTracks.first().isEmpty())

        // 4. User signs in as Bob (usr_bob)
        repository.setCurrentUserId("usr_bob")
        assertEquals("usr_bob", repository.currentUserId.value)

        // Bob's data is isolated from Alice
        assertTrue(repository.likedTrackIds.first().isEmpty())
        assertTrue(repository.playlists.first().isEmpty())

        // Bob creates his own data
        repository.toggleLike("trk_lover")
        repository.createPlaylist("Bob Rock")
        repository.recordRecentlyPlayed("trk_lover")

        assertEquals(setOf("trk_lover"), repository.likedTrackIds.first())
        assertEquals(1, repository.playlists.first().size)
        assertEquals("Bob Rock", repository.playlists.first()[0].name)

        // 5. Bob signs out
        repository.setCurrentUserId(null)
        assertEquals("", repository.currentUserId.value)
        assertTrue(repository.likedTrackIds.first().isEmpty())

        // 6. Alice signs back in -> Alice's data is fully preserved and intact
        repository.setCurrentUserId("usr_alice")
        assertEquals(setOf("trk_kesariya"), repository.likedTrackIds.first())
        assertEquals(1, repository.playlists.first().size)
        assertEquals("Alice Melodies", repository.playlists.first()[0].name)

        db.close()
    }

    @Test
    fun migration_fromVersion3To4_populatesPlaylistTracksUserIdFromPlaylists() = runTest {
        val dbFile = context.getDatabasePath("test_migration_v3_to_v4.db")
        if (dbFile.exists()) dbFile.delete()

        // 1. Create a raw SQLite database at Schema Version 3
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_v3_to_v4.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `user_profile` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `visualQuality` TEXT NOT NULL,
                            `themeMode` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `liked_tracks` (
                            `userId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `likedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`userId`, `trackId`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `playlists` (
                            `id` TEXT NOT NULL,
                            `userId` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_userId` ON `playlists` (`userId`)")

                    // v3 playlist_tracks has no userId column
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                            `playlistId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `position` INTEGER NOT NULL,
                            PRIMARY KEY(`playlistId`, `trackId`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `recently_played` (
                            `userId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `playedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`userId`, `trackId`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `track_metadata` (
                            `id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `artist` TEXT NOT NULL,
                            `artistId` TEXT NOT NULL,
                            `album` TEXT NOT NULL,
                            `albumId` TEXT NOT NULL,
                            `artwork` TEXT NOT NULL,
                            `durationSeconds` INTEGER NOT NULL,
                            `audioUrl` TEXT,
                            `genre` TEXT NOT NULL,
                            `bpm` INTEGER NOT NULL,
                            `energy` REAL NOT NULL,
                            `mood` TEXT NOT NULL,
                            `paletteColorHex` TEXT NOT NULL,
                            `cachedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
        val v3Db = openHelper.writableDatabase

        // Alice's playlist with 2 tracks
        v3Db.execSQL("INSERT INTO `playlists` VALUES ('pl_alice_1', 'usr_alice', 'Alice Favorites', 'Best songs', 1000)")
        v3Db.execSQL("INSERT INTO `playlist_tracks` VALUES ('pl_alice_1', 'trk_kesariya', 0)")
        v3Db.execSQL("INSERT INTO `playlist_tracks` VALUES ('pl_alice_1', 'trk_blinding_lights', 1)")

        // Bob's playlist with 1 track
        v3Db.execSQL("INSERT INTO `playlists` VALUES ('pl_bob_1', 'usr_bob', 'Bob Vibes', 'Chill beats', 2000)")
        v3Db.execSQL("INSERT INTO `playlist_tracks` VALUES ('pl_bob_1', 'trk_lover', 0)")

        v3Db.close()

        // 2. Open via Room database at Version 6 with MIGRATION_3_4, MIGRATION_4_5, and MIGRATION_5_6
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_migration_v3_to_v4.db")
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()

        val dao = roomDb.musicDao()

        // 3. Verify Alice's tracks are properly scoped to usr_alice
        val aliceTracks = dao.getPlaylistTrackIds("pl_alice_1", "usr_alice").first()
        assertEquals(listOf("trk_kesariya", "trk_blinding_lights"), aliceTracks)

        // 4. Verify Bob cannot see Alice's playlist tracks
        val bobSneaking = dao.getPlaylistTrackIds("pl_alice_1", "usr_bob").first()
        assertTrue("Bob must never see Alice's playlist tracks", bobSneaking.isEmpty())

        // 5. Verify Bob's tracks are properly scoped to usr_bob
        val bobTracks = dao.getPlaylistTrackIds("pl_bob_1", "usr_bob").first()
        assertEquals(listOf("trk_lover"), bobTracks)

        // 6. Verify Alice cannot see Bob's playlist tracks
        val aliceSneaking = dao.getPlaylistTrackIds("pl_bob_1", "usr_alice").first()
        assertTrue("Alice must never see Bob's playlist tracks", aliceSneaking.isEmpty())

        roomDb.close()
        dbFile.delete()
    }

    @Test
    fun playlistTrackIsolation_userBCannotSeeOrModifyUserAPlaylistTracks() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MusicRepository(db.musicDao())

        // 1. User Alice signs in and creates playlist
        repository.setCurrentUserId("usr_alice")
        val alicePlId = repository.createPlaylist("Alice Secrets", "Private")
        val add1 = repository.addTrackToPlaylist(alicePlId, "trk_kesariya", "usr_alice")
        val add2 = repository.addTrackToPlaylist(alicePlId, "trk_blinding_lights", "usr_alice")
        assertTrue(add1)
        assertTrue(add2)

        val alicePlaylists = repository.playlists.first()
        assertEquals(1, alicePlaylists.size)
        assertEquals(listOf("trk_kesariya", "trk_blinding_lights"), alicePlaylists[0].trackIds)

        // 2. User Bob signs in
        repository.setCurrentUserId("usr_bob")

        // Bob sees zero playlists
        assertTrue("Bob cannot see Alice's playlists", repository.playlists.first().isEmpty())

        // Bob attempts to add a song to Alice's playlist
        val bobAddAttempt = repository.addTrackToPlaylist(alicePlId, "trk_lover", "usr_bob")
        assertFalse("Bob cannot add tracks to Alice's playlist", bobAddAttempt)

        // Bob attempts to remove Alice's song
        repository.removeTrackFromPlaylist(alicePlId, "trk_kesariya", "usr_bob")

        // Bob attempts to delete Alice's playlist
        repository.deletePlaylist(alicePlId, "usr_bob")

        // 3. User Alice signs back in -> verify her playlist and songs are 100% unaltered
        repository.setCurrentUserId("usr_alice")
        val alicePlaylistsAfter = repository.playlists.first()
        assertEquals(1, alicePlaylistsAfter.size)
        assertEquals("Alice Secrets", alicePlaylistsAfter[0].name)
        assertEquals(listOf("trk_kesariya", "trk_blinding_lights"), alicePlaylistsAfter[0].trackIds)

        db.close()
    }

    @Test
    fun addToPlaylist_preventsDuplicatesAndPersistsAcrossInstances() = runTest {
        val dbFile = context.getDatabasePath("test_playlist_persistence.db")
        if (dbFile.exists()) dbFile.delete()

        val db1 = Room.databaseBuilder(context, AppDatabase::class.java, "test_playlist_persistence.db")
            .allowMainThreadQueries()
            .build()
        val repo1 = MusicRepository(db1.musicDao())

        repo1.setCurrentUserId("usr_alice")
        val plId = repo1.createPlaylist("Morning Energy")

        // Add track first time -> success
        val addedFirstTime = repo1.addTrackToPlaylist(plId, "trk_starboy", "usr_alice")
        assertTrue("First addition must succeed", addedFirstTime)

        // Duplicate addition -> prevented
        val addedSecondTime = repo1.addTrackToPlaylist(plId, "trk_starboy", "usr_alice")
        assertFalse("Duplicate track addition must be rejected", addedSecondTime)

        val tracksFirstSession = repo1.playlists.first()[0].trackIds
        assertEquals(listOf("trk_starboy"), tracksFirstSession)

        db1.close()

        // Reopen database (simulating app restart)
        val db2 = Room.databaseBuilder(context, AppDatabase::class.java, "test_playlist_persistence.db")
            .allowMainThreadQueries()
            .build()
        val repo2 = MusicRepository(db2.musicDao())

        repo2.setCurrentUserId("usr_alice")
        val playlistsRestored = repo2.playlists.first()
        assertEquals(1, playlistsRestored.size)
        assertEquals("Morning Energy", playlistsRestored[0].name)
        assertEquals(listOf("trk_starboy"), playlistsRestored[0].trackIds)

        db2.close()
        dbFile.delete()
    }

    @Test
    fun migration_fromVersion4To5_addsArtworkColumnAndPreservesData() = runTest {
        val dbFile = context.getDatabasePath("test_migration_v4_to_v5.db")
        if (dbFile.exists()) dbFile.delete()

        // 1. Create a raw SQLite database at Schema Version 4 (no artwork column on playlists)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_v4_to_v5.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `playlists` (
                            `id` TEXT NOT NULL,
                            `userId` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_userId` ON `playlists` (`userId`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                            `playlistId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `userId` TEXT NOT NULL,
                            `position` INTEGER NOT NULL,
                            PRIMARY KEY(`playlistId`, `trackId`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_userId` ON `playlist_tracks` (`userId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `liked_tracks` (
                            `userId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `likedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`userId`, `trackId`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `recently_played` (
                            `userId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `playedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`userId`, `trackId`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `track_metadata` (
                            `id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `artist` TEXT NOT NULL,
                            `artistId` TEXT NOT NULL,
                            `album` TEXT NOT NULL,
                            `albumId` TEXT NOT NULL,
                            `artwork` TEXT NOT NULL,
                            `durationSeconds` INTEGER NOT NULL,
                            `audioUrl` TEXT,
                            `genre` TEXT NOT NULL,
                            `bpm` INTEGER NOT NULL,
                            `energy` REAL NOT NULL,
                            `mood` TEXT NOT NULL,
                            `paletteColorHex` TEXT NOT NULL,
                            `cachedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `user_profile` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `visualQuality` TEXT NOT NULL,
                            `themeMode` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val v4Db = helper.writableDatabase

        // Insert existing v4 playlist
        v4Db.execSQL("INSERT INTO playlists (id, userId, name, description, createdAt) VALUES ('pl_v4', 'user_123', 'V4 Chill', 'Chill songs', 123456)")
        v4Db.close()

        // 2. Open via Room database at Version 6 applying MIGRATION_4_5 and MIGRATION_5_6
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_migration_v4_to_v5.db")
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()

        val dao = roomDb.musicDao()
        val playlist = dao.getPlaylistById("pl_v4", "user_123")
        assertNotNull("Playlist should be present after migration", playlist)
        assertEquals("V4 Chill", playlist?.name)
        assertEquals("Chill songs", playlist?.description)
        assertEquals("", playlist?.artwork) // Default value from migration

        // Can update artwork in v5
        dao.updatePlaylistDetails("pl_v4", "V4 Chill Updated", "New desc", "https://example.com/art.jpg", "user_123")
        val updated = dao.getPlaylistById("pl_v4", "user_123")
        assertEquals("V4 Chill Updated", updated?.name)
        assertEquals("https://example.com/art.jpg", updated?.artwork)

        roomDb.close()
        dbFile.delete()
    }

    @Test
    fun migration_fromVersion5To6_addsBannerArtworkColumnAndPreservesData() = runTest {
        val dbFile = context.getDatabasePath("test_migration_v5_to_v6.db")
        if (dbFile.exists()) dbFile.delete()

        // 1. Create a raw SQLite database at Schema Version 5
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_v5_to_v6.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `user_profile` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `visualQuality` TEXT NOT NULL,
                            `themeMode` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `liked_tracks` (
                            `userId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `likedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`userId`, `trackId`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `playlists` (
                            `id` TEXT NOT NULL,
                            `userId` TEXT NOT NULL DEFAULT 'guest_local',
                            `name` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `artwork` TEXT NOT NULL DEFAULT '',
                            `createdAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_userId` ON `playlists` (`userId`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                            `playlistId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `userId` TEXT NOT NULL DEFAULT '',
                            `position` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`playlistId`, `trackId`)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_userId` ON `playlist_tracks` (`userId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `recently_played` (
                            `userId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `playedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`userId`, `trackId`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `track_metadata` (
                            `id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `artist` TEXT NOT NULL,
                            `artistId` TEXT NOT NULL DEFAULT 'art_1',
                            `album` TEXT NOT NULL DEFAULT 'Flins Records',
                            `albumId` TEXT NOT NULL DEFAULT 'alb_1',
                            `artwork` TEXT NOT NULL,
                            `durationSeconds` INTEGER NOT NULL,
                            `audioUrl` TEXT,
                            `genre` TEXT NOT NULL DEFAULT 'Electronic',
                            `bpm` INTEGER NOT NULL DEFAULT 120,
                            `energy` REAL NOT NULL DEFAULT 0.7,
                            `mood` TEXT NOT NULL DEFAULT 'Chill',
                            `paletteColorHex` TEXT NOT NULL DEFAULT '#00F0FF',
                            `cachedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val v5Db = helper.writableDatabase

        // Insert existing track without bannerArtwork
        v5Db.execSQL("""
            INSERT INTO track_metadata (id, title, artist, artwork, durationSeconds, cachedAt)
            VALUES ('yt_Vug4YLiIsaU', 'Alfaaz', 'Hamza Malik', 'https://example.com/art.jpg', 186, 123456)
        """.trimIndent())
        v5Db.close()

        // 2. Open via Room database at Version 6 applying MIGRATION_5_6
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_migration_v5_to_v6.db")
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .build()

        val dao = roomDb.musicDao()
        val track = dao.getTrackMetadataById("yt_Vug4YLiIsaU")
        assertNotNull("Track should be present after migration", track)
        assertEquals("Alfaaz", track?.title)
        assertEquals("https://example.com/art.jpg", track?.artwork)
        assertEquals("", track?.bannerArtwork) // Default column value from migration

        // Can insert and query with bannerArtwork in v6
        val updatedTrack = track!!.copy(bannerArtwork = "https://example.com/banner.jpg")
        dao.insertTrackMetadata(updatedTrack)
        val queried = dao.getTrackMetadataById("yt_Vug4YLiIsaU")
        assertEquals("https://example.com/banner.jpg", queried?.bannerArtwork)

        roomDb.close()
        dbFile.delete()
    }
}
