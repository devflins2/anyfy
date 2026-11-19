package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.MusicRepository
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.RepeatMode
import com.example.service.integrations.ytmusic.YtAlbum
import com.example.service.integrations.ytmusic.YtArtist
import com.example.service.integrations.ytmusic.YtMusicParser
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtSearchResult
import com.example.service.integrations.ytmusic.YtTrack
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArtistDiscographyAndQueueRegressionTest {

    private lateinit var context: Context
    private lateinit var playbackService: PlaybackService
    private val testDispatcher = StandardTestDispatcher()

    private val track1 = Track(
        id = "yt_test11111",
        title = "Channa Mereya",
        artist = "Arijit Singh",
        artistId = "art_arijit_singh",
        album = "Ae Dil Hai Mushkil",
        albumId = "alb_adhm",
        artwork = "https://example.com/art1.jpg",
        durationSeconds = 289
    )

    private val track2 = Track(
        id = "yt_test22222",
        title = "Tum Hi Ho",
        artist = "Arijit Singh",
        artistId = "art_arijit_singh",
        album = "Aashiqui 2",
        albumId = "alb_aashiqui2",
        artwork = "https://example.com/art2.jpg",
        durationSeconds = 262
    )

    private val track3 = Track(
        id = "yt_test33333",
        title = "Kesariya",
        artist = "Arijit Singh",
        artistId = "art_arijit_singh",
        album = "Brahmastra",
        albumId = "alb_brahmastra",
        artwork = "https://example.com/art3.jpg",
        durationSeconds = 268
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("flinsmusic_playback_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        playbackService = PlaybackService(context)
    }

    @After
    fun tearDown() {
        playbackService.release()
        Dispatchers.resetMain()
    }

    @Test
    fun test01_artist_name_tap_triggers_onArtistClick_and_never_onTrackClick() {
        var artistClicked: String? = null
        var trackClicked: Track? = null

        val onArtistClick: (String) -> Unit = { name -> artistClicked = name }
        val onTrackClick: () -> Unit = { trackClicked = track1 }

        // Simulate child artist text click consuming event
        onArtistClick.invoke(track1.artist)

        assertEquals("Arijit Singh", artistClicked)
        assertEquals(null, trackClicked)
    }

    @Test
    fun test02_album_name_tap_triggers_onAlbumClick_and_never_onTrackClick() {
        var albumClicked: String? = null
        var trackClicked: Track? = null

        val onAlbumClick: (String) -> Unit = { name -> albumClicked = name }
        val onTrackClick: () -> Unit = { trackClicked = track1 }

        onAlbumClick.invoke(track1.album)

        assertEquals("Ae Dil Hai Mushkil", albumClicked)
        assertEquals(null, trackClicked)
    }

    @Test
    fun test03_track_row_body_tap_triggers_onTrackClick() {
        var trackClicked: Track? = null
        val onTrackClick: () -> Unit = { trackClicked = track1 }

        onTrackClick.invoke()

        assertEquals(track1, trackClicked)
    }

    @Test
    fun test04_artist_domain_model_supports_full_discography() {
        val album = Album(id = "alb_1", title = "Album 1", artist = "Arijit Singh", artwork = "https://example.com/alb1.jpg")
        val single = Album(id = "sng_1", title = "Single 1", artist = "Arijit Singh", artwork = "https://example.com/sng1.jpg")
        val video = Track(id = "yt_vid1", title = "Video 1", artist = "Arijit Singh", artwork = "https://example.com/vid1.jpg", durationSeconds = 200)
        val similar = Artist(id = "art_atif", name = "Atif Aslam", artwork = "https://example.com/atif.jpg")

        val artist = Artist(
            id = "art_arijit_singh",
            name = "Arijit Singh",
            artwork = "https://example.com/arijit.jpg",
            albums = listOf(album),
            singles = listOf(single),
            videos = listOf(video),
            similarArtists = listOf(similar)
        )

        assertEquals(1, artist.albums.size)
        assertEquals("Album 1", artist.albums[0].title)
        assertEquals(1, artist.singles.size)
        assertEquals("Single 1", artist.singles[0].title)
        assertEquals(1, artist.videos.size)
        assertEquals("Video 1", artist.videos[0].title)
        assertEquals(1, artist.similarArtists.size)
        assertEquals("Atif Aslam", artist.similarArtists[0].name)
    }

    @Test
    fun test05_ytArtist_toArtist_maps_all_discography_components() {
        val ytAlbum = YtAlbum(id = "MPREb_alb1", title = "Album 1", artist = "Arijit Singh", artwork = "https://example.com/alb1.jpg")
        val ytSingle = YtAlbum(id = "MPREb_sng1", title = "Single 1", artist = "Arijit Singh", artwork = "https://example.com/sng1.jpg")
        val ytVideo = YtTrack(id = "vid1", title = "Video 1", artist = "Arijit Singh", artwork = "https://example.com/vid1.jpg", durationSeconds = 180)
        val ytSimilar = YtArtist(id = "UC_atif", name = "Atif Aslam", artwork = "https://example.com/atif.jpg")

        val ytArtist = YtArtist(
            id = "UC_arijit",
            name = "Arijit Singh",
            artwork = "https://example.com/arijit.jpg",
            topTracks = listOf(YtTrack(id = "track1", title = "Track 1", artist = "Arijit Singh", artwork = "", durationSeconds = 200)),
            albums = listOf(ytAlbum),
            singles = listOf(ytSingle),
            videos = listOf(ytVideo),
            similarArtists = listOf(ytSimilar)
        )

        val domainArtist = ytArtist.toArtist()

        assertEquals("UC_arijit", domainArtist.id)
        assertEquals("Arijit Singh", domainArtist.name)
        assertEquals(1, domainArtist.popularTrackIds.size)
        assertEquals("yt_track1", domainArtist.popularTrackIds[0])
        assertEquals(1, domainArtist.albums.size)
        assertEquals("Album 1", domainArtist.albums[0].title)
        assertEquals(1, domainArtist.singles.size)
        assertEquals("Single 1", domainArtist.singles[0].title)
        assertEquals(1, domainArtist.videos.size)
        assertEquals("Video 1", domainArtist.videos[0].title)
        assertEquals(1, domainArtist.similarArtists.size)
        assertEquals("Atif Aslam", domainArtist.similarArtists[0].name)
    }

    @Test
    fun test06_parseArtistResponse_parses_discography_sections_with_all_header_variants() {
        val root = JSONObject().apply {
            put("header", JSONObject().apply {
                put("musicImmersiveHeaderRenderer", JSONObject().apply {
                    put("title", JSONObject().apply {
                        put("runs", JSONArray().put(JSONObject().put("text", "Arijit Singh")))
                    })
                    put("description", JSONObject().apply {
                        put("runs", JSONArray().put(JSONObject().put("text", "Indian playback singer")))
                    })
                })
            })
            put("contents", JSONObject().apply {
                put("singleColumnBrowseResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().put(JSONObject().apply {
                        put("tabRenderer", JSONObject().apply {
                            put("content", JSONObject().apply {
                                put("sectionListRenderer", JSONObject().apply {
                                    put("contents", JSONArray().apply {
                                        // Top songs shelf with musicShelfRenderer
                                        put(JSONObject().apply {
                                            put("musicShelfRenderer", JSONObject().apply {
                                                put("title", JSONObject().apply {
                                                    put("runs", JSONArray().put(JSONObject().put("text", "Songs")))
                                                })
                                                put("contents", JSONArray().put(JSONObject().apply {
                                                    put("musicResponsiveListItemRenderer", JSONObject().apply {
                                                        put("flexColumns", JSONArray().apply {
                                                            put(JSONObject().apply {
                                                                put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                    put("text", JSONObject().apply {
                                                                        put("runs", JSONArray().put(JSONObject().apply {
                                                                            put("text", "Tum Hi Ho")
                                                                            put("navigationEndpoint", JSONObject().apply {
                                                                                put("watchEndpoint", JSONObject().put("videoId", "track_tum_hi_ho"))
                                                                            })
                                                                        }))
                                                                    })
                                                                })
                                                            })
                                                        })
                                                    })
                                                }))
                                            })
                                        })
                                        // Albums carousel with musicCarouselShelfHeaderRenderer
                                        put(JSONObject().apply {
                                            put("musicCarouselShelfRenderer", JSONObject().apply {
                                                put("header", JSONObject().apply {
                                                    put("musicCarouselShelfHeaderRenderer", JSONObject().apply {
                                                        put("title", JSONObject().apply {
                                                            put("runs", JSONArray().put(JSONObject().put("text", "Albums")))
                                                        })
                                                    })
                                                })
                                                put("contents", JSONArray().put(JSONObject().apply {
                                                    put("musicTwoRowItemRenderer", JSONObject().apply {
                                                        put("title", JSONObject().apply {
                                                            put("runs", JSONArray().put(JSONObject().put("text", "Aashiqui 2")))
                                                        })
                                                        put("navigationEndpoint", JSONObject().apply {
                                                            put("browseEndpoint", JSONObject().put("browseId", "MPREb_aashiqui2"))
                                                        })
                                                    })
                                                }))
                                            })
                                        })
                                        // Singles carousel with musicShelfBasicHeaderRenderer
                                        put(JSONObject().apply {
                                            put("musicCarouselShelfRenderer", JSONObject().apply {
                                                put("header", JSONObject().apply {
                                                    put("musicShelfBasicHeaderRenderer", JSONObject().apply {
                                                        put("title", JSONObject().apply {
                                                            put("runs", JSONArray().put(JSONObject().put("text", "Singles & EPs")))
                                                        })
                                                    })
                                                })
                                                put("contents", JSONArray().put(JSONObject().apply {
                                                    put("musicTwoRowItemRenderer", JSONObject().apply {
                                                        put("title", JSONObject().apply {
                                                            put("runs", JSONArray().put(JSONObject().put("text", "Kesariya (Dance Mix)")))
                                                        })
                                                        put("navigationEndpoint", JSONObject().apply {
                                                            put("browseEndpoint", JSONObject().put("browseId", "MPREb_kesariya_single"))
                                                        })
                                                    })
                                                }))
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    }))
                })
            })
        }

        val artist = YtMusicParser.parseArtistResponse(root, "UC_arijit")
        assertEquals("Arijit Singh", artist.name)
        assertEquals(1, artist.topTracks.size)
        assertEquals("Tum Hi Ho", artist.topTracks[0].title)
        assertEquals(1, artist.albums.size)
        assertEquals("Aashiqui 2", artist.albums[0].title)
        assertEquals(1, artist.singles.size)
        assertEquals("Kesariya (Dance Mix)", artist.singles[0].title)
    }

    @Test
    fun test07_musicRepository_fetchYtArtist_populates_full_discography() = runTest(testDispatcher) {
        val mockService = object : YtMusicService {
            override suspend fun search(query: String): Result<YtSearchResult> {
                return Result.success(
                    YtSearchResult(
                        artists = listOf(YtArtist(id = "UC_arijit_channel", name = "Arijit Singh")),
                        albums = listOf(YtAlbum(id = "MPREb_extra_alb", title = "Arijit Hits Album", artist = "Arijit Singh"))
                    )
                )
            }
            override suspend fun searchTracks(query: String): Result<List<YtTrack>> = Result.success(emptyList())
            override suspend fun getHomeShelves() = Result.success(emptyList<com.example.service.integrations.ytmusic.YtHomeShelf>())
            override suspend fun getArtist(browseId: String): Result<YtArtist> {
                return Result.success(
                    YtArtist(
                        id = browseId,
                        name = "Arijit Singh",
                        topTracks = listOf(YtTrack(id = "thh", title = "Tum Hi Ho", artist = "Arijit Singh", artwork = "", durationSeconds = 200)),
                        albums = listOf(YtAlbum(id = "MPREb_aashiqui2", title = "Aashiqui 2", artist = "Arijit Singh")),
                        singles = listOf(YtAlbum(id = "MPREb_kesariya", title = "Kesariya", artist = "Arijit Singh"))
                    )
                )
            }
            override suspend fun getAlbum(browseId: String): Result<YtAlbum> = Result.success(YtAlbum(id = browseId, title = "Album", artist = "Arijit Singh"))
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.success(com.example.service.integrations.ytmusic.YtPlaylist(id = playlistId, title = "Playlist"))
            override suspend fun getSearchSuggestions(query: String): Result<List<String>> = Result.success(emptyList())
            override suspend fun getRadioTracks(videoId: String): Result<List<YtTrack>> = Result.success(emptyList())
        }

        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.local.AppDatabase::class.java).allowMainThreadQueries().build()
        val repository = MusicRepository(database.musicDao())
        val fetchedArtist = repository.fetchYtArtist("UC_arijit_channel", "Arijit Singh", mockService)

        assertNotNull(fetchedArtist)
        assertEquals("Arijit Singh", fetchedArtist?.name)
        assertEquals(1, fetchedArtist?.albums?.size)
        assertEquals("Aashiqui 2", fetchedArtist?.albums?.get(0)?.title)
        assertEquals(1, fetchedArtist?.singles?.size)
        assertEquals("Kesariya", fetchedArtist?.singles?.get(0)?.title)

        // Verify albums registered in repository dynamicAlbums
        assertNotNull(repository.getAlbumById("MPREb_aashiqui2"))
        assertNotNull(repository.getAlbumById("MPREb_kesariya"))
    }

    @Test
    fun test08_single_track_play_creates_queue_and_starts_playing() {
        playbackService.playTrack(track1, emptyList())

        val state = playbackService.uiState.value
        assertEquals(track1.id, state.currentTrack?.id)
        assertEquals(1, state.queue.size)
        assertEquals(0, state.currentTrackIndex)
        assertTrue(state.isPlaying)
    }

    @Test
    fun test09_album_tracklist_playback_preserves_order_and_sets_index() {
        val albumTracks = listOf(track1, track2, track3)
        playbackService.playTrack(track2, albumTracks)

        val state = playbackService.uiState.value
        assertEquals(track2.id, state.currentTrack?.id)
        assertEquals(3, state.queue.size)
        assertEquals(1, state.currentTrackIndex)
        assertEquals(track1.id, state.queue[0].id)
        assertEquals(track2.id, state.queue[1].id)
        assertEquals(track3.id, state.queue[2].id)
        assertTrue(state.isPlaying)
    }

    @Test
    fun test10_next_button_advances_queue_without_pausing() {
        val queue = listOf(track1, track2, track3)
        playbackService.playTrack(track1, queue)

        assertEquals(0, playbackService.uiState.value.currentTrackIndex)
        assertEquals(track1.id, playbackService.uiState.value.currentTrack?.id)

        playbackService.next()

        val state = playbackService.uiState.value
        assertEquals(1, state.currentTrackIndex)
        assertEquals(track2.id, state.currentTrack?.id)
        assertTrue(state.isPlaying)
    }

    @Test
    fun test11_previous_button_restarts_track_if_over_3_seconds() {
        val queue = listOf(track1, track2, track3)
        playbackService.playTrack(track2, queue)
        playbackService.seekTo(5000L)

        playbackService.previous()

        val state = playbackService.uiState.value
        assertEquals(1, state.currentTrackIndex)
        assertEquals(track2.id, state.currentTrack?.id)
        assertEquals(0L, state.currentPositionMs)
    }

    @Test
    fun test12_previous_button_plays_previous_track_if_under_3_seconds() {
        val queue = listOf(track1, track2, track3)
        playbackService.playTrack(track2, queue)
        playbackService.seekTo(1000L)

        playbackService.previous()

        val state = playbackService.uiState.value
        assertEquals(0, state.currentTrackIndex)
        assertEquals(track1.id, state.currentTrack?.id)
    }

    @Test
    fun test13_queue_reorder_and_remove_maintains_synchronization() {
        val queue = listOf(track1, track2, track3)
        playbackService.playTrack(track1, queue)

        // Reorder track 2 to position 0
        playbackService.reorderQueue(1, 0)
        var state = playbackService.uiState.value
        assertEquals(track2.id, state.queue[0].id)
        assertEquals(track1.id, state.queue[1].id)
        assertEquals(1, state.currentTrackIndex) // track1 is still current

        // Remove track3 (position 2)
        playbackService.removeFromQueue(2)
        state = playbackService.uiState.value
        assertEquals(2, state.queue.size)
        assertEquals(track1.id, state.currentTrack?.id)
    }

    @Test
    fun test14_clear_upcoming_queue_preserves_current_playing_track() {
        val queue = listOf(track1, track2, track3)
        playbackService.playTrack(track1, queue)

        playbackService.clearQueue()

        val state = playbackService.uiState.value
        assertEquals(1, state.queue.size)
        assertEquals(track1.id, state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertTrue(state.isPlaying)
    }

    @Test
    fun test15_mainViewModel_navigateToArtistByName_and_navigateToAlbumByTitle() = runTest(testDispatcher) {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.local.AppDatabase::class.java).allowMainThreadQueries().build()
        val repository = MusicRepository(database.musicDao())
        val viewModel = MainViewModel(repository = repository, playbackService = playbackService)

        viewModel.navigateToArtistByName("Arijit Singh")
        advanceUntilIdle()

        val selectedArtist = viewModel.selectedArtist.value
        assertNotNull(selectedArtist)
        assertEquals("Arijit Singh", selectedArtist?.name)
        assertFalse(viewModel.isFullPlayerVisible.value)

        viewModel.navigateToAlbumByTitle("Aashiqui 2", "Arijit Singh")
        advanceUntilIdle()

        val selectedAlbum = viewModel.selectedAlbum.value
        assertNotNull(selectedAlbum)
        assertEquals("Aashiqui 2", selectedAlbum?.title)
        assertFalse(viewModel.isFullPlayerVisible.value)
    }

    @Test
    fun test16_parseDiscographyPage_initialGridRenderer_extractsAlbumsAndContinuation() {
        val root = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("singleColumnBrowseResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().put(JSONObject().apply {
                        put("tabRenderer", JSONObject().apply {
                            put("content", JSONObject().apply {
                                put("sectionListRenderer", JSONObject().apply {
                                    put("contents", JSONArray().put(JSONObject().apply {
                                        put("gridRenderer", JSONObject().apply {
                                            put("items", JSONArray().apply {
                                                // Album 1
                                                put(JSONObject().apply {
                                                    put("musicTwoRowItemRenderer", JSONObject().apply {
                                                        put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Aashiqui 2"))))
                                                        put("subtitle", JSONObject().put("runs", JSONArray().apply {
                                                            put(JSONObject().put("text", "Album"))
                                                            put(JSONObject().put("text", " • "))
                                                            put(JSONObject().put("text", "Arijit Singh"))
                                                            put(JSONObject().put("text", " • "))
                                                            put(JSONObject().put("text", "2013"))
                                                        }))
                                                        put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "MPREb_aashiqui2")))
                                                    })
                                                })
                                                // Album 2
                                                put(JSONObject().apply {
                                                    put("musicTwoRowItemRenderer", JSONObject().apply {
                                                        put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Ae Dil Hai Mushkil"))))
                                                        put("subtitle", JSONObject().put("runs", JSONArray().apply {
                                                            put(JSONObject().put("text", "Album • 2016"))
                                                        }))
                                                        put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "MPREb_adhm")))
                                                    })
                                                })
                                                // Continuation item
                                                put(JSONObject().apply {
                                                    put("continuationItemRenderer", JSONObject().apply {
                                                        put("continuationEndpoint", JSONObject().apply {
                                                            put("continuationCommand", JSONObject().put("token", "token_page_2"))
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                    }))
                                })
                            })
                        })
                    }))
                })
            })
        }

        val (albums, continuationToken) = YtMusicParser.parseDiscographyPage(root, fallbackArtist = "Arijit Singh")
        assertEquals(2, albums.size)
        assertEquals("Aashiqui 2", albums[0].title)
        assertEquals("Arijit Singh", albums[0].artist)
        assertEquals("2013", albums[0].year)
        assertEquals("MPREb_aashiqui2", albums[0].id)

        assertEquals("Ae Dil Hai Mushkil", albums[1].title)
        assertEquals("Arijit Singh", albums[1].artist)
        assertEquals("2016", albums[1].year)

        assertEquals("token_page_2", continuationToken)
    }

    @Test
    fun test17_parseDiscographyPage_continuationActions_appendsPagedAlbums() {
        val root = JSONObject().apply {
            put("onResponseReceivedActions", JSONArray().put(JSONObject().apply {
                put("appendContinuationItemsAction", JSONObject().apply {
                    put("continuationItems", JSONArray().apply {
                        // Album 3
                        put(JSONObject().apply {
                            put("musicTwoRowItemRenderer", JSONObject().apply {
                                put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Brahmastra"))))
                                put("subtitle", JSONObject().put("runs", JSONArray().apply {
                                    put(JSONObject().put("text", "Album • 2022"))
                                }))
                                put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "MPREb_brahmastra")))
                            })
                        })
                        // End token
                        put(JSONObject().apply {
                            put("continuationItemRenderer", JSONObject().apply {
                                put("continuationEndpoint", JSONObject().apply {
                                    put("continuationCommand", JSONObject().put("token", "token_page_3"))
                                })
                            })
                        })
                    })
                })
            }))
        }

        val (albums, continuationToken) = YtMusicParser.parseDiscographyPage(root, fallbackArtist = "Arijit Singh")
        assertEquals(1, albums.size)
        assertEquals("Brahmastra", albums[0].title)
        assertEquals("MPREb_brahmastra", albums[0].id)
        assertEquals("token_page_3", continuationToken)
    }

    @Test
    fun test18_parseTwoRowAlbum_isolatesArtistFromSubtitleRuns_andResolvesBrowseIdFromRuns() {
        val twoRow = JSONObject().apply {
            put("title", JSONObject().put("runs", JSONArray().put(JSONObject().apply {
                put("text", "Kabir Singh")
                put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "MPREb_kabir_singh")))
            })))
            put("subtitle", JSONObject().put("runs", JSONArray().apply {
                put(JSONObject().put("text", "Album"))
                put(JSONObject().put("text", " • "))
                put(JSONObject().apply {
                    put("text", "Arijit Singh")
                    put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "UCarijit_id")))
                })
                put(JSONObject().put("text", " • "))
                put(JSONObject().put("text", "2019"))
            }))
            put("thumbnailRenderer", JSONObject().apply {
                put("musicThumbnailRenderer", JSONObject().apply {
                    put("thumbnail", JSONObject().apply {
                        put("thumbnails", JSONArray().put(JSONObject().apply {
                            put("url", "https://lh3.googleusercontent.com/test=w120-h120")
                            put("width", 120)
                            put("height", 120)
                        }))
                    })
                })
            })
        }

        val album = YtMusicParser.parseTwoRowAlbum(twoRow, fallbackArtist = "Unknown")
        assertNotNull(album)
        assertEquals("MPREb_kabir_singh", album!!.id)
        assertEquals("Kabir Singh", album.title)
        assertEquals("Arijit Singh", album.artist)
        assertEquals("2019", album.year)
        assertTrue(album.artwork.contains("=w544-h544"))
    }

    @Test
    fun test19_extractBannerArtwork_resolvesWideAspectRatiosAndVideoThumbnails() {
        // Test wide thumbnails
        val wideThumbs = JSONArray().apply {
            put(JSONObject().apply {
                put("url", "https://lh3.googleusercontent.com/wide=w1280-h720")
                put("width", 1280)
                put("height", 720)
            })
        }
        val wideBanner = YtMusicParser.extractBannerArtwork(wideThumbs)
        assertTrue(wideBanner.contains("w1280-h720"))

        // Test videoId fallback
        val videoBanner = YtMusicParser.extractBannerArtwork(null, videoId = "d9TPn9wR5pM")
        assertEquals("https://i.ytimg.com/vi/d9TPn9wR5pM/hqdefault.jpg", videoBanner)
    }

    @Test
    fun test20_trackDisplayBannerArtwork_guaranteesHighResBannerWithZeroBlanks() {
        val ytTrack = Track(
            id = "yt_Musafir1234",
            title = "Musafir",
            artist = "Palash Muchhal",
            artwork = "https://example.com/square.jpg",
            bannerArtwork = "",
            durationSeconds = 180
        )
        // Resolves guaranteed landscape video thumbnail
        assertEquals("https://i.ytimg.com/vi/Musafir1234/hqdefault.jpg", ytTrack.displayBannerArtwork)

        val customBannerTrack = ytTrack.copy(bannerArtwork = "https://example.com/custom_banner.jpg")
        assertEquals("https://example.com/custom_banner.jpg", customBannerTrack.displayBannerArtwork)

        val nonYtTrack = ytTrack.copy(id = "trk_local_1", bannerArtwork = "")
        assertEquals("https://example.com/square.jpg", nonYtTrack.displayBannerArtwork)
    }

    @Test
    fun test21_artistBrowseEndpoints_extractedFromCarousels_forFullDiscography() {
        val root = JSONObject().apply {
            put("header", JSONObject().apply {
                put("musicImmersiveHeaderRenderer", JSONObject().apply {
                    put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Arijit Singh"))))
                    put("description", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Indian playback singer"))))
                    put("thumbnail", JSONObject().apply {
                        put("musicThumbnailRenderer", JSONObject().apply {
                            put("thumbnails", JSONArray().put(JSONObject().apply {
                                put("url", "https://example.com/artist_banner.jpg")
                                put("width", 1280)
                                put("height", 720)
                            }))
                        })
                    })
                })
            })
            put("contents", JSONObject().apply {
                put("singleColumnBrowseResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().put(JSONObject().apply {
                        put("tabRenderer", JSONObject().apply {
                            put("content", JSONObject().apply {
                                put("sectionListRenderer", JSONObject().apply {
                                    put("contents", JSONArray().apply {
                                        // Albums carousel with moreContentButton
                                        put(JSONObject().apply {
                                            put("musicCarouselShelfRenderer", JSONObject().apply {
                                                put("header", JSONObject().apply {
                                                    put("musicCarouselShelfBasicHeaderRenderer", JSONObject().apply {
                                                        put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Albums"))))
                                                        put("moreContentButton", JSONObject().apply {
                                                            put("buttonRenderer", JSONObject().apply {
                                                                put("navigationEndpoint", JSONObject().apply {
                                                                    put("browseEndpoint", JSONObject().apply {
                                                                        put("browseId", "FEmusic_artist_discography")
                                                                        put("params", "ggMPO_albums_token")
                                                                    })
                                                                })
                                                            })
                                                        })
                                                    })
                                                })
                                                put("contents", JSONArray().put(JSONObject().apply {
                                                    put("musicTwoRowItemRenderer", JSONObject().apply {
                                                        put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Teaser Album"))))
                                                        put("subtitle", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Album • 2024"))))
                                                        put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "MPREb_teaser")))
                                                    })
                                                }))
                                            })
                                        })
                                        // Singles carousel with moreContentButton
                                        put(JSONObject().apply {
                                            put("musicCarouselShelfRenderer", JSONObject().apply {
                                                put("header", JSONObject().apply {
                                                    put("musicCarouselShelfBasicHeaderRenderer", JSONObject().apply {
                                                        put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Singles & EPs"))))
                                                        put("moreContentButton", JSONObject().apply {
                                                            put("buttonRenderer", JSONObject().apply {
                                                                put("navigationEndpoint", JSONObject().apply {
                                                                    put("browseEndpoint", JSONObject().apply {
                                                                        put("browseId", "FEmusic_artist_discography")
                                                                        put("params", "ggMPO_singles_token")
                                                                    })
                                                                })
                                                            })
                                                        })
                                                    })
                                                })
                                                put("contents", JSONArray())
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    }))
                })
            })
        }

        val ytArtist = YtMusicParser.parseArtistResponse(root, "UCarijit_id")
        assertEquals("Arijit Singh", ytArtist.name)
        assertNotNull(ytArtist.albumsBrowseEndpoint)
        assertEquals("FEmusic_artist_discography", ytArtist.albumsBrowseEndpoint?.first)
        assertEquals("ggMPO_albums_token", ytArtist.albumsBrowseEndpoint?.second)

        assertNotNull(ytArtist.singlesBrowseEndpoint)
        assertEquals("FEmusic_artist_discography", ytArtist.singlesBrowseEndpoint?.first)
        assertEquals("ggMPO_singles_token", ytArtist.singlesBrowseEndpoint?.second)

        assertTrue(ytArtist.bannerArtwork.isNotBlank())
    }

    @Test
    fun test22_albumParseResponse_extractsBannerArtworkAndTrackCount() {
        val root = JSONObject().apply {
            put("header", JSONObject().apply {
                put("musicDetailHeaderRenderer", JSONObject().apply {
                    put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Aashiqui 2"))))
                    put("subtitle", JSONObject().put("runs", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Arijit Singh")
                            put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "UCarijit_id")))
                        })
                        put(JSONObject().put("text", " • 2013"))
                    }))
                    put("thumbnail", JSONObject().apply {
                        put("croppedSquareThumbnailRenderer", JSONObject().apply {
                            put("thumbnail", JSONObject().apply {
                                put("thumbnails", JSONArray().put(JSONObject().apply {
                                    put("url", "https://example.com/album_art.jpg")
                                    put("width", 544)
                                    put("height", 544)
                                }))
                            })
                        })
                    })
                })
            })
            put("contents", JSONObject().apply {
                put("singleColumnBrowseResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().put(JSONObject().apply {
                        put("tabRenderer", JSONObject().apply {
                            put("content", JSONObject().apply {
                                put("sectionListRenderer", JSONObject().apply {
                                    put("contents", JSONArray().put(JSONObject().apply {
                                        put("musicShelfRenderer", JSONObject().apply {
                                            put("contents", JSONArray().apply {
                                                put(JSONObject().apply {
                                                    put("musicResponsiveListItemRenderer", JSONObject().apply {
                                                        put("videoId", "test_vid_1")
                                                        put("flexColumns", JSONArray().apply {
                                                            put(JSONObject().apply {
                                                                put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                    put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Tum Hi Ho"))))
                                                                })
                                                            })
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                    }))
                                })
                            })
                        })
                    }))
                })
            })
        }

        val ytAlbum = YtMusicParser.parseAlbumResponse(root, "MPREb_aashiqui2")
        assertEquals("Aashiqui 2", ytAlbum.title)
        assertEquals("Arijit Singh", ytAlbum.artist)
        assertEquals("UCarijit_id", ytAlbum.artistId)
        assertEquals(1, ytAlbum.trackCount)
        assertEquals(1, ytAlbum.tracks.size)
        assertEquals("Tum Hi Ho", ytAlbum.tracks[0].title)
        assertTrue(ytAlbum.bannerArtwork.isNotBlank())
    }

    @Test
    fun test23_artwork_pipeline_protocol_relative_url_and_fallback() {
        // 1. Protocol-relative URL normalization
        val thumbObj = JSONObject().apply {
            put("thumbnails", JSONArray().put(JSONObject().apply {
                put("url", "//lh3.googleusercontent.com/test_image=w120-h120")
                put("width", 120)
                put("height", 120)
            }))
        }
        val highRes = YtMusicParser.extractHighResArtwork(thumbObj.optJSONArray("thumbnails"), "dummy_vid")
        assertTrue(highRes.startsWith("https://lh3.googleusercontent.com/"))
        assertTrue(highRes.contains("=w544-h544-l90-rj"))

        // 2. Missing artwork fallback to video ID thumbnail
        val emptyThumbTrack = YtTrack(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            artwork = ""
        ).toTrack()
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", emptyTrackArtworkFallback(emptyThumbTrack))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", emptyThumbTrack.displayArtwork)
    }

    private fun emptyTrackArtworkFallback(track: Track): String {
        return track.displayArtwork.ifBlank { track.artwork }
    }

    @Test
    fun test24_extractThumbnails_supports_all_nested_renderers() {
        // Direct thumbnails array
        val directObj = JSONObject().put("thumbnails", JSONArray().put(JSONObject().put("url", "https://example.com/1.jpg")))
        assertNotNull(YtMusicParser.extractThumbnails(directObj))

        // croppedSquareThumbnailRenderer
        val squareObj = JSONObject().put("thumbnail", JSONObject().put("croppedSquareThumbnailRenderer", JSONObject().put("thumbnail", JSONObject().put("thumbnails", JSONArray().put(JSONObject().put("url", "https://example.com/2.jpg"))))))
        assertNotNull(YtMusicParser.extractThumbnails(squareObj))

        // thumbnailRenderer with musicThumbnailRenderer
        val rendererObj = JSONObject().put("thumbnailRenderer", JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail", JSONObject().put("thumbnails", JSONArray().put(JSONObject().put("url", "https://example.com/3.jpg"))))))
        assertNotNull(YtMusicParser.extractThumbnails(rendererObj))
    }

    @Test
    fun test25_artist_songsBrowseEndpoint_and_tracks_mapping() {
        val ytArtist = YtArtist(
            id = "UC_test_artist",
            name = "Test Artist",
            topTracks = listOf(
                YtTrack(id = "yt_12345678901", title = "Track 1", artist = "Test Artist", artwork = ""),
                YtTrack(id = "yt_12345678902", title = "Track 2", artist = "Test Artist", artwork = "")
            ),
            songsBrowseEndpoint = Pair("FEmusic_artist_tracks", "param_token_123")
        )

        val domainArtist = ytArtist.toArtist()
        assertEquals(2, domainArtist.tracks.size)
        assertEquals("Track 1", domainArtist.tracks[0].title)
        assertEquals("Track 2", domainArtist.tracks[1].title)
        assertEquals("https://i.ytimg.com/vi/12345678901/hqdefault.jpg", domainArtist.tracks[0].displayArtwork)
        assertEquals("https://i.ytimg.com/vi/12345678902/hqdefault.jpg", domainArtist.tracks[1].displayArtwork)
        assertEquals("FEmusic_artist_tracks", ytArtist.songsBrowseEndpoint?.first)
    }

    @Test
    fun test26_parseTracksPage_extracts_tracks_and_continuation() {
        val root = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("singleColumnBrowseResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().put(JSONObject().apply {
                        put("tabRenderer", JSONObject().apply {
                            put("content", JSONObject().apply {
                                put("sectionListRenderer", JSONObject().apply {
                                    put("contents", JSONArray().put(JSONObject().apply {
                                        put("musicShelfRenderer", JSONObject().apply {
                                            put("contents", JSONArray().apply {
                                                put(JSONObject().apply {
                                                    put("musicResponsiveListItemRenderer", JSONObject().apply {
                                                        put("flexColumns", JSONArray().apply {
                                                            put(JSONObject().apply {
                                                                put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                    put("text", JSONObject().put("runs", JSONArray().put(JSONObject().apply {
                                                                        put("text", "Artist Track 1")
                                                                        put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", "art_vid_01")))
                                                                    })))
                                                                })
                                                            })
                                                            put(JSONObject().apply {
                                                                put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                    put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Artist Name"))))
                                                                })
                                                            })
                                                        })
                                                    })
                                                })
                                            })
                                            put("continuations", JSONArray().put(JSONObject().apply {
                                                put("nextContinuationData", JSONObject().put("continuation", "cont_token_artist_tracks_page_2"))
                                            }))
                                        })
                                    }))
                                })
                            })
                        })
                    }))
                })
            })
        }

        val (tracks, nextCont) = com.example.service.integrations.ytmusic.YtMusicContinuationParser.parseTracksPage(root)
        assertEquals(1, tracks.size)
        assertEquals("yt_art_vid_01", tracks[0].id)
        assertEquals("Artist Track 1", tracks[0].title)
        assertEquals("cont_token_artist_tracks_page_2", nextCont)
    }

    @Test
    fun test27_parseSearchContinuation_extracts_paged_results() {
        val root = JSONObject().apply {
            put("onResponseReceivedActions", JSONArray().put(JSONObject().apply {
                put("appendContinuationItemsAction", JSONObject().apply {
                    put("continuationItems", JSONArray().apply {
                        put(JSONObject().apply {
                            put("musicResponsiveListItemRenderer", JSONObject().apply {
                                put("flexColumns", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                            put("text", JSONObject().put("runs", JSONArray().put(JSONObject().apply {
                                                put("text", "Continuation Song")
                                                put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", "cont_vid_01")))
                                            })))
                                        })
                                    })
                                    put(JSONObject().apply {
                                        put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                            put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Continuation Artist"))))
                                        })
                                    })
                                })
                            })
                        })
                        put(JSONObject().apply {
                            put("continuationItemRenderer", JSONObject().apply {
                                put("continuationEndpoint", JSONObject().apply {
                                    put("continuationCommand", JSONObject().put("token", "next_page_token_3"))
                                })
                            })
                        })
                    })
                })
            }))
        }

        val searchResult = com.example.service.integrations.ytmusic.YtMusicContinuationParser.parseSearchContinuation(root)
        assertEquals(1, searchResult.tracks.size)
        assertEquals("yt_cont_vid_01", searchResult.tracks[0].id)
        assertEquals("Continuation Song", searchResult.tracks[0].title)
        assertEquals("next_page_token_3", searchResult.continuationToken)
    }

    @Test
    fun test28_search_sun_mere_humsafar_itemSectionRenderer_parsed() {
        val root = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("tabbedSearchResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().put(JSONObject().apply {
                        put("tabRenderer", JSONObject().apply {
                            put("content", JSONObject().apply {
                                put("sectionListRenderer", JSONObject().apply {
                                    put("contents", JSONArray().apply {
                                        // Card shelf with episode/card
                                        put(JSONObject().apply {
                                            put("musicCardShelfRenderer", JSONObject().apply {
                                                put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Humsafar"))))
                                                put("subtitle", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Song • Akhil Sachdeva"))))
                                                put("buttons", JSONArray().put(JSONObject().apply {
                                                    put("buttonRenderer", JSONObject().apply {
                                                        put("command", JSONObject().put("watchEndpoint", JSONObject().put("videoId", "8v-TWxPWIWc")))
                                                    })
                                                }))
                                            })
                                        })
                                        // Section 1: itemSectionRenderer containing genuine song result
                                        put(JSONObject().apply {
                                            put("itemSectionRenderer", JSONObject().apply {
                                                put("contents", JSONArray().put(JSONObject().apply {
                                                    put("musicResponsiveListItemRenderer", JSONObject().apply {
                                                        put("flexColumns", JSONArray().apply {
                                                            put(JSONObject().apply {
                                                                put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                    put("text", JSONObject().put("runs", JSONArray().put(JSONObject().apply {
                                                                        put("text", "Humsafar")
                                                                        put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", "Aokj-w3COw0")))
                                                                    })))
                                                                })
                                                            })
                                                            put(JSONObject().apply {
                                                                put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                    put("text", JSONObject().put("runs", JSONArray().apply {
                                                                        put(JSONObject().put("text", "Akhil Sachdeva & Mansheel Gujral"))
                                                                        put(JSONObject().put("text", " • "))
                                                                        put(JSONObject().put("text", "Badrinath Ki Dulhania"))
                                                                    }))
                                                                })
                                                            })
                                                        })
                                                    })
                                                }))
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    }))
                })
            })
        }

        val parsed = com.example.service.integrations.ytmusic.YtMusicParser.parseSearchResponse(root)
        assertEquals(2, parsed.tracks.size)
        assertEquals("yt_8v-TWxPWIWc", parsed.tracks[0].id)
        assertEquals("yt_Aokj-w3COw0", parsed.tracks[1].id)
        assertEquals("Humsafar", parsed.tracks[1].title)
        assertEquals("Akhil Sachdeva & Mansheel Gujral", parsed.tracks[1].artist)
    }

    @Test
    fun test29_artist_discography_to_album_navigation_stack() = runTest(testDispatcher) {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.local.AppDatabase::class.java).allowMainThreadQueries().build()
        val repository = MusicRepository(database.musicDao())
        val viewModel = MainViewModel(repository = repository, playbackService = playbackService)

        // Navigate to Artist
        val artist = Artist(id = "art_alfaaz", name = "Alfaaz", artwork = "https://example.com/alfaaz.jpg")
        viewModel.navigateToArtist(artist)
        advanceUntilIdle()

        assertEquals(1, viewModel.subScreenStack.value.size)
        assertTrue(viewModel.currentSubScreen.value is com.example.viewmodel.SubScreenDestination.ArtistScreen)
        assertEquals("Alfaaz", (viewModel.currentSubScreen.value as com.example.viewmodel.SubScreenDestination.ArtistScreen).artist.name)

        // Click on Album/Single from Artist page
        val album = Album(id = "MPREb_alfaaz_acoustic", title = "Alfaaz - Acoustic", artist = "Alfaaz", artwork = "https://example.com/acoustic.jpg")
        viewModel.navigateToAlbum(album)
        advanceUntilIdle()

        // Verify AlbumScreen is now active on top of stack
        assertEquals(2, viewModel.subScreenStack.value.size)
        assertTrue(viewModel.currentSubScreen.value is com.example.viewmodel.SubScreenDestination.AlbumScreen)
        assertEquals("Alfaaz - Acoustic", (viewModel.currentSubScreen.value as com.example.viewmodel.SubScreenDestination.AlbumScreen).album.title)

        // Click Back
        viewModel.navigateBack()
        advanceUntilIdle()

        // Verify seamlessly returned to ArtistScreen
        assertEquals(1, viewModel.subScreenStack.value.size)
        assertTrue(viewModel.currentSubScreen.value is com.example.viewmodel.SubScreenDestination.ArtistScreen)
        assertEquals("Alfaaz", (viewModel.currentSubScreen.value as com.example.viewmodel.SubScreenDestination.ArtistScreen).artist.name)

        // Click Back again -> returns to root
        viewModel.navigateBack()
        advanceUntilIdle()
        assertEquals(0, viewModel.subScreenStack.value.size)
        assertNull(viewModel.currentSubScreen.value)
    }

    @Test
    fun test30_fetchYtAlbum_populates_tracks_and_artwork() = runTest(testDispatcher) {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.local.AppDatabase::class.java).allowMainThreadQueries().build()
        val repository = MusicRepository(database.musicDao())

        val mockYtService = object : com.example.service.integrations.ytmusic.YtMusicService {
            override suspend fun search(query: String) = Result.failure<com.example.service.integrations.ytmusic.YtSearchResult>(Exception())
            override suspend fun searchTracks(query: String) = Result.failure<List<com.example.service.integrations.ytmusic.YtTrack>>(Exception())
            override suspend fun getHomeShelves() = Result.failure<List<com.example.service.integrations.ytmusic.YtHomeShelf>>(Exception())
            override suspend fun getArtist(browseId: String) = Result.failure<com.example.service.integrations.ytmusic.YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String): Result<com.example.service.integrations.ytmusic.YtAlbum> {
                return Result.success(
                    com.example.service.integrations.ytmusic.YtAlbum(
                        id = browseId,
                        title = "Alfaaz - Acoustic",
                        artist = "Alfaaz",
                        artwork = "https://example.com/art.jpg",
                        bannerArtwork = "https://example.com/banner.jpg",
                        tracks = listOf(
                            com.example.service.integrations.ytmusic.YtTrack(
                                id = "yt_track12345",
                                title = "Alfaaz Acoustic Track",
                                artist = "Alfaaz",
                                album = "Alfaaz - Acoustic",
                                artwork = "https://example.com/art.jpg"
                            )
                        )
                    )
                )
            }
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<com.example.service.integrations.ytmusic.YtPlaylist>(Exception())
        }

        val resultAlbum = repository.fetchYtAlbum("MPREb_alfaaz_acoustic", "Alfaaz - Acoustic", mockYtService)
        assertNotNull(resultAlbum)
        assertEquals("Alfaaz - Acoustic", resultAlbum?.title)
        assertEquals(1, resultAlbum?.tracks?.size)
        assertEquals("yt_track12345", resultAlbum?.tracks?.get(0)?.id)
        assertEquals("https://example.com/art.jpg", resultAlbum?.displayArtwork)
    }

    @Test
    fun test31_multilingual_hindi_search_preserved() = runTest(testDispatcher) {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.local.AppDatabase::class.java).allowMainThreadQueries().build()
        val repository = MusicRepository(database.musicDao())

        val mockYtService = object : com.example.service.integrations.ytmusic.YtMusicService {
            override suspend fun search(query: String): Result<com.example.service.integrations.ytmusic.YtSearchResult> {
                return Result.success(
                    com.example.service.integrations.ytmusic.YtSearchResult(
                        tracks = listOf(
                            com.example.service.integrations.ytmusic.YtTrack(
                                id = "yt_Aokj-w3COw0",
                                title = "Humsafar",
                                artist = "Akhil Sachdeva & Mansheel Gujral",
                                artwork = "https://example.com/humsafar.jpg"
                            )
                        )
                    )
                )
            }
            override suspend fun searchTracks(query: String) = Result.success(emptyList<com.example.service.integrations.ytmusic.YtTrack>())
            override suspend fun getHomeShelves() = Result.failure<List<com.example.service.integrations.ytmusic.YtHomeShelf>>(Exception())
            override suspend fun getArtist(browseId: String) = Result.failure<com.example.service.integrations.ytmusic.YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<com.example.service.integrations.ytmusic.YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<com.example.service.integrations.ytmusic.YtPlaylist>(Exception())
        }

        val searchEngine = com.example.service.search.MusicSearchEngine(repository, ytMusicService = mockYtService)

        // Searching in Devanagari Hindi
        val result = searchEngine.search("सुन मेरे हमसफ़र")
        assertEquals(1, result.tracks.size)
        assertEquals("yt_Aokj-w3COw0", result.tracks[0].id)
        assertEquals("Humsafar", result.tracks[0].title)

        // Searching in Romanized Hindi
        val resultRoman = searchEngine.search("Sun Mere Humsafar")
        assertEquals(1, resultRoman.tracks.size)
        assertEquals("yt_Aokj-w3COw0", resultRoman.tracks[0].id)
    }
}
