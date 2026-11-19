package com.example.service.importer

import com.example.service.importer.extractor.YouTubeMusicPlaylistExtractor
import com.example.service.importer.model.PlatformType
import com.example.service.integrations.ytmusic.YtMusicClient
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicPlaylistExtractorTest {

    private fun buildMockClient(browseResponseJson: String, continuationResponseJson: String? = null): YtMusicClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val bodyStr = if (url.contains("continuation=")) {
                    continuationResponseJson ?: "{}"
                } else {
                    browseResponseJson
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyStr.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        return YtMusicClient(okHttpClient)
    }

    @Test
    fun extractPlaylist_withPagination_combinesAllPages() = runTest {
        val initialBrowseJson = """
        {
          "header": {
            "musicDetailHeaderRenderer": {
              "title": { "runs": [{ "text": "Global Top 100" }] },
              "description": { "runs": [{ "text": "Top tracks on YouTube Music" }] },
              "subtitle": { "runs": [{ "text": "YouTube Music" }, { "text": " • " }, { "text": "2 songs" }] }
            }
          },
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicPlaylistShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "vid_page1_1" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Lover" }] }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Diljit Dosanjh" }] }
                              }
                            }
                          ],
                          "fixedColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "3:18" }] }
                              }
                            }
                          ]
                        }
                      }
                    ],
                    "continuations": [
                      {
                        "nextContinuationData": { "continuation": "next_page_token_abc" }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val contJson = """
        {
          "continuationContents": {
            "musicPlaylistShelfContinuation": {
              "contents": [
                {
                  "musicResponsiveListItemRenderer": {
                    "playlistItemData": { "videoId": "vid_page2_1" },
                    "flexColumns": [
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": { "runs": [{ "text": "Believer" }] }
                        }
                      },
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": { "runs": [{ "text": "Imagine Dragons" }] }
                        }
                      }
                    ],
                    "fixedColumns": [
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": { "runs": [{ "text": "3:24" }] }
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val ytClient = buildMockClient(initialBrowseJson, contJson)
        val extractor = YouTubeMusicPlaylistExtractor(ytClient)

        var lastLoaded = 0
        val result = extractor.extractPlaylist("PL_global_100") { loaded, _ ->
            lastLoaded = loaded
        }

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(PlatformType.YOUTUBE_MUSIC, data.source)
        assertEquals("Global Top 100", data.playlistName)
        assertEquals(2, data.totalTracks)
        assertEquals(2, lastLoaded)

        // Track 1 from page 1
        assertEquals("Lover", data.tracks[0].title)
        assertEquals("Diljit Dosanjh", data.tracks[0].primaryArtist)
        assertEquals(198000L, data.tracks[0].durationMs)
        assertEquals(0, data.tracks[0].position)

        // Track 2 from page 2 (pagination verified)
        assertEquals("Believer", data.tracks[1].title)
        assertEquals("Imagine Dragons", data.tracks[1].primaryArtist)
        assertEquals(204000L, data.tracks[1].durationMs)
        assertEquals(1, data.tracks[1].position)
    }

    @Test
    fun extractPlaylist_emptyOrPrivate_reportsFailure() = runTest {
        val emptyBrowseJson = "{}"
        val ytClient = buildMockClient(emptyBrowseJson)
        val extractor = YouTubeMusicPlaylistExtractor(ytClient)

        val result = extractor.extractPlaylist("PL_empty")
        assertTrue(result.isFailure)
    }
}

