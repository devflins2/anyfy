package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Album
import com.example.model.Track
import com.example.service.integrations.ytmusic.YtAlbum
import com.example.service.integrations.ytmusic.YtHomeShelf
import com.example.service.integrations.ytmusic.YtMusicParser
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtSearchResult
import com.example.service.integrations.ytmusic.YtTrack
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.SubScreenDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GlobalAlbumDetailSystemRegressionTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. ALBUM 1: Modern InnerTube musicResponsiveHeaderRenderer (Coldplay - Viva La Vida)
    // =========================================================================
    @Test
    fun test01_album_musicResponsiveHeaderRenderer_with_straplineTextOne_artist() {
        val json = JSONObject("""
        {
          "header": {
            "musicResponsiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Viva La Vida or Death and All His Friends" }] },
              "straplineTextOne": {
                "runs": [
                  {
                    "text": "Coldplay",
                    "navigationEndpoint": {
                      "browseEndpoint": {
                        "browseId": "UCtop_coldplay_id",
                        "browseEndpointContextSupportedConfigs": {
                          "browseEndpointContextMusicConfig": {
                            "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                          }
                        }
                      }
                    }
                  }
                ]
              },
              "subtitle": {
                "runs": [
                  { "text": "Album" },
                  { "text": " • " },
                  { "text": "2008" }
                ]
              },
              "secondSubtitle": {
                "runs": [{ "text": "10 songs • 45 minutes" }]
              },
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": {
                    "thumbnails": [
                      { "url": "https://lh3.googleusercontent.com/viva_sq=w120-h120", "width": 120, "height": 120 },
                      { "url": "https://lh3.googleusercontent.com/viva_wide=w1280-h720", "width": 1280, "height": 720 }
                    ]
                  }
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
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "viva_001" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Life in Technicolor" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Coldplay" }] }
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
        """.trimIndent())

        val ytAlbum = YtMusicParser.parseAlbumResponse(json, "MPREb_ColdplayViva")
        assertEquals("MPREb_ColdplayViva", ytAlbum.id)
        assertEquals("Viva La Vida or Death and All His Friends", ytAlbum.title)
        assertEquals("Coldplay", ytAlbum.artist)
        assertEquals("UCtop_coldplay_id", ytAlbum.artistId)
        assertEquals("2008", ytAlbum.year)
        assertEquals("Album", ytAlbum.type)
        assertEquals(1, ytAlbum.tracks.size)
        assertEquals("Life in Technicolor", ytAlbum.tracks[0].title)
        assertEquals("MPREb_ColdplayViva", ytAlbum.tracks[0].albumId)
        assertFalse(ytAlbum.artwork.isBlank())
        assertFalse(ytAlbum.bannerArtwork.isBlank())

        val domainAlbum = ytAlbum.toAlbum()
        assertEquals("MPREb_ColdplayViva", domainAlbum.id)
        assertEquals("Viva La Vida or Death and All His Friends", domainAlbum.title)
        assertEquals("Coldplay", domainAlbum.artist)
        assertEquals("UCtop_coldplay_id", domainAlbum.artistId)
        assertEquals("2008", domainAlbum.releaseYear)
        assertEquals("Album", domainAlbum.type)
        assertNotEquals("Unknown Album", domainAlbum.title)
        assertNotEquals("Various Artists", domainAlbum.artist)
    }

    // =========================================================================
    // 2. ALBUM 2: InnerTube musicDetailHeaderRenderer with artist in subtitle text runs (Aashiqui 2)
    // =========================================================================
    @Test
    fun test02_album_musicDetailHeaderRenderer_with_plain_text_subtitle_artist() {
        val json = JSONObject("""
        {
          "header": {
            "musicDetailHeaderRenderer": {
              "title": { "runs": [{ "text": "Aashiqui 2" }] },
              "subtitle": {
                "runs": [
                  { "text": "Album" },
                  { "text": " • " },
                  { "text": "Mithoon & Ankit Tiwari" },
                  { "text": " • " },
                  { "text": "2013" }
                ]
              },
              "thumbnail": {
                "croppedSquareThumbnailRenderer": {
                  "thumbnail": {
                    "thumbnails": [{ "url": "https://img.youtube.com/aashiqui2.jpg", "width": 544, "height": 544 }]
                  }
                }
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
                          "playlistItemData": { "videoId": "tum_hi_ho_id" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Tum Hi Ho" }] }
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
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent())

        val ytAlbum = YtMusicParser.parseAlbumResponse(json, "MPREb_Aashiqui2")
        assertEquals("Aashiqui 2", ytAlbum.title)
        assertEquals("Mithoon & Ankit Tiwari", ytAlbum.artist)
        assertEquals("2013", ytAlbum.year)
        assertEquals(1, ytAlbum.tracks.size)
        assertEquals("Tum Hi Ho", ytAlbum.tracks[0].title)

        val domain = ytAlbum.toAlbum()
        assertEquals("Aashiqui 2", domain.title)
        assertEquals("Mithoon & Ankit Tiwari", domain.artist)
        assertEquals("2013", domain.releaseYear)
        assertNotEquals("Various Artists", domain.artist)
    }

    // =========================================================================
    // 3. ALBUM 3: Single release with 1 track (Hamza Malik - Alfaaz)
    // =========================================================================
    @Test
    fun test03_single_release_identifies_type_and_preserves_genuine_artist() {
        val json = JSONObject("""
        {
          "header": {
            "musicResponsiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Alfaaz" }] },
              "straplineTextOne": {
                "runs": [{ "text": "Hamza Malik & Zain Zohaib" }]
              },
              "subtitle": {
                "runs": [
                  { "text": "Single" },
                  { "text": " • " },
                  { "text": "2021" }
                ]
              },
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": {
                    "thumbnails": [
                      { "url": "https://lh3.googleusercontent.com/alfaaz_art=w544-h544", "width": 544, "height": 544 }
                    ]
                  }
                }
              }
            }
          },
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "alfaaz_vid_1" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Alfaaz" }] }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Hamza Malik & Zain Zohaib" }] }
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
        """.trimIndent())

        val ytAlbum = YtMusicParser.parseAlbumResponse(json, "MPREb_AlfaazSingle")
        assertEquals("MPREb_AlfaazSingle", ytAlbum.id)
        assertEquals("Alfaaz", ytAlbum.title)
        assertEquals("Hamza Malik & Zain Zohaib", ytAlbum.artist)
        assertEquals("2021", ytAlbum.year)
        assertEquals("Single", ytAlbum.type)
        assertEquals(1, ytAlbum.tracks.size)

        val domain = ytAlbum.toAlbum()
        assertEquals("Single", domain.type)
        assertEquals("Alfaaz", domain.title)
        assertEquals("Hamza Malik & Zain Zohaib", domain.artist)
        assertNotEquals("Various Artists", domain.artist)
    }

    // =========================================================================
    // 4. ALBUM 4: EP release with "EP" in title (Coldplay - Kaleidoscope EP)
    // =========================================================================
    @Test
    fun test04_ep_release_identifies_type_properly() {
        val json = JSONObject("""
        {
          "header": {
            "musicResponsiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Kaleidoscope EP" }] },
              "straplineTextOne": {
                "runs": [{ "text": "Coldplay" }]
              },
              "subtitle": {
                "runs": [
                  { "text": "EP" },
                  { "text": " • " },
                  { "text": "2017" }
                ]
              }
            }
          },
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "aliens_vid_1" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "A L I E N S" }] }
                              }
                            }
                          ]
                        }
                      },
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "miracles_vid_2" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Miracles (Someone Special)" }] }
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
        """.trimIndent())

        val ytAlbum = YtMusicParser.parseAlbumResponse(json, "MPREb_KaleidoscopeEP")
        assertEquals("EP", ytAlbum.type)
        assertEquals("Kaleidoscope EP", ytAlbum.title)
        assertEquals("Coldplay", ytAlbum.artist)
        assertEquals("2017", ytAlbum.year)
        assertEquals(2, ytAlbum.tracks.size)

        val domain = ytAlbum.toAlbum()
        assertEquals("EP", domain.type)
        assertEquals("Kaleidoscope EP", domain.title)
        assertEquals("Coldplay", domain.artist)
    }

    // =========================================================================
    // 5. ALBUM 5: Multi-artist album with artist in bylineText (Kabir Singh)
    // =========================================================================
    @Test
    fun test05_album_with_bylineText_artist_extraction() {
        val json = JSONObject("""
        {
          "header": {
            "musicResponsiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Kabir Singh" }] },
              "bylineText": {
                "runs": [
                  {
                    "text": "Sachet-Parampara",
                    "navigationEndpoint": {
                      "browseEndpoint": { "browseId": "UC_sachet_parampara" }
                    }
                  }
                ]
              },
              "subtitle": {
                "runs": [
                  { "text": "Album" },
                  { "text": " • " },
                  { "text": "2019" }
                ]
              }
            }
          },
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "bekhayali_1" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Bekhayali" }] }
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
        """.trimIndent())

        val ytAlbum = YtMusicParser.parseAlbumResponse(json, "MPREb_KabirSingh")
        assertEquals("Kabir Singh", ytAlbum.title)
        assertEquals("Sachet-Parampara", ytAlbum.artist)
        assertEquals("UC_sachet_parampara", ytAlbum.artistId)
        assertEquals("2019", ytAlbum.year)
        assertNotEquals("Various Artists", ytAlbum.artist)
    }

    // =========================================================================
    // 6. ALBUM 6: musicVisualHeaderRenderer (The Weeknd - After Hours)
    // =========================================================================
    @Test
    fun test06_album_musicVisualHeaderRenderer_parsed_correctly() {
        val json = JSONObject("""
        {
          "header": {
            "musicVisualHeaderRenderer": {
              "title": { "runs": [{ "text": "After Hours" }] },
              "straplineTextOne": {
                "runs": [
                  {
                    "text": "The Weeknd",
                    "navigationEndpoint": { "browseEndpoint": { "browseId": "UCweeknd_id" } }
                  }
                ]
              },
              "subtitle": {
                "runs": [{ "text": "2020" }]
              }
            }
          },
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "blinding_lights_id" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Blinding Lights" }] }
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
        """.trimIndent())

        val ytAlbum = YtMusicParser.parseAlbumResponse(json, "MPREb_AfterHours")
        assertEquals("After Hours", ytAlbum.title)
        assertEquals("The Weeknd", ytAlbum.artist)
        assertEquals("UCweeknd_id", ytAlbum.artistId)
        assertEquals("2020", ytAlbum.year)
    }

    // =========================================================================
    // 7. ALBUM 7: OLAK5uy_ album format with musicEditablePlaylistHeaderRenderer
    // =========================================================================
    @Test
    fun test07_album_OLAK_format_with_nested_editable_header() {
        val json = JSONObject("""
        {
          "header": {
            "musicEditablePlaylistHeaderRenderer": {
              "header": {
                "musicResponsiveHeaderRenderer": {
                  "title": { "runs": [{ "text": "Divide" }] },
                  "straplineTextOne": {
                    "runs": [
                      {
                        "text": "Ed Sheeran",
                        "navigationEndpoint": { "browseEndpoint": { "browseId": "UC_ed_sheeran" } }
                      }
                    ]
                  },
                  "subtitle": {
                    "runs": [{ "text": "Album • 2017" }]
                  }
                }
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
                          "playlistItemData": { "videoId": "shape_of_you_id" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Shape of You" }] }
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
        """.trimIndent())

        val ytAlbum = YtMusicParser.parseAlbumResponse(json, "OLAK5uy_EdSheeranDivide")
        assertEquals("OLAK5uy_EdSheeranDivide", ytAlbum.id)
        assertEquals("Divide", ytAlbum.title)
        assertEquals("Ed Sheeran", ytAlbum.artist)
        assertEquals("UC_ed_sheeran", ytAlbum.artistId)
        assertEquals("2017", ytAlbum.year)

        val domain = ytAlbum.toAlbum()
        assertEquals("OLAK5uy_EdSheeranDivide", domain.id)
        assertEquals("Divide", domain.title)
        assertEquals("Ed Sheeran", domain.artist)
    }

    // =========================================================================
    // 8. ALBUM 8: Header nested in twoColumnBrowseResultsRenderer tabs
    // =========================================================================
    @Test
    fun test08_album_header_in_twoColumn_tabs() {
        val json = JSONObject("""
        {
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicResponsiveHeaderRenderer": {
                              "title": { "runs": [{ "text": "Future Nostalgia" }] },
                              "straplineTextOne": {
                                "runs": [{ "text": "Dua Lipa" }]
                              },
                              "subtitle": {
                                "runs": [{ "text": "Album • 2020" }]
                              }
                            }
                          },
                          {
                            "musicShelfRenderer": {
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "dont_start_now" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Don't Start Now" }] }
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
        """.trimIndent())

        val ytAlbum = YtMusicParser.parseAlbumResponse(json, "MPREb_FutureNostalgia")
        assertEquals("Future Nostalgia", ytAlbum.title)
        assertEquals("Dua Lipa", ytAlbum.artist)
        assertEquals("2020", ytAlbum.year)
        assertEquals(1, ytAlbum.tracks.size)
        assertEquals("Don't Start Now", ytAlbum.tracks[0].title)
    }

    // =========================================================================
    // 9. ALBUM 9: Paginated album tracklist continuation token parsing
    // =========================================================================
    @Test
    fun test09_album_tracklist_continuation_token_extraction() {
        val root = JSONObject("""
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicPlaylistShelfRenderer": {
                    "contents": [],
                    "continuations": [
                      {
                        "nextContinuationData": {
                          "continuation": "album_continuation_token_xyz"
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent())

        val token = YtMusicParser.extractAlbumContinuationToken(root)
        assertEquals("album_continuation_token_xyz", token)

        val contJson = JSONObject("""
        {
          "continuationContents": {
            "musicPlaylistShelfContinuation": {
              "contents": [
                {
                  "musicResponsiveListItemRenderer": {
                    "playlistItemData": { "videoId": "paged_track_1" },
                    "flexColumns": [
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": { "runs": [{ "text": "Track 51 Continued" }] }
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent())

        val (tracks, nextToken) = YtMusicParser.parseContinuationTracks(contJson)
        assertEquals(1, tracks.size)
        assertEquals("Track 51 Continued", tracks[0].title)
    }

    // =========================================================================
    // 10. ALBUM 10: Fallback metadata retention on partial/failing API response
    // =========================================================================
    @Test
    fun test10_fallback_metadata_retained_on_empty_or_failing_response() {
        val emptyJson = JSONObject("{}")
        val fallbackCard = Album(
            id = "MPREb_OriginalCardId",
            title = "Aashiqui 2",
            artist = "Arijit Singh",
            artistId = "UC_arijit",
            artwork = "https://img.youtube.com/aashiqui2.jpg",
            bannerArtwork = "https://img.youtube.com/aashiqui2_wide.jpg",
            releaseYear = "2013",
            type = "Album"
        )

        // Parse with fallback parameters
        val ytAlbum = YtMusicParser.parseAlbumResponse(
            root = emptyJson,
            albumId = fallbackCard.id,
            fallbackTitle = fallbackCard.title,
            fallbackArtist = fallbackCard.artist,
            fallbackArtwork = fallbackCard.artwork
        )

        assertEquals("MPREb_OriginalCardId", ytAlbum.id)
        assertEquals("Aashiqui 2", ytAlbum.title)
        assertEquals("Arijit Singh", ytAlbum.artist)
        assertEquals("https://img.youtube.com/aashiqui2.jpg", ytAlbum.artwork)

        // Convert to domain model using toAlbum(fallbackCard)
        val domain = ytAlbum.toAlbum(fallbackCard)
        assertEquals("MPREb_OriginalCardId", domain.id)
        assertEquals("Aashiqui 2", domain.title)
        assertEquals("Arijit Singh", domain.artist)
        assertEquals("UC_arijit", domain.artistId)
        assertEquals("https://img.youtube.com/aashiqui2.jpg", domain.artwork)
        assertEquals("https://img.youtube.com/aashiqui2_wide.jpg", domain.bannerArtwork)
        assertEquals("2013", domain.releaseYear)
        assertEquals("Album", domain.type)
        assertNotEquals("Unknown Album", domain.title)
        assertNotEquals("Various Artists", domain.artist)
    }

    // =========================================================================
    // 11. REPOSITORY & VIEWMODEL: Strict cache by browseId, no cross-album reuse
    // =========================================================================
    @Test
    fun test11_repository_strict_cache_prevents_cross_album_reuse_for_singles() = runTest {
        val repo = MusicRepository(AppDatabase.getInstance(context).musicDao())

        val mockService = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<com.example.service.integrations.ytmusic.YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String): Result<YtAlbum> {
                return when (browseId) {
                    "MPREb_Alfaaz" -> Result.success(
                        YtAlbum(
                            id = "MPREb_Alfaaz",
                            title = "Single",
                            artist = "Hamza Malik",
                            artwork = "https://img.com/alfaaz.jpg",
                            type = "Single",
                            tracks = listOf(YtTrack(id = "yt_alfaaz_trk", title = "Alfaaz", artist = "Hamza Malik"))
                        )
                    )
                    "MPREb_Titli" -> Result.success(
                        YtAlbum(
                            id = "MPREb_Titli",
                            title = "Single",
                            artist = "Darshan Raval",
                            artwork = "https://img.com/titli.jpg",
                            type = "Single",
                            tracks = listOf(YtTrack(id = "yt_titli_trk", title = "Titli", artist = "Darshan Raval"))
                        )
                    )
                    else -> Result.failure(Exception("Unknown album"))
                }
            }
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<com.example.service.integrations.ytmusic.YtPlaylist>(Exception())
        }

        // Fetch Alfaaz
        val fetchedAlfaaz = repo.fetchYtAlbum("MPREb_Alfaaz", "Single", mockService)
        assertNotNull(fetchedAlfaaz)
        assertEquals("MPREb_Alfaaz", fetchedAlfaaz?.id)
        assertEquals("Hamza Malik", fetchedAlfaaz?.artist)
        assertTrue(repo.isAlbumCached("MPREb_Alfaaz"))

        // Fetch Titli - MUST NOT return Alfaaz even though both titles are "Single"
        val fetchedTitli = repo.fetchYtAlbum("MPREb_Titli", "Single", mockService)
        assertNotNull(fetchedTitli)
        assertEquals("MPREb_Titli", fetchedTitli?.id)
        assertEquals("Darshan Raval", fetchedTitli?.artist)
        assertNotEquals(fetchedAlfaaz?.id, fetchedTitli?.id)
        assertTrue(repo.isAlbumCached("MPREb_Titli"))
    }

    // =========================================================================
    // 12. VIEWMODEL: navigateToAlbum preserves genuine album details on stack
    // =========================================================================
    @Test
    fun test12_viewmodel_navigateToAlbum_preserves_genuine_album_details() = runTest {
        val repo = MusicRepository(AppDatabase.getInstance(context).musicDao())
        val vm = MainViewModel(repo, com.example.service.PlaybackService(context))

        val inputAlbum = Album(
            id = "MPREb_HamzaAlfaaz",
            title = "Alfaaz",
            artist = "Hamza Malik",
            artistId = "UC_hamza",
            artwork = "https://img.com/alfaaz_hq.jpg",
            releaseYear = "2021",
            type = "Single"
        )

        vm.navigateToAlbum(inputAlbum)
        advanceUntilIdle()

        val topScreen = vm.subScreenStack.value.lastOrNull() as? SubScreenDestination.AlbumScreen
        assertNotNull(topScreen)
        assertEquals("MPREb_HamzaAlfaaz", topScreen?.album?.id)
        assertEquals("Alfaaz", topScreen?.album?.title)
        assertEquals("Hamza Malik", topScreen?.album?.artist)
        assertNotEquals("Unknown Album", topScreen?.album?.title)
        assertNotEquals("Various Artists", topScreen?.album?.artist)
    }
}
