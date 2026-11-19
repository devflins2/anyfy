package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.LikedTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistTrackEntity
import com.example.data.local.entity.TrackMetadataEntity
import com.example.data.repository.MusicRepository
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistFunctionalityRegressionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val sampleOnlineTrack = Track(
        id = "trk_cyber_glow_99",
        title = "Cyber Glow",
        artist = "Neon Synth",
        artistId = "art_neon",
        album = "Future Horizon",
        albumId = "alb_future",
        artwork = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=600",
        durationSeconds = 215,
        audioUrl = "https://aac.saavncdn.com/test_cyber.mp4",
        genre = "Synthwave"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun addingSong_displaysInSelectedPlaylistImmediately() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MusicRepository(db.musicDao())

        // 1. Sign in as Alice
        repository.setCurrentUserId("usr_alice")
        val playlistId = repository.createPlaylist("Alice Roadtrip", "Summer hits")
        assertTrue("Playlist ID must not be blank", playlistId.isNotBlank())

        // 2. Add an online search track with complete metadata
        val addSuccess = repository.addTrackToPlaylist(playlistId, sampleOnlineTrack)
        assertTrue("Adding song should return true on success", addSuccess)

        // 3. Immediately verify the playlist reflects the added song
        val alicePlaylists = repository.playlists.first()
        assertEquals(1, alicePlaylists.size)
        val playlist = alicePlaylists[0]
        assertEquals("Alice Roadtrip", playlist.name)
        assertEquals(listOf(sampleOnlineTrack.id), playlist.trackIds)
        // Verify thumbnail is derived from the added track
        assertEquals(sampleOnlineTrack.artwork, playlist.artwork)

        // 4. Verify allKnownTracks StateFlow contains the new song metadata
        val known = repository.allKnownTracks.value
        val foundTrack = known.find { it.id == sampleOnlineTrack.id }
        assertNotNull("Track must be present in allKnownTracks StateFlow immediately", foundTrack)
        assertEquals("Cyber Glow", foundTrack?.title)
        assertEquals("Neon Synth", foundTrack?.artist)

        // 5. Verify getPlaylistTracks Flow resolves the complete Track immediately
        val playlistTracks = repository.getPlaylistTracks(playlistId, "usr_alice").first()
        assertEquals(1, playlistTracks.size)
        assertEquals("Cyber Glow", playlistTracks[0].title)
        assertEquals("Neon Synth", playlistTracks[0].artist)
        assertEquals("Future Horizon", playlistTracks[0].album)

        db.close()
    }

    @Test
    fun songPersistsAfterDatabaseReopenAndAppRestart() = runTest {
        val dbFile = context.getDatabasePath("test_playlist_restart_persistence.db")
        if (dbFile.exists()) dbFile.delete()

        // 1. First app session: Create playlist and add track
        val firstDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_playlist_restart_persistence.db")
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        val firstRepo = MusicRepository(firstDb.musicDao())
        firstRepo.setCurrentUserId("usr_alice")

        val playlistId = firstRepo.createPlaylist("Night Drives", "Synth beats")
        val addSuccess = firstRepo.addTrackToPlaylist(playlistId, sampleOnlineTrack)
        assertTrue("Initial add should succeed", addSuccess)

        // Ensure database write is committed
        val rawTrackIds = firstDb.musicDao().getPlaylistTrackIds(playlistId, "usr_alice").first()
        assertEquals(listOf(sampleOnlineTrack.id), rawTrackIds)
        val rawMetadata = firstDb.musicDao().getTrackMetadataById(sampleOnlineTrack.id)
        assertNotNull("Track metadata must be saved in SQLite before restart", rawMetadata)
        assertEquals("Cyber Glow", rawMetadata?.title)

        // Close the database to simulate process termination / app restart
        firstDb.close()

        // 2. Second app session: Re-open the database with a fresh repository instance
        val restartedDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_playlist_restart_persistence.db")
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        val restartedRepo = MusicRepository(restartedDb.musicDao())
        restartedRepo.setCurrentUserId("usr_alice")

        // Wait for repository initial cached metadata restoration
        advanceUntilIdle()

        // 3. Verify playlist and tracks survived the restart
        val restoredPlaylists = restartedRepo.playlists.first()
        assertEquals(1, restoredPlaylists.size)
        assertEquals("Night Drives", restoredPlaylists[0].name)
        assertEquals(listOf(sampleOnlineTrack.id), restoredPlaylists[0].trackIds)

        // 4. Verify the song details are preserved and resolved
        val resolvedTracks = restartedRepo.getPlaylistTracks(playlistId, "usr_alice").first()
        assertEquals(1, resolvedTracks.size)
        val restoredTrack = resolvedTracks[0]
        assertEquals(sampleOnlineTrack.id, restoredTrack.id)
        assertEquals("Cyber Glow", restoredTrack.title)
        assertEquals("Neon Synth", restoredTrack.artist)
        assertEquals("Future Horizon", restoredTrack.album)
        assertEquals(sampleOnlineTrack.artwork, restoredTrack.artwork)

        restartedDb.close()
        dbFile.delete()
    }

    @Test
    fun duplicateAdditionsHandledCorrectly() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MusicRepository(db.musicDao())
        repository.setCurrentUserId("usr_alice")

        val playlistId = repository.createPlaylist("Unique Hits")

        // First addition succeeds
        val firstAdd = repository.addTrackToPlaylist(playlistId, sampleOnlineTrack)
        assertTrue("First add must succeed", firstAdd)

        // Second addition of the exact same track must fail
        val duplicateAdd = repository.addTrackToPlaylist(playlistId, sampleOnlineTrack)
        assertFalse("Duplicate add must return false", duplicateAdd)

        // Check database row count in playlist_tracks
        val trackIds = db.musicDao().getPlaylistTrackIds(playlistId, "usr_alice").first()
        assertEquals("Track must only appear once in playlist", 1, trackIds.size)
        assertEquals(sampleOnlineTrack.id, trackIds[0])

        // Verify isTrackInPlaylist returns true
        val isInPlaylist = repository.isTrackInPlaylist(playlistId, sampleOnlineTrack.id).first()
        assertTrue("Track must be confirmed in playlist", isInPlaylist)

        db.close()
    }

    @Test
    fun userIsolation_userBCannotSeeOrModifyUserAPlaylistsOrSongs() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MusicRepository(db.musicDao())

        // 1. User Alice signs in and creates playlist with 2 tracks
        repository.setCurrentUserId("usr_alice")
        val alicePlId = repository.createPlaylist("Alice Top Secret")
        assertTrue(repository.addTrackToPlaylist(alicePlId, sampleOnlineTrack))
        assertTrue(repository.addTrackToPlaylist(alicePlId, "trk_kesariya"))

        val alicePlaylists = repository.playlists.first()
        assertEquals(1, alicePlaylists.size)
        assertEquals(listOf(sampleOnlineTrack.id, "trk_kesariya"), alicePlaylists[0].trackIds)

        // 2. User Bob signs in
        repository.setCurrentUserId("usr_bob")

        // Bob cannot see Alice's playlists
        val bobPlaylists = repository.playlists.first()
        assertTrue("Bob must see zero playlists", bobPlaylists.isEmpty())

        // Bob cannot get Alice's playlist by ID via DAO
        val bobSneakPlaylist = db.musicDao().getPlaylistById(alicePlId, "usr_bob")
        assertNull("Bob must not be able to query Alice's playlist by ID", bobSneakPlaylist)

        // Bob cannot view Alice's playlist tracks
        val bobTracks = repository.getPlaylistTracks(alicePlId, "usr_bob").first()
        assertTrue("Bob must see zero tracks for Alice's playlist", bobTracks.isEmpty())

        // Bob cannot add a track to Alice's playlist
        val bobAddAttempt = repository.addTrackToPlaylist(alicePlId, "trk_blinding_lights", userId = "usr_bob")
        assertFalse("Bob must not be able to add tracks to Alice's playlist", bobAddAttempt)

        // Bob cannot remove a track from Alice's playlist
        repository.removeTrackFromPlaylist(alicePlId, sampleOnlineTrack.id, userId = "usr_bob")

        // Bob cannot rename Alice's playlist
        repository.renamePlaylist(alicePlId, "Hacked by Bob", userId = "usr_bob")

        // Bob cannot delete Alice's playlist
        repository.deletePlaylist(alicePlId, userId = "usr_bob")

        // 3. User Alice signs back in -> verify everything is intact
        repository.setCurrentUserId("usr_alice")
        val alicePlaylistsAfter = repository.playlists.first()
        assertEquals(1, alicePlaylistsAfter.size)
        assertEquals("Alice Top Secret", alicePlaylistsAfter[0].name)
        assertEquals(listOf(sampleOnlineTrack.id, "trk_kesariya"), alicePlaylistsAfter[0].trackIds)

        db.close()
    }

    @Test
    fun existingRecords_surviveMigrationV2ToV3ToV4() = runTest {
        val dbFile = context.getDatabasePath("test_existing_data_migration.db")
        if (dbFile.exists()) dbFile.delete()

        // 1. Setup raw v2 SQLite database
        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_existing_data_migration.db")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `user_profile` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `visualQuality` TEXT NOT NULL, `themeMode` TEXT NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `liked_tracks` (`trackId` TEXT NOT NULL, `likedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `recently_played` (`trackId` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `track_metadata` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `artistId` TEXT NOT NULL, `album` TEXT NOT NULL, `albumId` TEXT NOT NULL, `artwork` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `audioUrl` TEXT, `genre` TEXT NOT NULL, `bpm` INTEGER NOT NULL, `energy` REAL NOT NULL, `mood` TEXT NOT NULL, `paletteColorHex` TEXT NOT NULL, `cachedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val v2Db = openHelper.writableDatabase
        v2Db.execSQL("INSERT INTO `playlists` VALUES ('pl_pre_existing', 'Old Favorites', 'Saved before migration', 1000)")
        v2Db.execSQL("INSERT INTO `playlist_tracks` VALUES ('pl_pre_existing', 'trk_kesariya', 0)")
        v2Db.execSQL("INSERT INTO `playlist_tracks` VALUES ('pl_pre_existing', 'trk_blinding_lights', 1)")
        v2Db.execSQL("INSERT INTO `liked_tracks` VALUES ('trk_kesariya', 2000)")
        v2Db.execSQL("INSERT INTO `recently_played` VALUES ('trk_kesariya', 3000)")
        v2Db.close()

        // 2. Open with Room applying MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, and MIGRATION_5_6
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_existing_data_migration.db")
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()

        val dao = migratedDb.musicDao()

        // Pre-existing records mapped to guest_local
        val migratedPlaylists = dao.getAllPlaylists("guest_local").first()
        assertEquals(1, migratedPlaylists.size)
        assertEquals("Old Favorites", migratedPlaylists[0].name)
        assertEquals("guest_local", migratedPlaylists[0].userId)

        val migratedTracks = dao.getPlaylistTrackIds("pl_pre_existing", "guest_local").first()
        assertEquals(listOf("trk_kesariya", "trk_blinding_lights"), migratedTracks)

        val migratedLikes = dao.getLikedTracks("guest_local").first()
        assertEquals(1, migratedLikes.size)
        assertEquals("trk_kesariya", migratedLikes[0].trackId)

        val migratedRecent = dao.getRecentlyPlayed("guest_local").first()
        assertEquals(1, migratedRecent.size)
        assertEquals("trk_kesariya", migratedRecent[0].trackId)

        migratedDb.close()
        dbFile.delete()
    }
}
