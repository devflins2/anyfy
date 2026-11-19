/*
 * Copyright (C) 2024-2026 maxrave-dev (SimpMusic)
 * Ported to Anyfy under GNU General Public License v3.0 (GPL-3.0)
 */

package com.example.service.simpmusic

import com.example.model.Track
import com.example.service.integrations.LiveYouTubeService
import com.example.service.integrations.YouTubeService
import com.example.service.simpmusic.cipher.QuickJsEngine
import com.example.service.simpmusic.cipher.RemotePlayerConfigParser
import com.example.service.simpmusic.cipher.ZemerCipherSolver
import com.example.service.simpmusic.extractor.hasRequiredItags
import com.example.service.simpmusic.repository.SimpMusicStreamRepository
import com.example.service.simpmusic.repository.StreamFormat
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
class SimpMusicStreamingEngineTest {

    @Test
    fun testItagConstantsAndAudioQualityPriority() {
        assertEquals(250, ITAG.AUDIO_OPUS_LOW)
        assertEquals(251, ITAG.AUDIO_OPUS_MEDIUM)
        assertEquals(774, ITAG.AUDIO_OPUS_HIGH)
        assertEquals(140, ITAG.AUDIO_AAC_MEDIUM)
        assertEquals(141, ITAG.AUDIO_AAC_HIGH)

        assertTrue(ITAG.AUDIO.contains(251))
        assertTrue(ITAG.AUDIO.contains(140))
        assertTrue(ITAG.AUDIO.contains(141))
        assertTrue(ITAG.AUDIO.contains(250))
        assertTrue(ITAG.AUDIO.contains(774))

        assertTrue(ITAG.VIDEO.contains(ITAG.VIDEO_360P))
        assertTrue(ITAG.VIDEO.contains(ITAG.VIDEO_720P))
        assertTrue(ITAG.VIDEO.contains(ITAG.VIDEO_1080P))

        // Verify priority order: Opus Medium (251) first
        assertEquals(ITAG.AUDIO_OPUS_MEDIUM, ITAG.PREFERRED_AUDIO_ITAGS.first())
    }

    @Test
    fun testHasRequiredItagsValidation() {
        val validStreams = listOf(
            251 to "https://googlevideo.com/videoplayback?itag=251",
            134 to "https://googlevideo.com/videoplayback?itag=134"
        )
        assertTrue(validStreams.hasRequiredItags())

        val audioOnlyStreams = listOf(
            251 to "https://googlevideo.com/videoplayback?itag=251",
            140 to "https://googlevideo.com/videoplayback?itag=140"
        )
        assertFalse(audioOnlyStreams.hasRequiredItags())

        val videoOnlyStreams = listOf(
            134 to "https://googlevideo.com/videoplayback?itag=134",
            136 to "https://googlevideo.com/videoplayback?itag=136"
        )
        assertFalse(videoOnlyStreams.hasRequiredItags())
    }

    @Test
    fun testRemotePlayerConfigParserSuccess() {
        val json = """
            {
                "schemaVersion": 1,
                "players": {
                    "241ef5e6": {
                        "sig": "fE(0,1,INPUT)",
                        "nClass": "abcDe",
                        "sts": 20409,
                        "aliases": ["a1b2c3d4"]
                    }
                }
            }
        """.trimIndent()

        val result = RemotePlayerConfigParser.parse(json)
        assertTrue(result is RemotePlayerConfigParser.ParseResult.Success)

        val success = result as RemotePlayerConfigParser.ParseResult.Success
        val config = success.configs["241ef5e6"]
        assertNotNull(config)
        assertEquals("fE(0,1,INPUT)", config!!.sigJsExpression)
        assertEquals(20409, config.signatureTimestamp)
        assertTrue(config.nJsExpression!!.contains("abcDe"))

        // Verify alias resolved to the same config
        val aliasConfig = success.configs["a1b2c3d4"]
        assertNotNull(aliasConfig)
        assertEquals(config, aliasConfig)
    }

