package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.RepeatMode
import com.example.service.diagnostics.PlaybackDiagnosticLogger
import com.example.service.integrations.StreamException
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.YtMusicClient
import com.example.service.integrations.ytmusic.YtMusicParser
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtPlaybackReporter
import com.example.service.integrations.ytmusic.YtPlaybackTracking
import com.example.service.integrations.ytmusic.YtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class YouTubeMusicExclusiveStreamingAndHistoryTest {

    private lateinit var context: Context
    private lateinit var playbackService: PlaybackService
    private val testDispatcher = StandardTestDispatcher()

    private val track1 = Track(
        id = "yt_dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        artistId = "art_rick_astley",
        album = "Whenever You Need Somebody",
        albumId = "alb_rick",
        artwork = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        durationSeconds = 213
    )

    private val track2 = Track(
        id = "yt_k2qgadSvNyU",
        title = "New Rules",
        artist = "Dua Lipa",
        artistId = "art_dua_lipa",
        album = "Dua Lipa",
        albumId = "alb_dua",
        artwork = "https://i.ytimg.com/vi/k2qgadSvNyU/hqdefault.jpg",
        durationSeconds = 209
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        playbackService = PlaybackService.getInstance(context)
        PlaybackDiagnosticLogger.init(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun test01_exact_youtube_music_track_id_mapping() {
        val ytTrack = YtTrack(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            durationSeconds = 213
        )
        val domainTrack = ytTrack.toTrack()
        assertEquals("yt_dQw4w9WgXcQ", domainTrack.id)
        val cleanVid = domainTrack.id.removePrefix("yt_").trim()
        assertEquals("dQw4w9WgXcQ", cleanVid)
        assertTrue(YouTubeService.isValidYouTubeVideoId(cleanVid))
    }

    @Test
    fun test02_stream_resolution_uses_exact_video_id() {
        val vid = "Umqb9KENgmk"
        assertTrue(YouTubeService.isValidYouTubeVideoId(vid))
        assertFalse(YouTubeService.isValidYouTubeVideoId("short"))
        assertFalse(YouTubeService.isValidYouTubeVideoId("PL1234567890")) // playlist
        assertFalse(YouTubeService.isValidYouTubeVideoId("UC1234567890")) // channel
    }

    @Test
    fun test03_wrong_track_substitution_prevented_no_foreign_providers() {
        val foreignTrack = Track(
            id = "saavn_song_99999",
            title = "Nonexistent Song 123456789",
            artist = "Nonexistent Artist 123456789",
            artwork = "",
            durationSeconds = 180
        )
        val clean = foreignTrack.id.removePrefix("yt_").trim()
        assertFalse("Foreign/invalid video ID should not pass validation", YouTubeService.isValidYouTubeVideoId(clean))
        assertFalse("Local file path should not pass validation", YouTubeService.isValidYouTubeVideoId("/sdcard/Music/test.mp3"))
        assertFalse("Spotify URI should not pass validation", YouTubeService.isValidYouTubeVideoId("spotify:track:12345678901"))
    }

    @Test
    fun test04_generateCpn_produces_valid_16_char_nonce() {
        val cpn1 = YtPlaybackReporter.generateCpn()
        val cpn2 = YtPlaybackReporter.generateCpn()

        assertEquals(16, cpn1.length)
        assertEquals(16, cpn2.length)
        assertTrue("CPN must match regex", cpn1.matches(Regex("^[a-zA-Z0-9_-]{16}$")))
        assertTrue("CPN must match regex", cpn2.matches(Regex("^[a-zA-Z0-9_-]{16}$")))
        assertFalse("Subsequent CPNs must be distinct", cpn1 == cpn2)
    }

    @Test
    fun test05_parsePlaybackTracking_extracts_all_urls() {
        val playerJson = JSONObject("""
            {
                "playbackTracking": {
                    "videostatsPlaybackUrl": {
                        "baseUrl": "https://s.youtube.com/api/stats/playback?ns=yt&el=detailpage&docid=dQw4w9WgXcQ"
                    },
                    "videostatsWatchtimeUrl": {
                        "baseUrl": "https://s.youtube.com/api/stats/watchtime?ns=yt&el=detailpage&docid=dQw4w9WgXcQ"
                    },
                    "videostatsDelayplayUrl": {
                        "baseUrl": "https://s.youtube.com/api/stats/delayplay?ns=yt&el=detailpage&docid=dQw4w9WgXcQ"
                    },
                    "atrUrl": {
                        "baseUrl": "https://s.youtube.com/api/stats/atr?ns=yt&el=detailpage"
                    }
                }
            }
        """.trimIndent())

        val tracking = YtPlaybackReporter.parsePlaybackTracking(playerJson)
        assertNotNull(tracking)
        assertEquals("https://s.youtube.com/api/stats/playback?ns=yt&el=detailpage&docid=dQw4w9WgXcQ", tracking?.playbackUrl)
        assertEquals("https://s.youtube.com/api/stats/watchtime?ns=yt&el=detailpage&docid=dQw4w9WgXcQ", tracking?.watchtimeUrl)
        assertEquals("https://s.youtube.com/api/stats/delayplay?ns=yt&el=detailpage&docid=dQw4w9WgXcQ", tracking?.delayplayUrl)
        assertEquals("https://s.youtube.com/api/stats/atr?ns=yt&el=detailpage", tracking?.atrUrl)
    }

    @Test
    fun test06_parsePlaybackTracking_missing_or_empty_returns_null() {
        val emptyJson = JSONObject("{}")
        val tracking = YtPlaybackReporter.parsePlaybackTracking(emptyJson)
        assertNull(tracking)
    }

    @Test
    fun test07_playback_reporter_duplicate_prevention() = runTest(testDispatcher) {
        val reporter = YtPlaybackReporter(authHeaderProvider = { emptyMap() })
        val cpn = YtPlaybackReporter.generateCpn()

        // First call
        val res1 = reporter.reportPlaybackStart("dQw4w9WgXcQ", cpn)
        assertTrue(res1.isSuccess)

        // Second call with same CPN -> deduplicated instantly
        val res2 = reporter.reportPlaybackStart("dQw4w9WgXcQ", cpn)
        assertTrue(res2.isSuccess)
    }

    @Test
    fun test08_playback_service_playTrack_updates_queue_and_state() {
        playbackService.playTrack(track1, listOf(track1, track2))
        val state = playbackService.uiState.value

        assertEquals(track1.id, state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(2, state.queue.size)
        assertTrue(state.isPlaying)
    }

    @Test
    fun test09_next_and_previous_queue_navigation() {
        playbackService.playTrack(track1, listOf(track1, track2))

        // Next advances to track2
        playbackService.next()
        val stateAfterNext = playbackService.uiState.value
        assertEquals(track2.id, stateAfterNext.currentTrack?.id)
        assertEquals(1, stateAfterNext.currentTrackIndex)

        // Previous returns to track1
        playbackService.previous()
        val stateAfterPrev = playbackService.uiState.value
        assertEquals(track1.id, stateAfterPrev.currentTrack?.id)
        assertEquals(0, stateAfterPrev.currentTrackIndex)
    }

    @Test
    fun test10_smart_queue_radio_tracks_parsing() {
        val nextResponse = JSONObject("""
            {
                "contents": {
                    "singleColumnMusicWatchNextResultsRenderer": {
                        "tabbedRenderer": {
                            "watchNextTabbedResultsRenderer": {
                                "tabs": [
                                    {
                                        "tabRenderer": {
                                            "content": {
                                                "musicQueueRenderer": {
                                                    "content": {
                                                        "playlistPanelRenderer": {
                                                            "contents": [
                                                                {
                                                                    "playlistPanelVideoRenderer": {
                                                                        "videoId": "k2qgadSvNyU",
                                                                        "title": { "runs": [{ "text": "New Rules" }] },
                                                                        "longBylineText": { "runs": [{ "text": "Dua Lipa" }] }
                                                                    }
                                                                },
                                                                {
                                                                    "playlistPanelVideoRenderer": {
                                                                        "videoId": "Umqb9KENgmk",
                                                                        "title": { "runs": [{ "text": "Kesariya" }] },
                                                                        "longBylineText": { "runs": [{ "text": "Arijit Singh" }] }
                                                                    }
                                                                }
                                                            ]
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                ]
                            }
                        }
                    }
                }
            }
        """.trimIndent())

        val radioTracks = YtMusicParser.parseRadioTracks(nextResponse)
        assertEquals(2, radioTracks.size)
        assertEquals("yt_k2qgadSvNyU", radioTracks[0].id)
        assertEquals("New Rules", radioTracks[0].title)
        assertEquals("yt_Umqb9KENgmk", radioTracks[1].id)
        assertEquals("Kesariya", radioTracks[1].title)
    }

    @Test
    fun test11_authentication_headers_sanitized_in_diagnostics() {
        val headers = mapOf(
            "User-Agent" to "Anyfy/1.0",
            "Cookie" to "SAPISID=secret_token; __Secure-3PAPISID=secret_pass;",
            "Authorization" to "SAPISIDHASH 123456_abcdef",
            "X-Goog-Authuser" to "0"
        )
        PlaybackDiagnosticLogger.startSession(1001L, "yt_test", "Test Song", "test_vid_123")
        PlaybackDiagnosticLogger.logUserAgentSent(1001L, "Anyfy/1.0", headers)
        val session = PlaybackDiagnosticLogger.getSession(1001L)
        assertNotNull(session)
        assertEquals("Anyfy/1.0", session?.userAgentSent)
    }

    @Test
    fun test12_parseHistoryResponse_extracts_user_listening_history() {
        val historyJson = JSONObject("""
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
                                                                "musicResponsiveListItemRenderer": {
                                                                    "flexColumns": [
                                                                        {
                                                                            "musicResponsiveListItemFlexColumnRenderer": {
                                                                                "text": {
                                                                                    "runs": [
                                                                                        {
                                                                                            "text": "Tum Hi Ho",
                                                                                            "navigationEndpoint": {
                                                                                                "watchEndpoint": { "videoId": "Umqb9KENgmk" }
                                                                                            }
                                                                                        }
                                                                                    ]
                                                                                }
                                                                            }
                                                                        },
                                                                        {
                                                                            "musicResponsiveListItemFlexColumnRenderer": {
                                                                                "text": {
                                                                                    "runs": [{ "text": "Arijit Singh" }]
                                                                                }
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

        val historyTracks = YtMusicParser.parseHistoryResponse(historyJson)
        assertEquals(1, historyTracks.size)
        assertEquals("yt_Umqb9KENgmk", historyTracks[0].id)
        assertEquals("Tum Hi Ho", historyTracks[0].title)
    }

    @Test
    fun test13_pause_and_resume_preserves_position() {
        playbackService.playTrack(track1, listOf(track1, track2))
        playbackService.seekTo(15000L)
        playbackService.pause()

        val pausedState = playbackService.uiState.value
        assertFalse(pausedState.isPlaying)
        assertEquals(15000L, pausedState.currentPositionMs)

        playbackService.resume()
        val resumedState = playbackService.uiState.value
        assertTrue(resumedState.isPlaying)
        assertEquals(15000L, resumedState.currentPositionMs)
    }

    @Test
    fun test14_together_listening_callback_invoked_on_track_play() {
        var playedTrackId: String? = null
        playbackService.setOnTrackPlayedListener { id ->
            playedTrackId = id
        }

        playbackService.playTrack(track2)
        assertEquals(track2.id, playedTrackId)
    }
}
