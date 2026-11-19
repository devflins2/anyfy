package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.data.repository.SearchCache
import com.example.model.Album
import com.example.model.Artist
import com.example.model.SearchCategory
import com.example.model.Track
import com.example.model.UnifiedSearchResult
import com.example.service.MediaNotificationManager
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.YtAlbum
import com.example.service.integrations.ytmusic.YtArtist
import com.example.service.integrations.ytmusic.YtHomeShelf
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtPlaylist
import com.example.service.integrations.ytmusic.YtTrack
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression and verification test suite for Smart YouTube Music Sync,
 * In-Flight Request Deduplication, Cache-First Navigation, and Playback Stability.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SmartSyncAndPlaybackStabilityRegressionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: MusicRepository
    private lateinit var playbackService: PlaybackService
    private val testDispatcher = StandardTestDispatcher()

    private val sampleTrack = Track(
        id = "yt_dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        artwork = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        durationSeconds = 213
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getInstance(context)
        repository = MusicRepository(database.musicDao())
        playbackService = PlaybackService(context)
    }

    @After
    fun tearDown() {
        repository.clearCache()
        Dispatchers.resetMain()
    }

    /**
     * Test 1: Smart Cache-First Artist Fetching
     * First call fetches from YouTube Music; subsequent calls return immediately from cache (0ms).
     */
    @Test
    fun test01_smartCacheFirst_artistFetching() = runBlocking {
        val networkCallCount = AtomicInteger(0)
        val mockService = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(com.example.service.integrations.ytmusic.YtSearchResult())
            override suspend fun searchTracks(query: String): Result<List<YtTrack>> = Result.success(emptyList())
            override suspend fun getHomeShelves(): Result<List<YtHomeShelf>> = Result.success(emptyList())
            override suspend fun getArtist(browseId: String): Result<YtArtist> {
                networkCallCount.incrementAndGet()
                return Result.success(
                    YtArtist(
                        id = browseId,
                        name = "Arijit Singh",
                        topTracks = listOf(
                            YtTrack(id = "yt_tum_hi_ho", title = "Tum Hi Ho", artist = "Arijit Singh", durationSeconds = 262)
                        ),
                        albums = listOf(
                            YtAlbum(id = "alb_aashiqui2", title = "Aashiqui 2", artist = "Arijit Singh")
                        )
                    )
                )
            }
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        // 1. Initial fetch (Cache miss -> Network call)
        val artist1 = repository.fetchYtArtist("UC_arijit_123", "Arijit Singh", mockService)
        assertNotNull(artist1)
        assertEquals(1, networkCallCount.get())
        assertTrue(repository.isArtistCached("UC_arijit_123"))

        // 2. Second fetch (Cache hit -> 0 network calls)
        val artist2 = repository.fetchYtArtist("UC_arijit_123", "Arijit Singh", mockService)
        assertNotNull(artist2)
        assertEquals("Cached response must not invoke network", 1, networkCallCount.get())
        assertEquals(artist1?.id, artist2?.id)
    }

    /**
     * Test 2: Smart Cache-First Album Fetching
     * First call fetches from YouTube Music; subsequent calls return immediately from cache (0ms).
     */
    @Test
    fun test02_smartCacheFirst_albumFetching() = runBlocking {
        val networkCallCount = AtomicInteger(0)
        val mockService = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(com.example.service.integrations.ytmusic.YtSearchResult())
            override suspend fun searchTracks(query: String): Result<List<YtTrack>> = Result.success(emptyList())
            override suspend fun getHomeShelves(): Result<List<YtHomeShelf>> = Result.success(emptyList())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String): Result<YtAlbum> {
                networkCallCount.incrementAndGet()
                return Result.success(
                    YtAlbum(
                        id = browseId,
                        title = "Starboy Album",
                        artist = "The Weeknd",
                        tracks = listOf(
                            YtTrack(id = "yt_starboy_trk", title = "Starboy", artist = "The Weeknd", durationSeconds = 230)
                        )
                    )
                )
            }
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        // 1. Initial fetch (Cache miss -> Network call)
        val album1 = repository.fetchYtAlbum("MPREb_starboy_123", "Starboy Album", mockService)
        assertNotNull(album1)
        assertEquals(1, networkCallCount.get())
        assertTrue(repository.isAlbumCached("MPREb_starboy_123"))

        // 2. Second fetch (Cache hit -> 0 network calls)
        val album2 = repository.fetchYtAlbum("MPREb_starboy_123", "Starboy Album", mockService)
        assertNotNull(album2)
        assertEquals("Cached album must not invoke network", 1, networkCallCount.get())
        assertEquals(album1?.id, album2?.id)
    }

    /**
     * Test 3: In-Flight Request Deduplication (Single-Flight)
     * Multiple simultaneous requests for the same artist or album share 1 network request.
     */
    @Test
    fun test03_inFlightRequestDeduplication() = runBlocking {
        val networkCallCount = AtomicInteger(0)
        val mockService = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(com.example.service.integrations.ytmusic.YtSearchResult())
            override suspend fun searchTracks(query: String): Result<List<YtTrack>> = Result.success(emptyList())
            override suspend fun getHomeShelves(): Result<List<YtHomeShelf>> = Result.success(emptyList())
            override suspend fun getArtist(browseId: String): Result<YtArtist> {
                networkCallCount.incrementAndGet()
                kotlinx.coroutines.delay(50L) // Simulate network latency
                return Result.success(
                    YtArtist(
                        id = browseId,
                        name = "Dua Lipa",
                        topTracks = listOf(YtTrack(id = "yt_levitating", title = "Levitating", artist = "Dua Lipa", durationSeconds = 203))
                    )
                )
            }
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        // Launch 5 concurrent calls simultaneously for the same artist
        val deferreds = (1..5).map {
            async(Dispatchers.IO) {
                repository.fetchYtArtist("UC_dua_lipa", "Dua Lipa", mockService)
            }
        }
        val results = deferreds.awaitAll()

        assertEquals(5, results.size)
        // Network call must be executed exactly ONCE due to single-flight deduplication
        assertEquals("Concurrent duplicate requests must be deduplicated to 1 network call", 1, networkCallCount.get())
        results.forEach { assertNotNull(it) }
    }

    /**
     * Test 4: Home Feed Freshness & Smart Caching
     * Home feed is cached with TTL; subsequent visits do not hammer YouTube Music.
     */
    @Test
    fun test04_homeFeedFreshness_andSmartCaching() = runBlocking {
        val shelvesCallCount = AtomicInteger(0)
        val mockService = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(com.example.service.integrations.ytmusic.YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(
                listOf(YtTrack(id = "yt_hit1", title = "Hit Song 1", artist = "Artist 1", durationSeconds = 180))
            )
            override suspend fun getHomeShelves(): Result<List<YtHomeShelf>> {
                shelvesCallCount.incrementAndGet()
                return Result.success(
                    listOf(
                        YtHomeShelf(
                            title = "Trending",
                            items = listOf(YtTrack(id = "yt_trend1", title = "Trending 1", artist = "Artist 1", durationSeconds = 200))
                        )
                    )
                )
            }
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }
        val fakeYt = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean) = Result.success("https://googlevideo.com/test")
            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean) = Result.success("https://googlevideo.com/test")
            override suspend fun searchTracks(query: String) = emptyList<Track>()
        }

        // 1. First load
        repository.loadRealMusicFeed(fakeYt, mockService, force = false)
        assertEquals(1, shelvesCallCount.get())
        assertTrue(repository.isHomeFeedCached())

        // 2. Subsequent screen visits within TTL must not re-fetch
        repository.loadRealMusicFeed(fakeYt, mockService, force = false)
        assertEquals("Second visit within TTL must use cache", 1, shelvesCallCount.get())

        // 3. Forced refresh bypasses cache
        repository.loadRealMusicFeed(fakeYt, mockService, force = true)
        assertEquals("Forced refresh must query YouTube Music", 2, shelvesCallCount.get())
    }

    /**
     * Test 5: Cache Invalidation on clearCache()
     */
    @Test
    fun test05_clearCache_invalidatesCatalogue() = runBlocking {
        val mockService = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(com.example.service.integrations.ytmusic.YtSearchResult())
            override suspend fun searchTracks(query: String): Result<List<YtTrack>> = Result.success(emptyList())
            override suspend fun getHomeShelves(): Result<List<YtHomeShelf>> = Result.success(emptyList())
            override suspend fun getArtist(browseId: String) = Result.success(
                YtArtist(id = browseId, name = "Test Artist", topTracks = listOf(YtTrack(id = "yt_1", title = "T1", artist = "Test", durationSeconds = 180)))
            )
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        repository.fetchYtArtist("UC_test_artist", "Test Artist", mockService)
        assertTrue(repository.isArtistCached("UC_test_artist"))

        repository.clearCache()
        assertFalse("clearCache must invalidate artist cache", repository.isArtistCached("UC_test_artist"))
        assertFalse("clearCache must invalidate home feed", repository.isHomeFeedCached())
    }

    /**
     * Test 6: Media Notification ongoing state and delete intent
     * Ensures ongoing is true while playing, false when paused, and delete intent is wired.
     */
    @Test
    fun test06_mediaNotification_ongoingState_and_deleteIntent() {
        val mediaSession = android.support.v4.media.session.MediaSessionCompat(context, "TestSession")
        val notificationManager = MediaNotificationManager(context, mediaSession)

        // Notification while playing: ongoing = true
        val playingNotif = notificationManager.getNotification(sampleTrack, isPlaying = true, positionMs = 5000L)
        assertTrue("Notification while playing must be ongoing", (playingNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertNotNull("Notification must have delete intent", playingNotif.deleteIntent)

        // Notification while paused: ongoing = false (dismissible)
        val pausedNotif = notificationManager.getNotification(sampleTrack, isPlaying = false, positionMs = 5000L)
        assertFalse("Notification while paused must be dismissible (not ongoing)", (pausedNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertNotNull("Notification while paused must have delete intent to cleanly shutdown service", pausedNotif.deleteIntent)

        mediaSession.release()
    }

    /**
     * Test 7: Stream URL Expiration Guard
     * Expired stream URLs must be rejected and never played.
     */
    @Test
    fun test07_streamUrlExpirationGuard() {
        val nowSec = System.currentTimeMillis() / 1000L
        val expiredUrl = "https://rr1---sn-oxunx-5h0e.googlevideo.com/videoplayback?expire=${nowSec - 3600}&id=123"
        val freshUrl = "https://rr1---sn-oxunx-5h0e.googlevideo.com/videoplayback?expire=${nowSec + 18000}&id=123"

        assertTrue("Expired stream URL must be detected", YouTubeService.isStreamUrlExpired(expiredUrl))
        assertFalse("Fresh stream URL must not be expired", YouTubeService.isStreamUrlExpired(freshUrl))
    }

    /**
     * Test 8: Search Cache TTL & Invalidation
     * Search results are served from cache within 15 minutes.
     */
    @Test
    fun test08_searchCache_freshnessAndRetrieval() {
        val searchCache = SearchCache(maxCapacity = 50)
        val dummyResult = UnifiedSearchResult(
            tracks = listOf(sampleTrack),
            query = "rick astley"
        )

        searchCache.put("rick astley_all", dummyResult)
        val cached = searchCache.get("rick astley_all")

        assertNotNull(cached)
        assertTrue("Cached result must have isFromCache=true", cached?.isFromCache == true)
        assertEquals(1, cached?.tracks?.size)
        assertEquals(sampleTrack.id, cached?.tracks?.first()?.id)
    }

    /**
     * Test 9: Navigation Cache-First Instant Rendering
     * MainViewModel navigates using cached data immediately if available.
     */
    @Test
    fun test09_navigation_cacheFirstInstantSelection() {
        val realtimeService = com.example.service.integrations.InMemoryRealtimeService()
        val viewModel = MainViewModel(repository, playbackService, realtimeService)

        val cachedArtist = Artist(
            id = "UC_cached_artist",
            name = "Arijit Singh",
            artwork = "https://example.com/art.jpg",
            tracks = listOf(sampleTrack)
        )
        // Seed into repository
        repository.registerTracks(listOf(sampleTrack))

        viewModel.navigateToArtist(cachedArtist)
        assertEquals("Selected artist must be available immediately", cachedArtist.id, viewModel.selectedArtist.value?.id)
        assertEquals("Sub-screen stack must contain the artist destination", 1, viewModel.subScreenStack.value.size)
    }
}