    @Test
    fun testRemotePlayerConfigParserRejectsInvalidSchema() {
        val invalidSchemaJson = """
            {
                "schemaVersion": 2,
                "players": {}
            }
        """.trimIndent()
        val result = RemotePlayerConfigParser.parse(invalidSchemaJson)
        assertTrue(result is RemotePlayerConfigParser.ParseResult.Failure)
    }

    @Test
    fun testExtractPlayerHash() {
        val url1 = "https://www.youtube.com/s/player/241ef5e6/player_ias.vflset/en_GB/base.js"
        assertEquals("241ef5e6", RemotePlayerConfigParser.extractPlayerHash(url1))

        val url2 = "https://www.youtube.com/player/a8b9c0d1/base.js"
        assertEquals("a8b9c0d1", RemotePlayerConfigParser.extractPlayerHash(url2))

        val invalidUrl = "https://www.youtube.com/watch?v=12345"
        assertNull(RemotePlayerConfigParser.extractPlayerHash(invalidUrl))
    }

    @Test
    fun testZemerCipherSolverScriptModification() {
        val playerCode = "function a(){ return 1; })(_yt_player);"
        val config = RemotePlayerConfigParser.HardcodedPlayerConfig(
            sigFuncName = "_expr_sig",
            sigConstantArg = null,
            sigJsExpression = "fE(0,1,INPUT)",
            nFuncName = "_expr_n",
            nArrayIndex = null,
            nConstantArgs = null,
            nJsExpression = "(function(n){ return n; })(INPUT)",
            signatureTimestamp = 20000
        )

        val modified = ZemerCipherSolver.buildModifiedPlayerScript(playerCode, config)
        assertTrue(modified.contains("window._cipherSigFunc"))
        assertTrue(modified.contains("window._nTransformFunc"))
        assertTrue(modified.contains("})(_yt_player);"))
        assertTrue(modified.contains("fE(0,1,sig)"))
    }

    @Test
    fun testQuickJsEngineJsStringLiteralEscaping() {
        assertEquals("\"hello\"", QuickJsEngine.jsStringLiteral("hello"))
        assertEquals("\"hello \\\"world\\\"\"", QuickJsEngine.jsStringLiteral("hello \"world\""))
        assertEquals("\"line1\\nline2\"", QuickJsEngine.jsStringLiteral("line1\nline2"))
        assertEquals("\"back\\\\slash\"", QuickJsEngine.jsStringLiteral("back\\slash"))
    }

    @Test
    fun testStreamRepositoryCacheAndExpiryCheck() {
        val repo = SimpMusicStreamRepository()
        val videoId = "BddP6PYo2gs"

        assertFalse(repo.isCached(videoId))

        // Expiry parsing
        val futureExpire = (System.currentTimeMillis() / 1000L) + 3600L
        val testUrl = "https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?expire=$futureExpire&id=test"
        assertFalse(YouTubeService.isStreamUrlExpired(testUrl))

        val pastExpire = (System.currentTimeMillis() / 1000L) - 3600L
        val expiredUrl = "https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?expire=$pastExpire&id=test"
        assertTrue(YouTubeService.isStreamUrlExpired(expiredUrl))
    }

    @Test
    fun testLiveYouTubeServiceValidationAndTracking() {
        val service = LiveYouTubeService()

        // Direct catalog video ID check
        assertTrue(YouTubeService.isValidYouTubeVideoId("BddP6PYo2gs"))
        assertTrue(YouTubeService.isValidYouTubeVideoId("4NRXx6U8ABQ"))
        assertFalse(YouTubeService.isValidYouTubeVideoId("short"))
        assertFalse(YouTubeService.isValidYouTubeVideoId("toolongvideoid12345"))
        assertFalse(YouTubeService.isValidYouTubeVideoId("PL1234567890")) // Playlist ID prefix rejected
    }
}

