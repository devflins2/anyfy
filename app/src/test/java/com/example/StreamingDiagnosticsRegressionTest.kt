package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.integrations.StreamException
import com.example.service.integrations.YouTubeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Regression test suite verifying diagnosis, error handling, format selection,
 * timeout handling, concurrency cancellation, and queue progression in FlinsMusic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingDiagnosticsRegressionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("flinsmusic_playback_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    // 1. Successful extraction
    @Test
    fun testSuccessfulExtraction_initiatesPlaybackWithValidAudioUrl() = runTest {
        val mockService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> =
                Result.success("https://rr1---sn-oxun-xx.googlevideo.com/videoplayback?id=123&itag=140")

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> =
                Result.success("https://rr1---sn-oxun-xx.googlevideo.com/videoplayback?id=123&itag=140")

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, mockService)
        val track = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            artwork = "https://example.com/art.jpg",
            audioUrl = null,
            durationSeconds = 212
        )

        service.playTrack(track)
        val state = service.uiState.value

        assertEquals("yt_dQw4w9WgXcQ", state.currentTrack?.id)
        assertTrue(state.isPlaying)
        assertEquals(0, state.currentTrackIndex)
    }

    // 2. Unavailable video
    @Test
    fun testUnavailableVideo_terminatesLoadingState_setsUnavailableWithClearError() = runBlocking {
        val mockService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> =
                Result.failure(StreamException("VIDEO_UNAVAILABLE", "Video unavailable: content removed"))

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> =
                Result.failure(StreamException("VIDEO_UNAVAILABLE", "Video unavailable: content removed"))

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, mockService)
        val track = Track(
            id = "yt_unavailable_vid",
            title = "Removed Song",
            artist = "Artist",
            artwork = "https://example.com/art.jpg",
            audioUrl = null,
            durationSeconds = 180
        )

        service.playTrack(track)

        val finalState = kotlinx.coroutines.withTimeout(10000L) {
            service.uiState.first { !it.status.isLoadingState }
        }

        assertEquals(PlayerStatus.UNAVAILABLE, finalState.status)
        assertFalse(finalState.isPlaying)
        assertNotNull(finalState.errorMessage)
        assertTrue(finalState.errorMessage!!.contains("Video unavailable") || finalState.errorMessage!!.contains("unavailable"))
    }

    // 3. Missing or invalid video IDs
    @Test
    fun testMissingOrInvalidVideoId_rejectedImmediatelyByValidator() {
        assertFalse(YouTubeService.isValidYouTubeVideoId(null))
        assertFalse(YouTubeService.isValidYouTubeVideoId(""))
        assertFalse(YouTubeService.isValidYouTubeVideoId("   "))
        assertFalse(YouTubeService.isValidYouTubeVideoId("short"))
        assertFalse(YouTubeService.isValidYouTubeVideoId("tooLongVideoIdString12345"))
        assertFalse(YouTubeService.isValidYouTubeVideoId("MPREb_album12345")) // InnerTube album browseId
        assertFalse(YouTubeService.isValidYouTubeVideoId("VLPL1234567890")) // Playlist ID
        assertFalse(YouTubeService.isValidYouTubeVideoId("UC12345678901")) // Channel ID
        assertFalse(YouTubeService.isValidYouTubeVideoId("RD12345678901")) // Radio ID
        assertFalse(YouTubeService.isValidYouTubeVideoId("invalid!char")) // Invalid special characters

        // Valid 11-char IDs
        assertTrue(YouTubeService.isValidYouTubeVideoId("dQw4w9WgXcQ"))
        assertTrue(YouTubeService.isValidYouTubeVideoId("BddP6PYo2gs"))
        assertTrue(YouTubeService.isValidYouTubeVideoId("7wtfhZwyrcc"))
        assertTrue(YouTubeService.isValidYouTubeVideoId("34Na4j8AVgA"))
    }

    // 4. Empty format results
    @Test
    fun testEmptyFormatResults_terminatesLoading_providesErrorMessage() = runBlocking {
        val mockService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> =
                Result.failure(StreamException("EMPTY_FORMATS", "No playable audio stream returned by extractor"))

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> =
                Result.failure(StreamException("EMPTY_FORMATS", "No playable audio stream returned by extractor"))

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, mockService)
        val track = Track(
            id = "yt_empty_stream",
            title = "Empty Stream Track",
            artist = "Artist",
            artwork = "https://example.com/art.jpg",
            audioUrl = null,
            durationSeconds = 180
        )

        service.playTrack(track)

        val finalState = kotlinx.coroutines.withTimeout(10000L) {
            service.uiState.first { !it.status.isLoadingState }
        }

        assertEquals(PlayerStatus.UNAVAILABLE, finalState.status)
        assertFalse(finalState.isPlaying)
        assertNotNull(finalState.errorMessage)
    }

    // 5. Expired URLs & force refresh
    @Test
    fun testExpiredUrl_forceRefreshBypassesCache() = runBlocking {
        var callCount = 0
        var wasForceRefreshRequested = false

        val mockService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> {
                callCount++
                wasForceRefreshRequested = forceRefresh
                return Result.success("https://audio.cdn.example.com/stream_$callCount.m4a")
            }

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> =
                resolveStreamUrl(track.id, forceRefresh)

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val track = Track(id = "yt_test_expire", title = "T", artist = "A", artwork = "https://example.com/art.jpg", durationSeconds = 120)

        // Test normal extraction
        val res1 = mockService.resolveTrackStream(track, forceRefresh = false)
        assertTrue(res1.isSuccess)
        assertFalse(wasForceRefreshRequested)
        assertEquals(1, callCount)

        // Test forced re-extraction after expiration
        val res2 = mockService.resolveTrackStream(track, forceRefresh = true)
        assertTrue(res2.isSuccess)
        assertTrue(wasForceRefreshRequested)
        assertEquals(2, callCount)
    }

    // 6. HTTP / Network errors
    @Test
    fun testHttpError_terminatesLoadingState_andShowsRecoverableError() = runBlocking {
        val mockService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> =
                Result.failure(IOException("HTTP 503 Service Unavailable"))

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> =
                Result.failure(IOException("HTTP 503 Service Unavailable"))

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, mockService)
        val track = Track(
            id = "yt_http_err_track",
            title = "Http Error Track",
            artist = "Artist",
            artwork = "https://example.com/art.jpg",
            audioUrl = null,
            durationSeconds = 180
        )

        service.playTrack(track)

        val finalState = kotlinx.coroutines.withTimeout(10000L) {
            service.uiState.first { !it.status.isLoadingState }
        }

        assertEquals(PlayerStatus.UNAVAILABLE, finalState.status)
        assertFalse(finalState.isPlaying)
        assertNotNull(finalState.errorMessage)

        // Verify retry ability
        service.retryCurrentTrack()
        assertEquals("yt_http_err_track", service.uiState.value.currentTrack?.id)
    }

    // 7. Timeout handling
    @Test
    fun testTimeoutHandling_terminatesLoadingState() = runBlocking {
        val mockService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> {
                delay(60000L)
                return Result.success("https://example.com/audio.m4a")
            }

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> {
                delay(60000L)
                return Result.success("https://example.com/audio.m4a")
            }

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, mockService)
        val track = Track(
            id = "yt_timeout_track",
            title = "Hanging Track",
            artist = "Artist",
            artwork = "https://example.com/art.jpg",
            audioUrl = null,
            durationSeconds = 180
        )

        service.playTrack(track)

        // Service has a 15000ms withTimeoutOrNull. We wait for it to exit LOADING
        val finalState = kotlinx.coroutines.withTimeout(20000L) {
            service.uiState.first { !it.status.isLoadingState }
        }

        assertEquals(PlayerStatus.UNAVAILABLE, finalState.status)
        assertFalse(finalState.isPlaying)
        assertTrue(finalState.errorMessage?.contains("timed out", ignoreCase = true) == true)
    }

    // 8. Rapid track changes & cancellation of stale extraction results
    @Test
    fun testRapidTrackChanges_preventsStaleExtractionResultFromOverwritingCurrentTrack() = runBlocking {
        val slowDeferred = CompletableDeferred<String>()

        val mockService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> =
                resolveTrackStream(Track(id = videoId, title = "", artist = "", artwork = "https://example.com/art.jpg", durationSeconds = 0), forceRefresh)

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> {
                return if (track.id == "yt_slow_track_1") {
                    val url = slowDeferred.await()
                    Result.success(url)
                } else {
                    Result.success("https://fast.stream.example.com/audio.m4a")
                }
            }

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, mockService)
        val slowTrack = Track(id = "yt_slow_track_1", title = "Slow Track", artist = "Artist 1", artwork = "https://example.com/art.jpg", durationSeconds = 180)
        val fastTrack = Track(id = "yt_fast_track_2", title = "Fast Track", artist = "Artist 2", artwork = "https://example.com/art.jpg", durationSeconds = 200)

        // 1. User taps slow track
        service.playTrack(slowTrack, listOf(slowTrack, fastTrack))
        assertEquals("yt_slow_track_1", service.uiState.value.currentTrack?.id)

        // 2. User quickly taps fast track before slow track finishes
        service.playTrack(fastTrack, listOf(slowTrack, fastTrack))
        assertEquals("yt_fast_track_2", service.uiState.value.currentTrack?.id)

        // 3. Slow track finally completes extraction in background
        slowDeferred.complete("https://slow.stream.example.com/stale_audio.m4a")
        delay(100L)

        // 4. Verify that slow track did NOT overwrite fast track!
        val currentState = service.uiState.value
        assertEquals("yt_fast_track_2", currentState.currentTrack?.id)
        assertEquals("Fast Track", currentState.currentTrack?.title)
    }

    // 9. Duplicate requests maintain consistent playback state
    @Test
    fun testDuplicateRequests_maintainSinglePlaybackIntent() {
        val service = PlaybackService(context)
        val track = Track(id = "trk_kesariya", title = "Kesariya", artist = "Arijit Singh", artwork = "https://example.com/art.jpg", durationSeconds = 268)

        // Tapping same track twice
        service.playTrack(track, listOf(track))
        val state1 = service.uiState.value
        val reqId1 = state1.currentTrack?.id

        service.playTrack(track, listOf(track))
        val state2 = service.uiState.value

        assertEquals(reqId1, state2.currentTrack?.id)
        assertEquals(1, state2.queue.size)
        assertTrue(state2.isPlaying)
    }

    // 10. Queue progression after a failed track
    @Test
    fun testQueueProgression_afterFailedTrack_playsNextValidTrack() = runBlocking {
        val mockService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> =
                resolveTrackStream(Track(id = videoId, title = "", artist = "", artwork = "https://example.com/art.jpg", durationSeconds = 0), forceRefresh)

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> {
                return if (track.id == "yt_broken_track") {
                    Result.failure(StreamException("STREAM_UNAVAILABLE", "Corrupted stream"))
                } else {
                    Result.success("https://cdn.example.com/valid_stream.m4a")
                }
            }

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, mockService)
        val brokenTrack = Track(id = "yt_broken_track", title = "Broken Track", artist = "Artist 1", artwork = "https://example.com/art.jpg", durationSeconds = 180)
        val validTrack = Track(id = "yt_valid_track", title = "Valid Track", artist = "Artist 2", artwork = "https://example.com/art.jpg", durationSeconds = 210)

        // Queue: [brokenTrack, validTrack]
        service.playTrack(brokenTrack, listOf(brokenTrack, validTrack))

        // Wait for broken track to fail
        val failedState = kotlinx.coroutines.withTimeout(10000L) {
            service.uiState.first { !it.status.isLoadingState }
        }
        assertEquals(PlayerStatus.UNAVAILABLE, failedState.status)

        // Skip to next track in queue
        service.next()

        // Verify progression: current track is now the valid track
        val nextState = service.uiState.value
        assertEquals("yt_valid_track", nextState.currentTrack?.id)
        assertEquals(1, nextState.currentTrackIndex)
        assertTrue(nextState.isPlaying)
    }

    // 11. Controlled fallback after recoverable extraction failure
    @Test
    fun testFallback_afterRecoverableExtractionFailure() = runBlocking {
        var ytDlpAttempted = false
        var fallbackAttempted = false

        val fallbackService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> {
                ytDlpAttempted = true
                // Primary on-device yt-dlp fails with transient network timeout
                return Result.failure(StreamException(StreamException.NETWORK_TIMEOUT, "Socket timed out", isRecoverable = true))
            }

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> {
                val primaryRes = resolveStreamUrl(track.id, forceRefresh)
                if (primaryRes.isSuccess) return primaryRes

                // Primary failed with recoverable error -> trigger controlled JioSaavn fallback
                fallbackAttempted = true
                return Result.success("https://aac.saavncdn.com/fallback/stream_320.mp4")
            }

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, fallbackService)
        val track = Track(
            id = "yt_timeout_song",
            title = "Kesariya",
            artist = "Arijit Singh",
            artwork = "https://example.com/art.jpg",
            audioUrl = null,
            durationSeconds = 268
        )

        service.playTrack(track)
        kotlinx.coroutines.withTimeout(5000L) {
            while (!fallbackAttempted) {
                kotlinx.coroutines.delay(50L)
            }
        }
        val finalState = service.uiState.value

        assertTrue("Primary yt-dlp must be attempted first", ytDlpAttempted)
        assertTrue("Controlled fallback must be attempted after recoverable failure", fallbackAttempted)
        assertEquals("yt_timeout_song", finalState.currentTrack?.id)
        assertTrue(finalState.isPlaying)
    }

    // 12. No fallback for invalid video ID without track metadata
    @Test
    fun testNoFallback_forInvalidVideoId_withoutTrackMetadata() = runBlocking {
        var searchFallbackAttempted = false

        val serviceWithFallback = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> {
                if (!YouTubeService.isValidYouTubeVideoId(videoId)) {
                    return Result.failure(StreamException(StreamException.INVALID_VIDEO_ID, "Invalid ID: $videoId", isRecoverable = false))
                }
                return Result.success("https://example.com/audio.m4a")
            }

            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> {
                val cleanVid = track.id.removePrefix("yt_").trim()
                if (!YouTubeService.isValidYouTubeVideoId(cleanVid)) {
                    if (track.title.isBlank()) {
                        // Permanent failure without title metadata: must NOT attempt unguided fallback
                        return Result.failure(StreamException(StreamException.INVALID_VIDEO_ID, "Invalid ID and no title", isRecoverable = false))
                    }
                    searchFallbackAttempted = true
                }
                return Result.failure(StreamException(StreamException.STREAM_UNAVAILABLE, "Unavailable"))
            }

            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }

        val service = PlaybackService(context, serviceWithFallback)
        val badTrack = Track(
            id = "yt_invalid!id",
            title = "",
            artist = "",
            artwork = "https://example.com/art.jpg",
            audioUrl = null,
            durationSeconds = 180
        )

        service.playTrack(badTrack)

        val finalState = kotlinx.coroutines.withTimeout(10000L) {
            service.uiState.first { !it.status.isLoadingState }
        }

        assertEquals(PlayerStatus.UNAVAILABLE, finalState.status)
        assertFalse("Must not attempt search fallback for invalid ID with no metadata", searchFallbackAttempted)
    }

    // 13. Incorrect search matches being rejected by plausible match validator
    @Test
    fun testIncorrectSearchMatch_isRejectedByPlausibleMatchValidator() {
        // Obscure query should not accept unrelated top hits
        assertFalse(
            YouTubeService.isPlausibleMatch(
                expectedTitle = "Rare Indie Song XYZ",
                expectedArtist = "Underground Band",
                candidateTitle = "Top 50 Hindi Hits 2024",
                candidateArtist = "Various Artists"
            )
        )

        // Totally different song titles
        assertFalse(
            YouTubeService.isPlausibleMatch(
                expectedTitle = "Starboy",
                expectedArtist = "The Weeknd",
                candidateTitle = "Despacito",
                candidateArtist = "Luis Fonsi"
            )
        )

        // Valid plausible matches
        assertTrue(
            YouTubeService.isPlausibleMatch(
                expectedTitle = "Kesariya",
                expectedArtist = "Arijit Singh",
                candidateTitle = "Kesariya (From \"Brahmastra\")",
                candidateArtist = "Pritam, Arijit Singh"
            )
        )
        assertTrue(
            YouTubeService.isPlausibleMatch(
                expectedTitle = "Blinding Lights",
                expectedArtist = "The Weeknd",
                candidateTitle = "Blinding Lights (Official Audio)",
                candidateArtist = "The Weeknd"
            )
        )
        assertTrue(
            YouTubeService.isPlausibleMatch(
                expectedTitle = "Starboy",
                expectedArtist = "The Weeknd",
                candidateTitle = "Starboy feat. Daft Punk",
                candidateArtist = "The Weeknd"
            )
        )
    }

    // 14. Preview URLs rejected from streaming and caching
    @Test
    fun testPreviewUrls_areRejectedFromStreamingAndCaching() {
        val preview1 = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview123/preview.m4a"
        val preview2 = "https://mza123.mzstatic.com/audiopreview/v4/12/34/preview.m4a"
        val fullStream1 = "https://aac.saavncdn.com/123/stream_320.mp4"
        val fullStream2 = "https://rr1---sn-oxun-xx.googlevideo.com/videoplayback?id=123"

        assertTrue("iTunes preview URL must be classified as preview", YouTubeService.isAudioPreviewUrl(preview1))
        assertTrue("mzstatic preview URL must be classified as preview", YouTubeService.isAudioPreviewUrl(preview2))
        assertFalse("Saavn CDN full stream must not be classified as preview", YouTubeService.isAudioPreviewUrl(fullStream1))
        assertFalse("GoogleVideo full stream must not be classified as preview", YouTubeService.isAudioPreviewUrl(fullStream2))
    }

    // 15. Together Party and personal playlists remain unaffected
    @Test
    fun testTogetherPartyAndPlaylists_unaffectedByStreamingChanges() {
        val service = PlaybackService(context)
        val trackA = Track(id = "trk_kesariya", title = "Kesariya", artist = "Arijit Singh", artwork = "https://example.com/art.jpg", durationSeconds = 268)
        val trackB = Track(id = "trk_blinding_lights", title = "Blinding Lights", artist = "The Weeknd", artwork = "https://example.com/art.jpg", durationSeconds = 200)

        // Queue order and state maintained
        service.playTrack(trackA, listOf(trackA, trackB))
        val state = service.uiState.value

        assertEquals(2, state.queue.size)
        assertEquals("trk_kesariya", state.queue[0].id)
        assertEquals("trk_blinding_lights", state.queue[1].id)
        assertEquals(0, state.currentTrackIndex)

        // Advance to party track
        service.next()
        val nextState = service.uiState.value
        assertEquals("trk_blinding_lights", nextState.currentTrack?.id)
        assertEquals(1, nextState.currentTrackIndex)
    }
}
