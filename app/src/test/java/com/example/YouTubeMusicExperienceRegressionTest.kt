package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.RepeatMode
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.YtMusicClient
import com.example.service.integrations.ytmusic.YtMusicParser
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtTrack
import com.example.service.lyrics.LyricsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YouTubeMusicExperienceRegressionTest {

    private lateinit var context: Context
    private lateinit var playbackService: PlaybackService

    private val track1 = Track(
        id = "yt_track11111",
        title = "First Song",
        artist = "Artist A",
        artistId = "art_a",
        album = "Album A",
        albumId = "alb_a",
        artwork = "https://example.com/art1.jpg",
        durationSeconds = 180,
        audioUrl = "https://example.com/audio1.mp4"
    )

    private val track2 = Track(
        id = "yt_track22222",
        title = "Second Song",
        artist = "Artist B",
        artistId = "art_b",
        album = "Album B",
        albumId = "alb_b",
        artwork = "https://example.com/art2.jpg",
        durationSeconds = 200,
        audioUrl = "https://example.com/audio2.mp4"
    )

    private val track3 = Track(
        id = "yt_track33333",
        title = "Third Song",
        artist = "Artist C",
        artistId = "art_c",
        album = "Album C",
        albumId = "alb_c",
        artwork = "https://example.com/art3.jpg",
        durationSeconds = 220,
        audioUrl = "https://example.com/audio3.mp4"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        playbackService = PlaybackService(context)
    }

    // ==========================================
    // Phase 1: Next Song Auto-Play Fix
    // ==========================================

    @Test
    fun nextTrack_startsPlayingAutomaticallyWithoutPausing() {
        // Play playlist of 2 tracks
        playbackService.playTrack(track1, listOf(track1, track2))
        val state1 = playbackService.uiState.value
        assertEquals("yt_track11111", state1.currentTrack?.id)
        assertTrue(state1.isPlaying)

        // Press Next button
        playbackService.next()

        val state2 = playbackService.uiState.value
        assertEquals(1, state2.currentTrackIndex)
        assertEquals("yt_track22222", state2.currentTrack?.id)
        assertTrue("Next track must remain in isPlaying=true intent", state2.isPlaying)
    }

    // ==========================================
    // Phase 2: Context-Aware Queue & Operations
    // ==========================================

    @Test
    fun queueOperations_playNextAndAddToQueue_preserveCorrectOrder() {
        playbackService.playTrack(track1, listOf(track1, track2))
        assertEquals(2, playbackService.uiState.value.queue.size)

        // Test playNext: inserts immediately after current track (index 1)
        playbackService.playNext(track3)
        val queueAfterPlayNext = playbackService.uiState.value.queue
        assertEquals(3, queueAfterPlayNext.size)
        assertEquals("yt_track33333", queueAfterPlayNext[1].id)
        assertEquals("yt_track22222", queueAfterPlayNext[2].id)

        // Test addToQueue: appends to the end
        val track4 = track1.copy(id = "yt_track44444", title = "Fourth Song")
        playbackService.addToQueue(track4)
        val queueAfterAdd = playbackService.uiState.value.queue
        assertEquals(4, queueAfterAdd.size)
        assertEquals("yt_track44444", queueAfterAdd[3].id)

        // Test removeFromQueue
        playbackService.removeFromQueue(1) // Remove track3
        val queueAfterRemove = playbackService.uiState.value.queue
        assertEquals(3, queueAfterRemove.size)
        assertEquals("yt_track22222", queueAfterRemove[1].id)

        // Test reorderQueue
        playbackService.reorderQueue(2, 1) // Move track4 from index 2 to 1
        val queueAfterReorder = playbackService.uiState.value.queue
        assertEquals("yt_track44444", queueAfterReorder[1].id)
        assertEquals("yt_track22222", queueAfterReorder[2].id)

        // Test clearQueue: keeps only currently playing song
        playbackService.clearQueue()
        val clearedQueue = playbackService.uiState.value.queue
        assertEquals(1, clearedQueue.size)
        assertEquals(track1.id, clearedQueue[0].id)
    }

    @Test
    fun shuffle_preservesCurrentTrackAtTop_andRestoresOriginalOrderOnToggleOff() {
        val originalList = listOf(track1, track2, track3)
        playbackService.playTrack(track1, originalList)

        // Turn shuffle ON
        playbackService.toggleShuffle()
        val shuffledState = playbackService.uiState.value
        assertTrue(shuffledState.isShuffle)
        assertEquals(3, shuffledState.queue.size)
        assertEquals("Current song must remain at index 0 when shuffled", track1.id, shuffledState.queue[0].id)

        // Turn shuffle OFF
        playbackService.toggleShuffle()
        val unShuffledState = playbackService.uiState.value
        assertFalse(unShuffledState.isShuffle)
        assertEquals(originalList.map { it.id }, unShuffledState.queue.map { it.id })
    }

    @Test
    fun repeatModes_cycleCorrectlyThroughOffAllOne() {
        assertEquals(RepeatMode.OFF, playbackService.uiState.value.repeatMode)

        playbackService.toggleRepeat()
        assertEquals(RepeatMode.ALL, playbackService.uiState.value.repeatMode)

        playbackService.toggleRepeat()
        assertEquals(RepeatMode.ONE, playbackService.uiState.value.repeatMode)

        playbackService.toggleRepeat()
        assertEquals(RepeatMode.OFF, playbackService.uiState.value.repeatMode)
    }

    @Test
    fun autoplay_toggleWorksAsExpected() {
        assertTrue("Autoplay must default to true", playbackService.uiState.value.isAutoplay)
        playbackService.toggleAutoplay()
        assertFalse(playbackService.uiState.value.isAutoplay)
        playbackService.toggleAutoplay()
        assertTrue(playbackService.uiState.value.isAutoplay)
    }

    // ==========================================
    // Phase 6: Synchronized Lyrics
    // ==========================================

    @Test
    fun parseLrc_supportsMultiTimestampsAndMillisecondPrecision() {
        val lrcWithMultipleTags = """
            [00:04.50][00:15.500]Repeated lyrical hook line
            [01:02:80]Colon separator line
        """.trimIndent()

        val parsed = LyricsRepository.parseLrc(lrcWithMultipleTags)
        assertEquals(3, parsed.size)

        assertEquals(4500L, parsed[0].timeMs)
        assertEquals("Repeated lyrical hook line", parsed[0].text)

        assertEquals(15500L, parsed[1].timeMs)
        assertEquals("Repeated lyrical hook line", parsed[1].text)

        assertEquals(62800L, parsed[2].timeMs)
        assertEquals("Colon separator line", parsed[2].text)
    }

    @Test
    fun parsePlainLyrics_returnsNegativeTimestampsToAvoidFakeSync() {
        val plain = """
            First verse of poetry
            Second verse of melody
        """.trimIndent()

        val parsed = LyricsRepository.parsePlainLyrics(plain)
        assertEquals(2, parsed.size)
        assertEquals(-1L, parsed[0].timeMs)
        assertEquals(-1L, parsed[1].timeMs)
        assertEquals("First verse of poetry", parsed[0].text)
    }

    // ==========================================
    // Phase 7: Together Listening Compatibility
    // ==========================================

    @Test
    fun togetherListening_inMemoryRealtimeService_managesRoomsAndQueue() = runBlocking {
        val service = com.example.service.integrations.InMemoryRealtimeService()
        val roomResult = service.createRoom("test_room", true, "Host Device")
        assertTrue(roomResult.isSuccess)

        val room = roomResult.getOrThrow()
        assertNotNull(room.code)
        assertTrue(service.isHost())

        // Host changes track
        val changeResult = service.changeTrack(track1, "op_1")
        assertTrue(changeResult.isSuccess)
        val updatedRoom = service.activeRoom.first()
        assertEquals(track1.id, updatedRoom?.currentTrack?.id)

        // Leave room
        service.leaveRoom()
        val leftRoom = service.activeRoom.first()
        assertEquals(null, leftRoom)
    }

    // ==========================================
    // Phase 8: YouTube Music Data & Content Integration
    // ==========================================

    @Test
    fun parseHomeResponse_extractsMixedCarousels_tracksAlbumsArtistsPlaylists() {
        val homeJson = JSONObject("""
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
                                  "title": { "runs": [{ "text": "Quick picks & Discovery" }] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {
                                      "runs": [{
                                        "text": "Midnight City",
                                        "navigationEndpoint": {
                                          "watchEndpoint": { "videoId": "vid_quick_1" }
                                        }
                                      }]
                                    },
                                    "subtitle": {
                                      "runs": [{ "text": "M83 • Hurry Up, We're Dreaming" }]
                                    },
                                    "thumbnailRenderer": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": { "thumbnails": [{ "url": "https://img/m83.jpg" }] }
                                      }
                                    },
                                    "navigationEndpoint": {
                                      "watchEndpoint": { "videoId": "vid_quick_1" }
                                    }
                                  }
                                },
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {
                                      "runs": [{
                                        "text": "Random Access Memories",
                                        "navigationEndpoint": {
                                          "browseEndpoint": { "browseId": "MPREb_ram_album" }
                                        }
                                      }]
                                    },
                                    "subtitle": {
                                      "runs": [{ "text": "Album • Daft Punk • 2013" }]
                                    },
                                    "thumbnailRenderer": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": { "thumbnails": [{ "url": "https://img/ram.jpg" }] }
                                      }
                                    },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_ram_album" }
                                    }
                                  }
                                },
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {
                                      "runs": [{
                                        "text": "The Weeknd",
                                        "navigationEndpoint": {
                                          "browseEndpoint": { "browseId": "UCtheweeknd_id" }
                                        }
                                      }]
                                    },
                                    "subtitle": {
                                      "runs": [{ "text": "Artist • 35M subscribers" }]
                                    },
                                    "thumbnailRenderer": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": { "thumbnails": [{ "url": "https://img/weeknd.jpg" }] }
                                      }
                                    },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "UCtheweeknd_id" }
                                    }
                                  }
                                },
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {
                                      "runs": [{
                                        "text": "Today's Hits",
                                        "navigationEndpoint": {
                                          "browseEndpoint": { "browseId": "VLPL_todays_hits" }
                                        }
                                      }]
                                    },
                                    "subtitle": {
                                      "runs": [{ "text": "Playlist • YouTube Music" }]
                                    },
                                    "thumbnailRenderer": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": { "thumbnails": [{ "url": "https://img/hits.jpg" }] }
                                      }
                                    },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "VLPL_todays_hits" }
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
        """.trimIndent())

        val shelves = YtMusicParser.parseHomeResponse(homeJson)
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Quick picks & Discovery", shelf.title)

        // Verify tracks
        assertEquals(1, shelf.items.size)
        assertEquals("yt_vid_quick_1", shelf.items[0].id)
        assertEquals("Midnight City", shelf.items[0].title)

        // Verify albums
        assertEquals(1, shelf.albums.size)
        assertEquals("MPREb_ram_album", shelf.albums[0].id)
        assertEquals("Random Access Memories", shelf.albums[0].title)

        // Verify artists
        assertEquals(1, shelf.artists.size)
        assertEquals("UCtheweeknd_id", shelf.artists[0].id)
        assertEquals("The Weeknd", shelf.artists[0].name)

        // Verify playlists
        assertEquals(1, shelf.playlists.size)
        assertEquals("VLPL_todays_hits", shelf.playlists[0].id)
        assertEquals("Today's Hits", shelf.playlists[0].title)
    }

    @Test
    fun parseArtistResponse_extractsCompleteDiscographyAndSections() {
        val artistJson = JSONObject("""
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Coldplay" }] },
              "description": { "runs": [{ "text": "British rock band formed in London in 1997." }] },
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": { "thumbnails": [{ "url": "https://img/coldplay.jpg" }] }
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
                                    "playlistItemData": { "videoId": "cp_yellow_1" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Yellow" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Coldplay • Parachutes" }] }
                                        }
                                      }
                                    ],
                                    "fixedColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "4:29" }] }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          },
                          {
                            "musicCarouselShelfRenderer": {
                              "header": {
                                "musicCarouselShelfBasicHeaderRenderer": {
                                  "title": { "runs": [{ "text": "Albums" }] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "A Rush of Blood to the Head" }] },
                                    "subtitle": { "runs": [{ "text": "Album • 2002" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_arobth" }
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
                                  "title": { "runs": [{ "text": "Singles & EPs" }] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "Midnight" }] },
                                    "subtitle": { "runs": [{ "text": "Single • 2014" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_midnight" }
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
                                  "title": { "runs": [{ "text": "Fans might also like" }] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "Keane" }] },
                                    "subtitle": { "runs": [{ "text": "Artist • 2M subscribers" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "UCkeane_id" }
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
        """.trimIndent())

        val artist = YtMusicParser.parseArtistResponse(artistJson, "UCcoldplay_id")
        assertEquals("Coldplay", artist.name)
        assertEquals("British rock band formed in London in 1997.", artist.bio)
        assertEquals(1, artist.topTracks.size)
        assertEquals("yt_cp_yellow_1", artist.topTracks[0].id)
        assertEquals("Yellow", artist.topTracks[0].title)

        assertEquals(1, artist.albums.size)
        assertEquals("MPREb_arobth", artist.albums[0].id)
        assertEquals("A Rush of Blood to the Head", artist.albums[0].title)

        assertEquals(1, artist.singles.size)
        assertEquals("MPREb_midnight", artist.singles[0].id)
        assertEquals("Midnight", artist.singles[0].title)
        assertEquals("Single", artist.singles[0].type)

        assertEquals(1, artist.similarArtists.size)
        assertEquals("UCkeane_id", artist.similarArtists[0].id)
        assertEquals("Keane", artist.similarArtists[0].name)
    }

    @Test
    fun parseAlbumResponse_extractsMetadataTracklistAndReleaseType() {
        val albumJson = JSONObject("""
        {
          "header": {
            "musicDetailHeaderRenderer": {
              "title": { "runs": [{ "text": "Viva La Vida" }] },
              "subtitle": {
                "runs": [
                  { "text": "Album" },
                  { "text": " • " },
                  { "text": "Coldplay" },
                  { "text": " • " },
                  { "text": "2008" }
                ]
              },
              "thumbnail": {
                "croppedSquareThumbnailRenderer": {
                  "thumbnail": { "thumbnails": [{ "url": "https://img/viva.jpg" }] }
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
                                    "playlistItemData": { "videoId": "viva_track1" },
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
                                    ],
                                    "fixedColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "2:29" }] }
                                        }
                                      }
                                    ]
                                  }
                                },
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "viva_track2" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Viva La Vida" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Coldplay" }] }
                                        }
                                      }
                                    ],
                                    "fixedColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "4:01" }] }
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

        val album = YtMusicParser.parseAlbumResponse(albumJson, "MPREb_viva")
        assertEquals("Viva La Vida", album.title)
        assertEquals("Album", album.type)
        assertEquals("2008", album.year)
        assertEquals(2, album.tracks.size)
        assertEquals(2, album.trackCount)
        assertEquals("yt_viva_track1", album.tracks[0].id)
        assertEquals("yt_viva_track2", album.tracks[1].id)
        assertNotNull(album.durationText)
    }

    @Test
    fun parseLibraryAlbumsAndArtists_extractsLibraryEntitiesCorrectly() {
        val libraryAlbumsJson = JSONObject("""
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
                            "gridRenderer": {
                              "items": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "Ghost Stories" }] },
                                    "subtitle": { "runs": [{ "text": "Album • Coldplay • 2014" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_ghost_stories" }
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
        """.trimIndent())

        val libraryAlbums = YtMusicParser.parseLibraryAlbums(libraryAlbumsJson)
        assertEquals(1, libraryAlbums.size)
        assertEquals("MPREb_ghost_stories", libraryAlbums[0].id)
        assertEquals("Ghost Stories", libraryAlbums[0].title)

        val libraryArtistsJson = JSONObject("""
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
                            "musicShelfRenderer": {
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "Radiohead" }] },
                                    "subtitle": { "runs": [{ "text": "Artist • 3M subscribers" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "UCradiohead_id" }
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
        """.trimIndent())

        val libraryArtists = YtMusicParser.parseLibraryArtists(libraryArtistsJson)
        assertEquals(1, libraryArtists.size)
        assertEquals("UCradiohead_id", libraryArtists[0].id)
        assertEquals("Radiohead", libraryArtists[0].name)
    }
}
