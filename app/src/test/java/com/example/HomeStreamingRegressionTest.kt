package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.integrations.StreamException
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.YtMusicParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeStreamingRegressionTest {

    private lateinit var context: Context
    private lateinit var playbackService: PlaybackService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("flinsmusic_playback_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        playbackService = PlaybackService(context)
    }

    @Test
    fun homeSong_withValidVideoId_producesCorrectStreamResolutionRequest() {
        val validVideoId = "dQw4w9WgXcQ"
        assertTrue(YtMusicParser.isValidVideoId(validVideoId))
        assertTrue(YouTubeService.isValidYouTubeVideoId(validVideoId))

        val homeTrackJson = JSONObject().apply {
            put("title", JSONObject().apply {
                put("runs", JSONArray().apply {
                    put(JSONObject().apply { put("text", "Never Gonna Give You Up") })
                })
            })
            put("navigationEndpoint", JSONObject().apply {
                put("watchEndpoint", JSONObject().apply {
                    put("videoId", validVideoId)
                })
            })
            put("subtitle", JSONObject().apply {
                put("runs", JSONArray().apply {
                    put(JSONObject().apply { put("text", "Rick Astley • 3:32") })
                })
            })
        }

        val shelfJson = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("singleColumnBrowseResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tabRenderer", JSONObject().apply {
                                put("content", JSONObject().apply {
                                    put("sectionListRenderer", JSONObject().apply {
                                        put("contents", JSONArray().apply {
                                            put(JSONObject().apply {
                                                put("musicCarouselShelfRenderer", JSONObject().apply {
                                                    put("header", JSONObject().apply {
                                                        put("musicCarouselShelfBasicHeaderRenderer", JSONObject().apply {
                                                            put("title", JSONObject().apply {
                                                                put("runs", JSONArray().apply {
                                                                    put(JSONObject().apply { put("text", "Quick picks") })
                                                                })
                                                            })
                                                        })
                                                    })
                                                    put("contents", JSONArray().apply {
                                                        put(JSONObject().apply {
                                                            put("musicTwoRowItemRenderer", homeTrackJson)
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }

        val shelves = YtMusicParser.parseHomeResponse(shelfJson)
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Quick picks", shelf.title)
        assertEquals(1, shelf.items.size)

        val parsedTrack = shelf.items[0]
        assertEquals("yt_$validVideoId", parsedTrack.id)
        assertEquals("Never Gonna Give You Up", parsedTrack.title)
        assertEquals("Rick Astley", parsedTrack.artist)
    }

    @Test
    fun homeSong_withBrowseIdOrInvalidId_handledSafelyWithoutCrashing() {
        val invalidBrowseId = "MPREb_album12345"
        assertFalse(YtMusicParser.isValidVideoId(invalidBrowseId))
        assertFalse(YouTubeService.isValidYouTubeVideoId(invalidBrowseId))

        val homeTrackJson = JSONObject().apply {
            put("title", JSONObject().apply {
                put("runs", JSONArray().apply {
                    put(JSONObject().apply { put("text", "Trending Album Track") })
                })
            })
            put("navigationEndpoint", JSONObject().apply {
                put("watchEndpoint", JSONObject().apply {
                    put("videoId", invalidBrowseId)
                })
            })
            put("subtitle", JSONObject().apply {
                put("runs", JSONArray().apply {
                    put(JSONObject().apply { put("text", "Song • Diljit Dosanjh • 2024") })
                })
            })
        }

        val shelfJson = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("singleColumnBrowseResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tabRenderer", JSONObject().apply {
                                put("content", JSONObject().apply {
                                    put("sectionListRenderer", JSONObject().apply {
                                        put("contents", JSONArray().apply {
                                            put(JSONObject().apply {
                                                put("musicCarouselShelfRenderer", JSONObject().apply {
                                                    put("header", JSONObject().apply {
                                                        put("musicCarouselShelfBasicHeaderRenderer", JSONObject().apply {
                                                            put("title", JSONObject().apply {
                                                                put("runs", JSONArray().apply {
                                                                    put(JSONObject().apply { put("text", "Trending Hits") })
                                                                })
                                                            })
                                                        })
                                                    })
                                                    put("contents", JSONArray().apply {
                                                        put(JSONObject().apply {
                                                            put("musicTwoRowItemRenderer", homeTrackJson)
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }

        val shelves = YtMusicParser.parseHomeResponse(shelfJson)
        assertEquals(1, shelves.size)
        val track = shelves[0].items[0]

        // Invalid browseId is replaced by a safe metadata track ID
        assertTrue(track.id.startsWith("yt_meta_"))
        assertEquals("Trending Album Track", track.title)
        assertEquals("Diljit Dosanjh", track.artist)
    }

    @Test
    fun cleanArtistName_removesBulletPointsAndDurations() {
        assertEquals("Arijit Singh", YtMusicParser.cleanArtistName("Song • Arijit Singh • 3:45"))
        assertEquals("The Weeknd", YtMusicParser.cleanArtistName("The Weeknd • 10M views"))
        assertEquals("Diljit Dosanjh", YtMusicParser.cleanArtistName("Song • Diljit Dosanjh"))
        assertEquals("Taylor Swift", YtMusicParser.cleanArtistName("Taylor Swift"))
        assertEquals("YouTube Music", YtMusicParser.cleanArtistName(""))
        assertEquals("YouTube Music", YtMusicParser.cleanArtistName(null))

        assertEquals("Arijit Singh", YouTubeService.cleanArtistForSearch("Song • Arijit Singh • 3:45"))
        assertEquals("The Weeknd", YouTubeService.cleanArtistForSearch("The Weeknd • 10M views"))
    }

    @Test
    fun successfulStreamResolution_reachesPlaybackState() = runTest {
        // Use a catalog track that has a verified audioUrl
        val track = Track(
            id = "trk_kesariya",
            title = "Kesariya",
            artist = "Arijit Singh",
            artwork = "https://example.com/art.jpg",
            audioUrl = "https://aac.saavncdn.com/871/c2febd353f3a076a406fa37510f31f9f_320.mp4",
            durationSeconds = 268
        )

        playbackService.playTrack(track)
        val state = playbackService.uiState.value

        assertEquals("trk_kesariya", state.currentTrack?.id)
        assertTrue(state.isPlaying)
        assertEquals(0, state.currentTrackIndex)
    }

    @Test
    fun streamResolutionFailure_exitsLoadingState_andProvidesRecoverableError() = kotlinx.coroutines.runBlocking {
        val failingService = object : YouTubeService {
            override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): Result<String> =
                Result.failure(StreamException("STREAM_UNAVAILABLE", "Could not resolve stream"))
            override suspend fun resolveTrackStream(track: Track, forceRefresh: Boolean): Result<String> =
                Result.failure(StreamException("STREAM_UNAVAILABLE", "Could not resolve stream"))
            override suspend fun searchTracks(query: String): List<Track> = emptyList()
        }
        val customPlaybackService = PlaybackService(context, failingService)

        val unresolvableTrack = Track(
            id = "yt_invalid_track_id",
            title = "Unresolvable Nonexistent Track Title 123456789",
            artist = "Nonexistent Artist 987654321",
            artwork = "https://example.com/art.jpg",
            audioUrl = null,
            durationSeconds = 180
        )

        customPlaybackService.playTrack(unresolvableTrack)

        // Await the state transition out of loading/extracting/connecting
        val state = kotlinx.coroutines.withTimeout(15000L) {
            customPlaybackService.uiState.first { !it.status.isLoadingState }
        }

        assertEquals(PlayerStatus.UNAVAILABLE, state.status)
        assertFalse(state.isPlaying)
        assertNotNull(state.errorMessage)

        // Verify retry restores playback intent
        customPlaybackService.retryCurrentTrack()
        val retryState = customPlaybackService.uiState.value
        assertEquals(unresolvableTrack.id, retryState.currentTrack?.id)
    }

    @Test
    fun existingSearchPlaybackBehavior_remainsUnaffected() = runTest {
        val searchTrack = Track(
            id = "trk_believer",
            title = "Believer",
            artist = "Imagine Dragons",
            artwork = "https://example.com/believer.jpg",
            audioUrl = "https://aac.saavncdn.com/978/believer_320.mp4",
            durationSeconds = 204
        )

        playbackService.playTrack(searchTrack)
        val state = playbackService.uiState.value

        assertEquals("trk_believer", state.currentTrack?.id)
        assertEquals("Believer", state.currentTrack?.title)
        assertTrue(state.isPlaying)
        assertEquals(1, state.queue.size)
    }
}
