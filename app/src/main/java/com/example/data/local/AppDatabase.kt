package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.MusicDao
import com.example.data.local.entity.LikedTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistTrackEntity
import com.example.data.local.entity.RecentlyPlayedEntity
import com.example.data.local.entity.TrackMetadataEntity
import com.example.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        LikedTrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        RecentlyPlayedEntity::class,
        TrackMetadataEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Migrate liked_tracks: recreate with composite primary key (userId, trackId) and copy data
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `liked_tracks_new` (
                        `userId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `likedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `trackId`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `liked_tracks_new` (`userId`, `trackId`, `likedAt`)
                    SELECT 'guest_local', `trackId`, `likedAt` FROM `liked_tracks`
                """.trimIndent())
                db.execSQL("DROP TABLE `liked_tracks`")
                db.execSQL("ALTER TABLE `liked_tracks_new` RENAME TO `liked_tracks`")

                // 2. Migrate recently_played: recreate with composite primary key (userId, trackId) and copy data
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recently_played_new` (
                        `userId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `playedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `trackId`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `recently_played_new` (`userId`, `trackId`, `playedAt`)
                    SELECT 'guest_local', `trackId`, `playedAt` FROM `recently_played`
                """.trimIndent())
                db.execSQL("DROP TABLE `recently_played`")
                db.execSQL("ALTER TABLE `recently_played_new` RENAME TO `recently_played`")

                // 3. Migrate playlists: add userId column and create index matching Room entity definition
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `userId` TEXT NOT NULL DEFAULT 'guest_local'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_userId` ON `playlists` (`userId`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Migrate playlist_tracks: recreate with userId column and indices
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `playlist_tracks_new` (
                        `playlistId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL DEFAULT '',
                        `position` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`playlistId`, `trackId`)
                    )
                """.trimIndent())

                // Backfill userId from parent playlists table using playlistId
                db.execSQL("""
                    INSERT INTO `playlist_tracks_new` (`playlistId`, `trackId`, `userId`, `position`)
                    SELECT pt.`playlistId`, pt.`trackId`, COALESCE(p.`userId`, 'guest_local'), pt.`position`
                    FROM `playlist_tracks` pt
                    LEFT JOIN `playlists` p ON pt.`playlistId` = p.`id`
                """.trimIndent())

                db.execSQL("DROP TABLE `playlist_tracks`")
                db.execSQL("ALTER TABLE `playlist_tracks_new` RENAME TO `playlist_tracks`")

                // Create indices matching Room entity definition
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_userId` ON `playlist_tracks` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add optional custom artwork column to playlists table
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `artwork` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add bannerArtwork column to track_metadata table
                db.execSQL("ALTER TABLE `track_metadata` ADD COLUMN `bannerArtwork` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flinsmusic_database"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
