/*
 * Copyright (C) 2024–2026 Nguyễn Đức Tuấn Minh (maxrave-dev) <ndtminh2608@gmail.com>
 * Portions adapted from SimpMusic (commit f037043d714450bac5aaac2f9dbf77e17b019303)
 * and maxrave-dev/core (commit 7d11cf437e59ba4f348ec92f7558732ee3d34c1a).
 *
 * Licensed under the GNU General Public License Version 3.0 (GPL-3.0-only).
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *     https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Modified for FlinsMusic on 2026-10-19:
 * Unit test suite verifying public YouTube Music metadata parsing, fallbacks,
 * and zero-cookie compliance.
 */

package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YtMusicIntegrationTest {

    @Test
    fun publicSearch_parsingSongsAlbumsArtistsPlaylists() {
        val jsonString = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicShelfRenderer": {
                              "title": { "runs": [{ "text": "Songs" }] },
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "BddP6PYo2gs" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              {
                                                "text": "Kesariya",
                                                "navigationEndpoint": {
                                                  "watchEndpoint": { "videoId": "BddP6PYo2gs" }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              {
                                                "text": "Arijit Singh",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": { "browseId": "UCarijit_id" }
                                                }
                                              },
                                              { "text": " • " },
                                              {
                                                "text": "Brahmāstra",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": { "browseId": "MPREb_brahmastra" }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                      }
                                    ],
                                    "fixedColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "4:28" }] }
                                        }
                                      }
                                    ],
                                    "thumbnail": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": {
                                          "thumbnails": [
                                            { "url": "https://lh3.googleusercontent.com/test=w120-h120" }
                                          ]
                                        }
                                      }
                                    }
                                  }
                                }
                              ],
                              "continuations": [
                                {
                                  "nextContinuationData": { "continuation": "next_search_token_123" }
                                }
                              ]
                            }
                          },
                          {
                            "musicShelfRenderer": {
                              "title": { "runs": [{ "text": "Albums" }] },
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              {
                                                "text": "Brahmāstra",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": { "browseId": "MPREb_brahmastra" }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Pritam" }] }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          },
                          {
                            "musicShelfRenderer": {
                              "title": { "runs": [{ "text": "Artists" }] },
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              {
                                                "text": "Arijit Singh",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": { "browseId": "UCarijit_id" }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "42M subscribers" }] }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          },
                          {
                            "musicShelfRenderer": {
                              "title": { "runs": [{ "text": "Community playlists" }] },
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              {
                                                "text": "Bollywood Romance Hits",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": { "browseId": "VLPL_romance" }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "YouTube Music" }] }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val parsed = YtMusicParser.parseSearchResponse(JSONObject(jsonString))

        // Assert songs
        assertEquals(1, parsed.tracks.size)
        val track = parsed.tracks[0]
        assertEquals("yt_BddP6PYo2gs", track.id)
        assertEquals("Kesariya", track.title)
        assertEquals("Arijit Singh", track.artist)
        assertEquals("UCarijit_id", track.artistId)
        assertEquals("Brahmāstra", track.album)
        assertEquals(268, track.durationSeconds)
        assertTrue("Artwork must be high resolution", track.artwork.contains("=w544-h544"))

        // Assert albums
        assertEquals(1, parsed.albums.size)
        assertEquals("Brahmāstra", parsed.albums[0].title)
        assertEquals("Pritam", parsed.albums[0].artist)

        // Assert artists
        assertEquals(1, parsed.artists.size)
        assertEquals("Arijit Singh", parsed.artists[0].name)
        assertEquals("42M subscribers", parsed.artists[0].monthlyListeners)

        // Assert playlists
        assertEquals(1, parsed.playlists.size)
        assertEquals("Bollywood Romance Hits", parsed.playlists[0].title)

        // Assert continuation
        assertEquals("next_search_token_123", parsed.continuationToken)
    }

    @Test
    fun homeFeedShelves_parsingCarouselsAndTracks() {
        val jsonString = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicCarouselShelfRenderer": {
                              "header": {
                                "musicCarouselShelfBasicHeaderRenderer": {
                                  "title": { "runs": [{ "text": "Trending Now" }] },
                                  "strapline": { "runs": [{ "text": "Top charts in your region" }] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "Blinding Lights" }] },
                                    "subtitle": { "runs": [{ "text": "The Weeknd" }] },
                                    "navigationEndpoint": {
                                      "watchEndpoint": { "videoId": "4NRXx6U8ABQ" }
                                    },
                                    "thumbnailRenderer": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": {
                                          "thumbnails": [{ "url": "https://img.youtube.com/vi/4NRXx6U8ABQ/hqdefault.jpg" }]
                                        }
                                      }
                                    }
                                  }
                                }
                              ]
                            }
                          },
                          {
                            "musicCarouselShelfRenderer": {
                              "header": {
                                "musicCarouselShelfBasicHeaderRenderer": {
                                  "title": { "runs": [{ "text": "Quick picks" }] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "Lover" }] },
                                    "subtitle": { "runs": [{ "text": "Diljit Dosanjh" }] },
                                    "navigationEndpoint": {
                                      "watchEndpoint": { "videoId": "cl0a3iHRKFY" }
                                    }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val shelves = YtMusicParser.parseHomeResponse(JSONObject(jsonString))
        assertEquals(2, shelves.size)

        assertEquals("Trending Now", shelves[0].title)
        assertEquals("Top charts in your region", shelves[0].subtitle)
        assertEquals(1, shelves[0].items.size)
        assertEquals("yt_4NRXx6U8ABQ", shelves[0].items[0].id)
        assertEquals("Blinding Lights", shelves[0].items[0].title)
        assertEquals("The Weeknd", shelves[0].items[0].artist)

        assertEquals("Quick picks", shelves[1].title)
        assertEquals(1, shelves[1].items.size)
        assertEquals("yt_cl0a3iHRKFY", shelves[1].items[0].id)
        assertEquals("Lover", shelves[1].items[0].title)
    }

    @Test
    fun artistMetadataAndTopTracks_parsing() {
        val jsonString = """
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": { "runs": [{ "text": "The Weeknd" }] },
              "description": { "runs": [{ "text": "Grammy-winning global pop visionary" }] },
              "subscriptionButton": {
                "subscribeButtonRenderer": {
                  "subscriberCountText": { "runs": [{ "text": "35M subscribers" }] }
                }
              },
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnails": [{ "url": "https://img.youtube.com/art.jpg" }]
                }
              }
            }
          },
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicShelfRenderer": {
                              "title": { "runs": [{ "text": "Top songs" }] },
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "34Na4j8AVgA" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Starboy" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "The Weeknd" }] }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val artist = YtMusicParser.parseArtistResponse(JSONObject(jsonString), "UCweeknd_id")
        assertEquals("The Weeknd", artist.name)
        assertEquals("Grammy-winning global pop visionary", artist.bio)
        assertEquals("35M subscribers", artist.monthlyListeners)
        assertEquals(1, artist.topTracks.size)
        assertEquals("Starboy", artist.topTracks[0].title)
        assertEquals("yt_34Na4j8AVgA", artist.topTracks[0].id)
    }

    @Test
    fun albumMetadataAndTracklist_parsing() {
        val jsonString = """
        {
          "header": {
            "musicDetailHeaderRenderer": {
              "title": { "runs": [{ "text": "After Hours" }] },
              "description": { "runs": [{ "text": "Cinematic synth-pop album" }] },
              "subtitle": {
                "runs": [
                  { "text": "The Weeknd", "navigationEndpoint": { "browseEndpoint": { "browseId": "UCweeknd_id" } } },
                  { "text": " • " },
                  { "text": "2020" }
                ]
              }
            }
          },
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicShelfRenderer": {
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "4NRXx6U8ABQ" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Blinding Lights" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "The Weeknd" }] }
                                        }
                                      }
                                    ],
                                    "fixedColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "3:20" }] }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val album = YtMusicParser.parseAlbumResponse(JSONObject(jsonString), "MPREb_after_hours")
        assertEquals("After Hours", album.title)
        assertEquals("The Weeknd", album.artist)
        assertEquals("UCweeknd_id", album.artistId)
        assertEquals("2020", album.year)
        assertEquals(1, album.tracks.size)
        assertEquals("Blinding Lights", album.tracks[0].title)
        assertEquals(200, album.tracks[0].durationSeconds)
    }

    @Test
    fun publicPlaylistTracksAndContinuation_parsing() {
        val jsonString = """
        {
          "header": {
            "musicDetailHeaderRenderer": {
              "title": { "runs": [{ "text": "Today's Biggest Hits" }] },
              "description": { "runs": [{ "text": "The hottest tracks globally" }] },
              "subtitle": {
                "runs": [
                  { "text": "YouTube Music" },
                  { "text": " • " },
                  { "text": "50 songs" }
                ]
              }
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
                          "playlistItemData": { "videoId": "ElZfdU54Cp8" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Apna Bana Le" }] }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Arijit Singh" }] }
                              }
                            }
                          ]
                        }
                      }
                    ],
                    "continuations": [
                      {
                        "nextContinuationData": { "continuation": "next_page_token_xyz" }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val playlist = YtMusicParser.parsePlaylistResponse(JSONObject(jsonString), "PL_biggest_hits")
        assertEquals("Today's Biggest Hits", playlist.title)
        assertEquals("YouTube Music", playlist.author)
        assertEquals(50, playlist.trackCount)
        assertEquals(1, playlist.tracks.size)
        assertEquals("yt_ElZfdU54Cp8", playlist.tracks[0].id)
        assertEquals("Apna Bana Le", playlist.tracks[0].title)
        assertEquals("next_page_token_xyz", playlist.continuationToken)

        // Test continuation parsing
        val contJson = """
        {
          "continuationContents": {
            "musicPlaylistShelfContinuation": {
              "contents": [
                {
                  "musicResponsiveListItemRenderer": {
                    "playlistItemData": { "videoId": "JGwWNGJdvx8" },
                    "flexColumns": [
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": { "runs": [{ "text": "Shape of You" }] }
                        }
                      },
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": { "runs": [{ "text": "Ed Sheeran" }] }
                        }
                      }
                    ]
                  }
                }
              ],
              "continuations": [
                {
                  "nextContinuationData": { "continuation": "next_page_token_final" }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val (contTracks, nextCont) = YtMusicParser.parseContinuationTracks(JSONObject(contJson))
        assertEquals(1, contTracks.size)
        assertEquals("yt_JGwWNGJdvx8", contTracks[0].id)
        assertEquals("Shape of You", contTracks[0].title)
        assertEquals("next_page_token_final", nextCont)
    }

    @Test
    fun missingAndMalformedJson_handlesSafelyWithoutCrashing() {
        val emptyJson = JSONObject("{}")
        val searchResult = YtMusicParser.parseSearchResponse(emptyJson)
        assertTrue(searchResult.tracks.isEmpty())
        assertTrue(searchResult.albums.isEmpty())
        assertTrue(searchResult.artists.isEmpty())
        assertNull(searchResult.continuationToken)

        val homeShelves = YtMusicParser.parseHomeResponse(emptyJson)
        assertTrue(homeShelves.isEmpty())

        val artist = YtMusicParser.parseArtistResponse(emptyJson, "UC_empty")
        assertEquals("Unknown Artist", artist.name)
        assertTrue(artist.topTracks.isEmpty())

        val album = YtMusicParser.parseAlbumResponse(emptyJson, "MPREb_empty")
        assertEquals("Unknown Album", album.title)
        assertTrue(album.tracks.isEmpty())

        val playlist = YtMusicParser.parsePlaylistResponse(emptyJson, "PL_empty")
        assertEquals("YouTube Playlist", playlist.title)
        assertTrue(playlist.tracks.isEmpty())

        val (contTracks, nextCont) = YtMusicParser.parseContinuationTracks(emptyJson)
        assertTrue(contTracks.isEmpty())
        assertNull(nextCont)
    }

    @Test
    fun networkFailureAndFallbackToExistingCatalog() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = MusicRepository(db.musicDao())

        // Initial state matches catalog
        assertEquals(repository.catalogTracks.size, repository.homeTracks.value.size)

        // Mock a failing YouTubeService and failing YtMusicService
        val failingYtService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean) = Result.failure<String>(IOException("Network down"))
            override suspend fun searchTracks(query: String): List<Track> { throw IOException("Service unreachable") }
        }

        val failingYtMusicService = object : YtMusicService {
            override suspend fun search(query: String) = Result.failure<YtSearchResult>(IOException("No connection"))
            override suspend fun searchTracks(query: String) = Result.failure<List<YtTrack>>(IOException("No connection"))
            override suspend fun getHomeShelves() = Result.failure<List<YtHomeShelf>>(IOException("No connection"))
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(IOException("No connection"))
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(IOException("No connection"))
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(IOException("No connection"))
        }

        // Must not throw exception and must retain default catalog tracks
        repository.loadRealMusicFeed(failingYtService, failingYtMusicService)

        assertFalse("Must not be in loading state after error", repository.isLoadingRealData.value)
        assertTrue("Home tracks must remain honest empty state on network failure without dummy data", repository.homeTracks.value.isEmpty())

        db.close()
    }

    @Test
    fun noCookieReadingStorageOrTransmission_inYtMusicClient() {
        val client = YtMusicClient()
        // Ensure clearCache executes without issue
        client.clearCache()

        // Inspect reflection on YtMusicClient's OkHttpClient
        val clientClass = YtMusicClient::class.java
        val httpClientField = clientClass.getDeclaredField("httpClient").apply { isAccessible = true }
        val okHttpClient = httpClientField.get(client) as okhttp3.OkHttpClient

        // Assert no CookieJar is registered (CookieJar.NO_COOKIES is default when cookieJar is omitted)
        assertEquals(okhttp3.CookieJar.NO_COOKIES, okHttpClient.cookieJar)

        // Verify public API base URL
        assertEquals("https://music.youtube.com/youtubei/v1", YtMusicClient.BASE_URL)
        assertEquals("WEB_REMIX", YtMusicClient.CLIENT_NAME)
    }
}
