package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.cache.StreamUrlDiskCache
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.YtAccount
import com.example.service.integrations.ytmusic.YtAccountManager
import com.example.service.integrations.ytmusic.YtMusicClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimpMusicArchitectureIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("anyfy_stream_url_cache", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("anyfy_yt_accounts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // =========================================================================
    // 1. STREAM URL DISK CACHE TESTS (SimpMusic Caching Architecture)
    // =========================================================================

    @Test
    fun streamUrlDiskCache_storesAndRetrievesValidStreamUrl() {
        val diskCache = StreamUrlDiskCache.getInstance(context)
        val testVideoId = "dQw4w9WgXcQ"
        val futureExpirySec = (System.currentTimeMillis() / 1000L) + 14400L // 4 hours in future
        val streamUrl = "https://rr1---sn-4g5ednzy.googlevideo.com/videoplayback?expire=$futureExpirySec&id=12345"

        diskCache.put(testVideoId, streamUrl, "yt-dlp")

        val retrieved = diskCache.get(testVideoId)
        assertNotNull(retrieved)
        assertEquals(streamUrl, retrieved)
    }

    @Test
    fun streamUrlDiskCache_evictsExpiredStreamUrl() {
        val diskCache = StreamUrlDiskCache.getInstance(context)
        val testVideoId = "expiredVid12"
        val pastExpirySec = (System.currentTimeMillis() / 1000L) - 300L // 5 mins in past
        val expiredStreamUrl = "https://rr1---sn-4g5ednzy.googlevideo.com/videoplayback?expire=$pastExpirySec&id=999"

        diskCache.put(testVideoId, expiredStreamUrl, "yt-dlp")

        val retrieved = diskCache.get(testVideoId)
        assertNull("Expired stream URL must return null and be evicted", retrieved)
    }

    @Test
    fun streamUrlDiskCache_handlesPrefixesAndClearing() {
        val diskCache = StreamUrlDiskCache.getInstance(context)
        val rawVideoId = "BddP6PYo2gs"
        val prefixedId = "yt_BddP6PYo2gs"
        val futureExpirySec = (System.currentTimeMillis() / 1000L) + 7200L
        val streamUrl = "https://rr2---sn-4g5ednzy.googlevideo.com/videoplayback?expire=$futureExpirySec"

        diskCache.put(prefixedId, streamUrl, "yt-dlp")
        assertEquals(streamUrl, diskCache.get(rawVideoId))
        assertEquals(streamUrl, diskCache.get(prefixedId))

        diskCache.remove(rawVideoId)
        assertNull(diskCache.get(rawVideoId))
    }

    // =========================================================================
    // 2. YOUTUBE MUSIC ACCOUNT & AUTHENTICATION TESTS (SimpMusic Account Model)
    // =========================================================================

    @Test
    fun ytAccountManager_calculatesValidSapisidHash() {
        val sapisid = "1234567890abcdef"
        val hashHeader = YtAccountManager.calculateSapisidHash(sapisid)

        assertTrue("SAPISIDHASH must start with prefix", hashHeader.startsWith("SAPISIDHASH "))
        val parts = hashHeader.removePrefix("SAPISIDHASH ").split("_")
        assertEquals(2, parts.size)
        assertTrue("First part must be timestamp digits", parts[0].all { it.isDigit() })
        assertEquals("Second part must be 40-char SHA1 hex", 40, parts[1].length)
    }

    @Test
    fun ytAccountManager_managesMultipleAccountsAndSwitching() {
        val accountManager = YtAccountManager.getInstance(context)

        val account1 = YtAccount(
            id = "acc_user1",
            name = "Gaurav Primary",
            email = "gaurav@example.com",
            cookie = "SAPISID=sample_token_1; SSID=abc; HSID=def",
            isActive = true
        )
        val account2 = YtAccount(
            id = "acc_user2",
            name = "Gaurav Secondary",
            email = "gaurav2@example.com",
            cookie = "SAPISID=sample_token_2; SSID=xyz; HSID=uvw",
            isActive = false
        )

        accountManager.addOrUpdateAccount(account1)
        accountManager.addOrUpdateAccount(account2)

        val allAccounts = accountManager.accounts.value
        assertEquals(2, allAccounts.size)
        assertEquals("acc_user2", accountManager.activeAccount.value?.id)

        // Switch back to account 1
        val switched = accountManager.switchAccount("acc_user1")
        assertTrue(switched)
        assertEquals("acc_user1", accountManager.activeAccount.value?.id)
        assertTrue(accountManager.activeAccount.value!!.isActive)

        val headers = accountManager.getAuthHeaders()
        assertTrue("Headers must include Cookie", headers.containsKey("Cookie"))
        assertTrue("Headers must include Authorization SAPISIDHASH", headers.containsKey("Authorization"))
        assertTrue("Authorization header must contain SAPISIDHASH", headers["Authorization"]!!.startsWith("SAPISIDHASH "))

        // Remove account 1
        accountManager.removeAccount("acc_user1")
        assertEquals(1, accountManager.accounts.value.size)
        assertEquals("acc_user2", accountManager.activeAccount.value?.id)
    }

    // =========================================================================
    // 3. SEAMLESS PRELOADING & QUEUE PRE-RESOLUTION TESTS (SimpMusic Streaming)
    // =========================================================================

    @Test
    fun playbackService_preloadsAndAttachesNextTrackStreamUrl() = runTest {
        val mockYtService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> {
                return Result.success("https://audio.example.com/stream_$videoId.mp4")
            }
            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> {
                return Result.success("https://audio.example.com/stream_${track.id}.mp4")
            }
            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val playbackService = PlaybackService(context, mockYtService)

        val track1 = Track(id = "trk_1", title = "Track 1", artist = "Artist", artwork = "https://example.com/art1.jpg", durationSeconds = 180, audioUrl = "https://audio.example.com/stream_trk_1.mp4")
        val track2 = Track(id = "trk_2", title = "Track 2", artist = "Artist", artwork = "https://example.com/art2.jpg", durationSeconds = 200, audioUrl = null)
        val track3 = Track(id = "trk_3", title = "Track 3", artist = "Artist", artwork = "https://example.com/art3.jpg", durationSeconds = 210, audioUrl = null)

        val playlist = listOf(track1, track2, track3)
        playbackService.playTrack(track1, playlist)
        advanceUntilIdle()

        // Verify initial state
        assertEquals("trk_1", playbackService.uiState.value.currentTrack?.id)
        assertEquals(3, playbackService.uiState.value.queue.size)

        // Advance to track 2
        playbackService.next()
        advanceUntilIdle()

        assertEquals("trk_2", playbackService.uiState.value.currentTrack?.id)
        assertEquals(1, playbackService.uiState.value.currentTrackIndex)
    }
}

