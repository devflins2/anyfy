package com.example.service.importer

import com.example.service.importer.model.PlatformType
import com.example.service.importer.url.PlaylistUrlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistUrlParserTest {

    @Test
    fun parse_validSpotifyUrls_success() {
        // Standard HTTPS URL
        val url1 = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val res1 = PlaylistUrlParser.parse(url1)
        assertTrue(res1.isSuccess)
        val data1 = res1.getOrThrow()
        assertEquals(PlatformType.SPOTIFY, data1.platform)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", data1.playlistId)

        // URL with query parameters
        val url2 = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=e2f83196929c490a&pt=abcd"
        val res2 = PlaylistUrlParser.parse(url2)
        assertTrue(res2.isSuccess)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", res2.getOrThrow().playlistId)

        // Without protocol prefix
        val url3 = "open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val res3 = PlaylistUrlParser.parse(url3)
        assertTrue(res3.isSuccess)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", res3.getOrThrow().playlistId)

        // spotify:playlist: URI format
        val url4 = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        val res4 = PlaylistUrlParser.parse(url4)
        assertTrue(res4.isSuccess)
        assertEquals(PlatformType.SPOTIFY, res4.getOrThrow().platform)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", res4.getOrThrow().playlistId)
    }

    @Test
    fun parse_validYouTubeMusicUrls_success() {
        // Standard music.youtube.com
        val url1 = "https://music.youtube.com/playlist?list=PL4fGSI1pDJn6jXS_PECE3hPB_tw_R5Gbp"
        val res1 = PlaylistUrlParser.parse(url1)
        assertTrue(res1.isSuccess)
        val data1 = res1.getOrThrow()
        assertEquals(PlatformType.YOUTUBE_MUSIC, data1.platform)
        assertEquals("PL4fGSI1pDJn6jXS_PECE3hPB_tw_R5Gbp", data1.playlistId)

        // Standard www.youtube.com
        val url2 = "https://www.youtube.com/playlist?list=PL4fGSI1pDJn6jXS_PECE3hPB_tw_R5Gbp&si=test_123"
        val res2 = PlaylistUrlParser.parse(url2)
        assertTrue(res2.isSuccess)
        assertEquals("PL4fGSI1pDJn6jXS_PECE3hPB_tw_R5Gbp", res2.getOrThrow().playlistId)

        // RD / OLAK album/mix playlist IDs
        val url3 = "https://music.youtube.com/playlist?list=OLAK5uy_kQy56g7s"
        val res3 = PlaylistUrlParser.parse(url3)
        assertTrue(res3.isSuccess)
        assertEquals("OLAK5uy_kQy56g7s", res3.getOrThrow().playlistId)
    }

    @Test
    fun parse_invalidUrlsAndBlank_failsGracefully() {
        assertTrue(PlaylistUrlParser.parse("").isFailure)
        assertTrue(PlaylistUrlParser.parse("   ").isFailure)
        assertTrue(PlaylistUrlParser.parse("not a url").isFailure)
        assertTrue(PlaylistUrlParser.parse("https://open.spotify.com/track/123456").isFailure)
        assertTrue(PlaylistUrlParser.parse("https://music.youtube.com/watch?v=12345678901").isFailure)
    }

    @Test
    fun parse_ssrfAndInternalNetworkRejection() {
        // Localhost rejection
        val localRes = PlaylistUrlParser.parse("https://localhost/playlist/123")
        assertTrue(localRes.isFailure)

        // Internal IPv4 rejection
        val ipRes1 = PlaylistUrlParser.parse("https://127.0.0.1/playlist?list=PL123456789012")
        assertTrue(ipRes1.isFailure)

        val ipRes2 = PlaylistUrlParser.parse("https://192.168.1.1/playlist/123")
        assertTrue(ipRes2.isFailure)

        // Untrusted foreign host
        val foreignRes = PlaylistUrlParser.parse("https://evil-phishing-site.com/playlist?list=PL123456789012")
        assertTrue(foreignRes.isFailure)
    }
}

