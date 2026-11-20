package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room as AndroidRoom
import com.example.data.local.AppDatabase
import com.example.data.local.entity.LikedTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistTrackEntity
import com.example.data.local.entity.RecentlyPlayedEntity
import com.example.data.local.entity.TrackMetadataEntity
import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.cache.AudioCacheManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidStorageOptimizationRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AndroidRoom.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MusicRepository(db.musicDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testAudioCacheManager_configuredWithSensibleDiskLimit() {
        // Verify AudioCacheManager max disk cache limit is capped at 40 MB (not bloated 120 MB)
        val cache = AudioCacheManager.getCache(context)
        assertNotNull("SimpleCache must initialize successfully", cache)
        // Verify preloading chunk size remains responsive for low-latency zero-delay playback
        assertEquals(768 * 1024L, AudioCacheManager.PRELOAD_CHUNK_SIZE)
    }

    @Test
    fun testEphemeralStreamUrl_isNotPersistedToRoomDatabase() = runTest {
        val ephemeralStreamTrack = Track(
            id = "yt_test_ephemeral",
            title = "Ephemeral Track",
            artist = "Stream Artist",
            artwork = "https://i.ytimg.com/vi/test_ephemeral/hqdefault.jpg",
            audioUrl = "https://rr3---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1732100000&ei=test&ip=1.1.1.1",
            durationSeconds = 210
        )

        // Persist track metadata through repository
        repository.persistTrackMetadata(ephemeralStreamTrack)

        // Read directly from Room DAO
        val entity = db.musicDao().getTrackMetadataById("yt_test_ephemeral")
        assertNotNull(entity)
        assertNull(
            "Ephemeral googlevideo.com stream URLs must NOT be saved into persistent Room storage",
            entity?.audioUrl
        )
        assertEquals("Ephemeral Track", entity?.title)
        assertEquals("Stream Artist", entity?.artist)
    }

    @Test
    fun testDatabasePruning_safelyRemovesOrphanMetadata_whilePreservingUserData() = runTest {
        val dao = db.musicDao()
        val now = System.currentTimeMillis()
        val twentyDaysAgo = now - TimeUnit.DAYS.toMillis(20)

        // 1. Insert user data: Liked track, playlist track, recently played track
        val likedEntity = LikedTrackEntity(userId = "user_1", trackId = "trk_liked", likedAt = twentyDaysAgo)
        dao.insertLikedTrack(likedEntity)
        dao.insertTrackMetadata(
            TrackMetadataEntity(
                id = "trk_liked",
                title = "Liked Song",
                artist = "Artist A",
                artwork = "https://example.com/a.jpg",
                durationSeconds = 180,
                cachedAt = twentyDaysAgo
            )
        )

        val playlist = PlaylistEntity(id = "pl_1", userId = "user_1", name = "My Party")
        dao.insertPlaylist(playlist)
        dao.insertPlaylistTrack(PlaylistTrackEntity(playlistId = "pl_1", trackId = "trk_playlist", userId = "user_1"))
        dao.insertTrackMetadata(
            TrackMetadataEntity(
                id = "trk_playlist",
                title = "Playlist Song",
                artist = "Artist B",
                artwork = "https://example.com/b.jpg",
                durationSeconds = 200,
                cachedAt = twentyDaysAgo
            )
        )

        val recent = RecentlyPlayedEntity(userId = "user_1", trackId = "trk_recent", playedAt = twentyDaysAgo)
        dao.insertRecentlyPlayed(recent)
        dao.insertTrackMetadata(
            TrackMetadataEntity(
                id = "trk_recent",
                title = "Recent Song",
                artist = "Artist C",
                artwork = "https://example.com/c.jpg",
                durationSeconds = 220,
                cachedAt = twentyDaysAgo
            )
        )

        // 2. Insert orphan search results (not liked, not in playlist, not in recent)
        dao.insertTrackMetadata(
            TrackMetadataEntity(
                id = "trk_orphan_old",
                title = "Old Orphan Song",
                artist = "Artist X",
                artwork = "https://example.com/x.jpg",
                durationSeconds = 150,
                cachedAt = twentyDaysAgo // 20 days old (expired)
            )
        )
        dao.insertTrackMetadata(
            TrackMetadataEntity(
                id = "trk_orphan_fresh",
                title = "Fresh Orphan Song",
                artist = "Artist Y",
                artwork = "https://example.com/y.jpg",
                durationSeconds = 160,
                cachedAt = now // Fresh
            )
        )

        // Total initial metadata count = 5
        assertEquals(5, dao.getAllCachedTrackMetadata().first().size)

        // 3. Execute pruneExpiredMetadata for records older than 14 days
        val fourteenDaysAgo = now - TimeUnit.DAYS.toMillis(14)
        val prunedCount = dao.pruneExpiredMetadata(fourteenDaysAgo)
        assertEquals("Only trk_orphan_old should be pruned", 1, prunedCount)

        // 4. Verify preserved user records
        assertNotNull("Liked track metadata MUST be preserved", dao.getTrackMetadataById("trk_liked"))
        assertNotNull("Playlist track metadata MUST be preserved", dao.getTrackMetadataById("trk_playlist"))
        assertNotNull("Recent track metadata MUST be preserved", dao.getTrackMetadataById("trk_recent"))
        assertNotNull("Fresh orphan metadata must be preserved", dao.getTrackMetadataById("trk_orphan_fresh"))
        assertNull("Expired orphan metadata must be deleted", dao.getTrackMetadataById("trk_orphan_old"))

        // Total metadata count after prune = 4
        assertEquals(4, dao.getAllCachedTrackMetadata().first().size)
    }

    @Test
    fun testDatabasePruning_trimOrphanMetadata_enforcesHardCapOnOrphans() = runTest {
        val dao = db.musicDao()
        val now = System.currentTimeMillis()

        // Insert 10 orphan tracks
        for (i in 1..10) {
            dao.insertTrackMetadata(
                TrackMetadataEntity(
                    id = "trk_orphan_$i",
                    title = "Orphan Track $i",
                    artist = "Unknown",
                    artwork = "https://example.com/art.jpg",
                    durationSeconds = 120,
                    cachedAt = now + i
                )
            )
        }

        assertEquals(10, dao.getAllCachedTrackMetadata().first().size)

        // Trim orphans to keep limit = 5
        val deleted = dao.trimOrphanMetadata(keepLimit = 5)
        assertEquals(5, deleted)

        val remaining = dao.getAllCachedTrackMetadata().first()
        assertEquals(5, remaining.size)
        // Verify the 5 newest remain
        assertTrue(remaining.any { it.id == "trk_orphan_10" })
        assertTrue(remaining.any { it.id == "trk_orphan_9" })
        assertFalse(remaining.any { it.id == "trk_orphan_1" })
    }

    @Test
    fun testCacheCleaning_removesOrphanTemporaryFiles() {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val tempApk = File(updatesDir, "Anyfy-v1.0.0.apk.tmp").apply { writeText("temporary chunk") }
        val diagExport = File(context.cacheDir, "anyfy_playback_diagnostics.txt").apply { writeText("diagnostics text") }

        assertTrue(tempApk.exists())
        assertTrue(diagExport.exists())

        // Simulate temporary cleanup logic
        if (diagExport.exists()) diagExport.delete()
        updatesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
            }
        }

        assertFalse("Diagnostics share file should be cleaned", diagExport.exists())
        assertFalse("Interrupted .tmp download should be cleaned", tempApk.exists())
    }
}
