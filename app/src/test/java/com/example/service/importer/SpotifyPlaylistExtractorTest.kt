package com.example.service.importer

import com.example.service.importer.extractor.SpotifyPlaylistExtractor
import com.example.service.importer.model.PlatformType
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistExtractorTest {

    private fun buildMockClient(statusCode: Int, responseBody: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .body(responseBody.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun extractPlaylist_validPublicPayload_success() = runTest {
        val sampleNextDataHtml = """
        <!DOCTYPE html><html><head>
        <script id="__NEXT_DATA__" type="application/json">
        {
          "props": {
            "pageProps": {
              "state": {
                "data": {
                  "entity": {
                    "name": "Today's Top Hits",
                    "subtitle": "Spotify Curated",
                    "coverArt": {
                      "sources": [{ "url": "https://i.scdn.co/image/top_hits.jpg" }]
                    },
                    "trackList": [
                      {
                        "uri": "spotify:track:trk_123",
                        "title": "Blinding Lights",
                        "subtitle": "The Weeknd",
                        "duration": 200000,
                        "isPlayable": true
                      },
                      {
                        "uri": "spotify:track:trk_456",
                        "title": "Kesariya",
                        "subtitle": "Arijit Singh, Pritam",
                        "duration": 268000,
                        "isPlayable": true
                      }
                    ]
                  }
                }
              }
            }
          }
        }
        </script></head><body></body></html>
        """.trimIndent()

        val mockClient = buildMockClient(200, sampleNextDataHtml)
        val extractor = SpotifyPlaylistExtractor(mockClient)

        var loadedCount = 0
        val result = extractor.extractPlaylist("37i9dQZF1DXcBWIGoYBM5M") { loaded, _ ->
            loadedCount = loaded
        }

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(PlatformType.SPOTIFY, data.source)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", data.sourcePlaylistId)
        assertEquals("Today's Top Hits", data.playlistName)
        assertEquals("https://i.scdn.co/image/top_hits.jpg", data.artwork)
        assertEquals(2, data.totalTracks)
        assertEquals(2, loadedCount)

        // Verify Track 1
        assertEquals("Blinding Lights", data.tracks[0].title)
        assertEquals("The Weeknd", data.tracks[0].primaryArtist)
        assertEquals(200000L, data.tracks[0].durationMs)
        assertEquals(0, data.tracks[0].position)

        // Verify Track 2
        assertEquals("Kesariya", data.tracks[1].title)
        assertEquals(2, data.tracks[1].artists.size)
        assertEquals("Arijit Singh", data.tracks[1].primaryArtist)
        assertEquals(1, data.tracks[1].position)
    }

    @Test
    fun extractPlaylist_emptyPlaylist_failsWithHelpfulError() = runTest {
        val emptyHtml = """
        <!DOCTYPE html><html><head>
        <script id="__NEXT_DATA__" type="application/json">
        {
          "props": {
            "pageProps": {
              "state": {
                "data": {
                  "entity": {
                    "name": "Empty Playlist",
                    "trackList": []
                  }
                }
              }
            }
          }
        }
        </script></head><body></body></html>
        """.trimIndent()

        val mockClient = buildMockClient(200, emptyHtml)
        val extractor = SpotifyPlaylistExtractor(mockClient)

        val result = extractor.extractPlaylist("empty_pl_id")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("contains no public tracks") == true)
    }

    @Test
    fun extractPlaylist_404NotFoundOrPrivate_reportsPrivateError() = runTest {
        val mockClient = buildMockClient(404, "Not Found")
        val extractor = SpotifyPlaylistExtractor(mockClient)

        val result = extractor.extractPlaylist("private_pl_id")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found or is set to private") == true)
    }

    @Test
    fun extractPlaylist_blankId_failsEarly() = runTest {
        val mockClient = buildMockClient(200, "")
        val extractor = SpotifyPlaylistExtractor(mockClient)

        val result = extractor.extractPlaylist("   ")
        assertTrue(result.isFailure)
    }
}

