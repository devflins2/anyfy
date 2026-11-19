package com.example.service.integrations

import androidx.media3.common.PlaybackException
import com.example.service.diagnostics.PlaybackDiagnosticLogger
import com.example.service.integrations.ytmusic.YtMusicClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeStreamingRepairTest {

    @Test
    fun testUserAgentMatchingForAndroidClient() {
        val androidUrl = "https://rr2---sn-4g5ednss.googlevideo.com/videoplayback?expire=1731600000&ei=xyz&ip=1.2.3.4&id=o-ABC&itag=251&source=youtube&requiressl=yes&c=ANDROID&cpn=abcdef"
        val userAgent = YouTubeStreamHeaders.getUserAgentForUrl(androidUrl)
        assertEquals(YouTubeStreamHeaders.USER_AGENT_ANDROID, userAgent)
        assertTrue(userAgent.contains("com.google.android.youtube"))
    }

    @Test
    fun testUserAgentMatchingForAndroidMusicClient() {
        val androidMusicUrl = "https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?expire=1731600000&itag=140&c=ANDROID_MUSIC&cpn=12345"
        val userAgent = YouTubeStreamHeaders.getUserAgentForUrl(androidMusicUrl)
        assertEquals(YouTubeStreamHeaders.USER_AGENT_ANDROID_MUSIC, userAgent)
        assertTrue(userAgent.contains("com.google.android.apps.youtube.music"))
    }

    @Test
    fun testUserAgentMatchingForIosClient() {
        val iosUrl = "https://rr3---sn-4g5ednss.googlevideo.com/videoplayback?expire=1731600000&itag=251&c=IOS&cpn=ios123"
        val userAgent = YouTubeStreamHeaders.getUserAgentForUrl(iosUrl)
        assertEquals(YouTubeStreamHeaders.USER_AGENT_IOS, userAgent)
        assertTrue(userAgent.contains("com.google.ios.youtube"))
    }

    @Test
    fun testUserAgentMatchingForWebClient() {
        val webUrl = "https://rr4---sn-4g5ednss.googlevideo.com/videoplayback?expire=1731600000&itag=251&c=WEB&cpn=web123"
        val userAgent = YouTubeStreamHeaders.getUserAgentForUrl(webUrl)
        assertEquals(YouTubeStreamHeaders.USER_AGENT_WEB, userAgent)
        assertTrue(userAgent.contains("Chrome"))
    }

    @Test
    fun testUserAgentMatchingForTvClient() {
        val tvUrl = "https://rr5---sn-4g5ednss.googlevideo.com/videoplayback?expire=1731600000&itag=140&c=TVHTML5&cpn=tv123"
        val userAgent = YouTubeStreamHeaders.getUserAgentForUrl(tvUrl)
        assertEquals(YouTubeStreamHeaders.USER_AGENT_TV, userAgent)
        assertTrue(userAgent.contains("PlayStation"))
    }

    @Test
    fun testUserAgentDefaultFallback() {
        val genericUrl = "https://example.com/audio.mp3"
        val userAgent = YouTubeStreamHeaders.getUserAgentForUrl(genericUrl)
        assertEquals(YouTubeStreamHeaders.USER_AGENT_WEB, userAgent)

        val nullUserAgent = YouTubeStreamHeaders.getUserAgentForUrl(null)
        assertEquals(YouTubeStreamHeaders.USER_AGENT_WEB, nullUserAgent)
    }

    @Test
    fun testRequestHeadersForProgressiveStreaming() {
        val androidUrl = "https://rr2---sn-4g5ednss.googlevideo.com/videoplayback?c=ANDROID&itag=251"
        val headers = YouTubeStreamHeaders.buildRequestHeadersForUrl(androidUrl)

        assertEquals(YouTubeStreamHeaders.USER_AGENT_ANDROID, headers["User-Agent"])
        assertEquals("*/*", headers["Accept"])
        assertEquals("identity", headers["Accept-Encoding"])
        // Range header must be dynamically managed per chunk by Media3 HttpDataSource
        assertNull("Range header must not be statically hardcoded", headers["Range"])
    }

    @Test
    fun testRequestHeadersForJioSaavnCdn() {
        val saavnUrl = "https://aac.saavncdn.com/123/sample_320.mp4"
        val headers = YouTubeStreamHeaders.buildRequestHeadersForUrl(saavnUrl)

        assertEquals("https://www.jiosaavn.com/", headers["Referer"])
        assertEquals("https://www.jiosaavn.com", headers["Origin"])
        assertEquals("identity", headers["Accept-Encoding"])
    }

    @Test
    fun testRequestHeadersForWebGoogleVideo() {
        val webUrl = "https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?c=WEB&itag=140"
        val headers = YouTubeStreamHeaders.buildRequestHeadersForUrl(webUrl)

        assertEquals("https://music.youtube.com", headers["Origin"])
        assertEquals("https://music.youtube.com/", headers["Referer"])
    }

    @Test
    fun testIsStreamUrlExpiredDetection() {
        val currentSec = System.currentTimeMillis() / 1000L

        // URL expired 10 minutes ago
        val expiredUrl = "https://rr1.googlevideo.com/videoplayback?expire=${currentSec - 600}&itag=251"
        assertTrue(YouTubeService.isStreamUrlExpired(expiredUrl))

        // URL expiring in 60 seconds (within 180s buffer)
        val nearExpiryUrl = "https://rr1.googlevideo.com/videoplayback?expire=${currentSec + 60}&itag=251"
        assertTrue(YouTubeService.isStreamUrlExpired(nearExpiryUrl))

        // URL valid for 2 hours
        val validUrl = "https://rr1.googlevideo.com/videoplayback?expire=${currentSec + 7200}&itag=251"
        assertFalse(YouTubeService.isStreamUrlExpired(validUrl))

        // Blank or null
        assertTrue(YouTubeService.isStreamUrlExpired(null))
        assertTrue(YouTubeService.isStreamUrlExpired(""))
    }

    @Test
    fun testPlausibleMatchVerification() {
        assertTrue(YouTubeService.isPlausibleMatch("Kesariya", "Arijit Singh", "Kesariya (From \"Brahmastra\")", "Arijit Singh, Pritam"))
        assertTrue(YouTubeService.isPlausibleMatch("Blinding Lights", "The Weeknd", "Blinding Lights", "The Weeknd"))
        assertFalse(YouTubeService.isPlausibleMatch("Kesariya", "Arijit Singh", "Shape of You", "Ed Sheeran"))
    }

    @Test
    fun testPlaybackDiagnosticLoggerAll11PointsRecorded() {
        val reqId = 98765L
        val trackId = "trk_kesariya"
        val trackTitle = "Kesariya"
        val videoId = "BddP6PYo2gs"

        // 1. Selected song title and video ID
        PlaybackDiagnosticLogger.startSession(reqId, trackId, trackTitle, videoId)

        // 2. Active YouTube Music account/session status (never log tokens)
        PlaybackDiagnosticLogger.logAccountStatus(reqId, hasAccount = true, accountName = "VerifiedUser", hasCookies = true)

        // 3. On-device extraction start
        PlaybackDiagnosticLogger.logExtractionStart(reqId, "yt-dlp")

        // 4, 5. Extraction completion, exception sanitization, playable URL check
        val playableUrl = "https://rr2---sn-4g5ednss.googlevideo.com/videoplayback?expire=1731600000&itag=251&c=ANDROID"
        PlaybackDiagnosticLogger.logExtractionComplete(reqId, success = true, url = playableUrl, error = null)

        // 6. Selected audio format, codec and MIME type
        PlaybackDiagnosticLogger.logFormatDetails(
            requestId = reqId,
            itag = 251,
            mimeType = "audio/webm; codecs=\"opus\"",
            codecs = "opus",
            bitrate = 160000,
            sampleRate = 48000
        )

        // 7, 8. HTTP response status, headers, and server byte-range support
        val httpHeaders = mapOf(
            "Content-Type" to listOf("audio/webm"),
            "Content-Length" to listOf("4194304"),
            "Accept-Ranges" to listOf("bytes"),
            "Content-Range" to listOf("bytes 0-1048575/4194304")
        )
        PlaybackDiagnosticLogger.logHttpResponse(reqId, statusCode = 206, headers = httpHeaders)

        // 9. First audio bytes received
        PlaybackDiagnosticLogger.logFirstBytes(reqId, bytesLoaded = 65536L)

        // 10. Media3/ExoPlayer state transitions
        PlaybackDiagnosticLogger.logStateTransition(reqId, oldState = "BUFFERING", newState = "READY")

        // 11. URL expiry & auth check
        PlaybackDiagnosticLogger.logUrlExpiryCheck(reqId, url = playableUrl, isExpired = false)

        // Retrieve session and verify properties
        val session = PlaybackDiagnosticLogger.getSession(reqId)
        assertNotNull(session)
        assertEquals(trackTitle, session?.trackTitle)
        assertEquals(videoId, session?.videoId)
        assertTrue(session?.hasActiveAccount == true)
        assertTrue(session?.extractionSuccess == true)
        assertTrue(session?.hasPlayableUrl == true)
        assertEquals(251, session?.formatItag)
        assertEquals("opus", session?.formatCodecs)
        assertEquals(206, session?.httpStatus)
        assertTrue(session?.serverSupportsByteRange == true)
        assertEquals(65536L, session?.firstAudioBytesLoaded)
        assertEquals("READY", session?.lastPlayerState)
        assertFalse(session?.isUrlExpired == true)
        assertFalse(session?.isAuthRejected == true)

        // Generate full report and verify all 11 points are articulated
        val report = PlaybackDiagnosticLogger.printFullDiagnosticReport(reqId)
        assertTrue(report.contains("1. Track: 'Kesariya' (id=trk_kesariya, vid=BddP6PYo2gs)"))
        assertTrue(report.contains("2. Account Status: Authenticated"))
        assertTrue(report.contains("3. On-Device Extraction: yt-dlp"))
        assertTrue(report.contains("4. Extraction Error: None"))
        assertTrue(report.contains("5. Playable URL Returned: true"))
        assertTrue(report.contains("6. Selected Format: itag=251, mime=audio/webm; codecs=\"opus\", codecs=opus, bitrate=160000"))
        assertTrue(report.contains("7. HTTP Status: 206"))
        assertTrue(report.contains("8. Byte Range Support: true"))
        assertTrue(report.contains("9. First Bytes: 65536 bytes"))
        assertTrue(report.contains("10. Player State: READY"))
        assertTrue(report.contains("11. Expiry/Auth: isExpired=false, authRejected=false"))

        // Strictly verify that no sensitive auth tokens or private cookies are in the report
        assertFalse(report.contains("SAPISID"))
        assertFalse(report.contains("HSID"))
        assertFalse(report.contains("SSID"))
        assertFalse(report.contains("Bearer"))
        assertFalse(report.contains("password"))
    }

    @Test
    fun testPlaybackDiagnosticLoggerFailureScenario() {
        val reqId = 54321L
        PlaybackDiagnosticLogger.startSession(reqId, "trk_fail", "Failing Song", "vidFail001")
        PlaybackDiagnosticLogger.logAccountStatus(reqId, hasAccount = false, accountName = null, hasCookies = false)
        PlaybackDiagnosticLogger.logExtractionStart(reqId, "yt-dlp")
        PlaybackDiagnosticLogger.logExtractionComplete(reqId, success = false, url = null, error = "HTTP 403 Forbidden")
        PlaybackDiagnosticLogger.logHttpResponse(reqId, statusCode = 403, headers = null)
        PlaybackDiagnosticLogger.logStreamReadFailure(reqId, "SocketTimeoutException: Connection closed")

        val playbackException = PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        PlaybackDiagnosticLogger.logPlaybackException(reqId, playbackException)

        val report = PlaybackDiagnosticLogger.printFullDiagnosticReport(reqId)
        val session = PlaybackDiagnosticLogger.getSession(reqId)

        assertNotNull(session)
        assertFalse(session?.hasPlayableUrl == true)
        assertTrue(session?.isAuthRejected == true)
        assertNotNull(session?.exactPlaybackException)
        assertTrue(report.contains("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertTrue(report.contains("11. Expiry/Auth: isExpired=false, authRejected=true"))
    }

    @Test
    fun testYtMusicClientExtractAudioStreamUrlFromInnerTube() {
        val client = YtMusicClient()

        // Create realistic InnerTube /player response with multiple adaptive formats
        val playerJson = JSONObject().apply {
            put("streamingData", JSONObject().apply {
                put("formats", JSONArray().apply {
                    put(JSONObject().apply {
                        put("itag", 18)
                        put("url", "https://rr1.googlevideo.com/videoplayback?itag=18")
                        put("mimeType", "video/mp4")
                    })
                })
                put("adaptiveFormats", JSONArray().apply {
                    put(JSONObject().apply {
                        put("itag", 137)
                        put("url", "https://rr1.googlevideo.com/videoplayback?itag=137")
                        put("mimeType", "video/mp4; codecs=\"avc1.640028\"")
                    })
                    put(JSONObject().apply {
                        put("itag", 140)
                        put("url", "https://rr1.googlevideo.com/videoplayback?itag=140")
                        put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                        put("bitrate", 128000)
                    })
                    put(JSONObject().apply {
                        put("itag", 251)
                        put("url", "https://rr1.googlevideo.com/videoplayback?itag=251")
                        put("mimeType", "audio/webm; codecs=\"opus\"")
                        put("bitrate", 160000)
                    })
                })
            })
        }

        val extractedUrl = client.extractAudioStreamUrl(playerJson)
        assertNotNull(extractedUrl)
        // High quality itag 251 Opus must be selected first
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=251", extractedUrl)
    }

    @Test
    fun testYtMusicClientFallbackToItag140When251Missing() {
        val client = YtMusicClient()

        val playerJson = JSONObject().apply {
            put("streamingData", JSONObject().apply {
                put("adaptiveFormats", JSONArray().apply {
                    put(JSONObject().apply {
                        put("itag", 140)
                        put("url", "https://rr1.googlevideo.com/videoplayback?itag=140")
                        put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                        put("bitrate", 128000)
                    })
                })
            })
        }

        val extractedUrl = client.extractAudioStreamUrl(playerJson)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=140", extractedUrl)
    }
}


