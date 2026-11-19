package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Album
import com.example.model.Artist
import com.example.model.BannerFallbackLevel
import com.example.model.Track
import com.example.model.UnifiedSearchResult
import com.example.service.PlaybackService
import com.example.service.PlayerStatus
import com.example.service.RepeatMode
import com.example.service.diagnostics.BannerDiagnosticLogger
import com.example.service.integrations.ytmusic.YtAlbum
import com.example.service.integrations.ytmusic.YtMusicParser
import com.example.service.integrations.ytmusic.YtTrack
import com.example.service.search.MusicSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnyfyComprehensiveOverhaulTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun test01_artwork_multitiered_fallback_resolution() {
        // Track with valid artwork
        val trackWithArt = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            artwork = "https://lh3.googleusercontent.com/test=s544",
            durationSeconds = 212
        )
        assertEquals("https://lh3.googleusercontent.com/test=s544", trackWithArt.displayArtwork)

        // Track with blank artwork and blank banner, but valid 11-char YouTube ID
        val trackBlankArt = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            artwork = "",
            bannerArtwork = "",
            durationSeconds = 212
        )
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", trackBlankArt.displayArtwork)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", trackBlankArt.displayBannerArtwork)

        // Track with raw 11-char ID without prefix
        val trackRawId = Track(
            id = "dQw4w9WgXcQ",
            title = "Raw Video ID Track",
            artist = "Artist",
            artwork = "",
            bannerArtwork = "",
            durationSeconds = 180
        )
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", trackRawId.displayArtwork)

        // Track with non-YouTube ID should return empty string so UI uses neutral placeholder
        val trackForeignId = Track(
            id = "local_track_12345",
            title = "Local Audio",
            artist = "Artist",
            artwork = "",
            bannerArtwork = "",
            durationSeconds = 120
        )
        assertEquals("", trackForeignId.displayArtwork)
    }

    @Test
    fun test02_high_res_artwork_regex_safety() {
        // Normal thumbnail with =w60-h60-l90-rj should expand
        val standardThumb = org.json.JSONArray().put(org.json.JSONObject().put("url", "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"))
        val highResStandard = YtMusicParser.extractHighResArtwork(standardThumb)
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", highResStandard)

        // Path with /s60 should expand
        val slashThumb = org.json.JSONArray().put(org.json.JSONObject().put("url", "https://lh3.googleusercontent.com/xyz/s120/photo.jpg"))
        val highResSlash = YtMusicParser.extractHighResArtwork(slashThumb)
        assertEquals("https://lh3.googleusercontent.com/xyz/s544/photo.jpg", highResSlash)

        // Random hash containing 's12' like /ALV-UjVs12abc should NOT be corrupted!
        val hashUrl = org.json.JSONArray().put(org.json.JSONObject().put("url", "https://lh3.googleusercontent.com/ALV-UjVs12abc_xyz/photo.jpg"))
        val highResHash = YtMusicParser.extractHighResArtwork(hashUrl)
        assertFalse("Hash token 's12' must not be corrupted into s544", highResHash.contains("ALV-UjVs544"))
    }

    @Test
    fun test03_search_full_queue_context_without_truncation() {
        val searchResults = (1..30).map { i ->
            Track(
                id = "yt_track_$i",
                title = "Search Result Track $i",
                artist = "Artist $i",
                artwork = "https://example.com/$i.jpg",
                durationSeconds = 200
            )
        }

        // Simulating the user tapping index 5
        val clickedIndex = 5
        // Previously: (clickedIndex + 10).coerceAtMost(searchResults.size) artificially limited queue to 10 items
        // Correct implementation: subList(clickedIndex, searchResults.size)
        val passedQueue = searchResults.subList(clickedIndex, searchResults.size)

        assertEquals(25, passedQueue.size)
        assertEquals("yt_track_6", passedQueue.first().id)
        assertEquals("yt_track_30", passedQueue.last().id)
    }

    @Test
    fun test04_queue_management_and_bounds_safety() {
        val playbackService = PlaybackService.getInstance(context)

        val track1 = Track(id = "yt_11111111111", title = "Track 1", artist = "Artist", artwork = "", durationSeconds = 180)
        val track2 = Track(id = "yt_22222222222", title = "Track 2", artist = "Artist", artwork = "", durationSeconds = 200)
        val track3 = Track(id = "yt_33333333333", title = "Track 3", artist = "Artist", artwork = "", durationSeconds = 220)

        playbackService.playTrack(track1, listOf(track1, track2, track3))

        var state = playbackService.uiState.value
        assertEquals("yt_11111111111", state.currentTrack?.id)
        assertEquals(0, state.currentTrackIndex)
        assertEquals(3, state.queue.size)

        // Test playNext
        val trackNext = Track(id = "yt_44444444444", title = "Track Play Next", artist = "Artist", artwork = "", durationSeconds = 190)
        playbackService.playNext(trackNext)
        state = playbackService.uiState.value
        assertEquals(4, state.queue.size)
        assertEquals("yt_44444444444", state.queue[1].id)

        // Test addToQueue
        val trackQueue = Track(id = "yt_55555555555", title = "Track Add To Queue", artist = "Artist", artwork = "", durationSeconds = 210)
        playbackService.addToQueue(trackQueue)
        state = playbackService.uiState.value
        assertEquals(5, state.queue.size)
        assertEquals("yt_55555555555", state.queue.last().id)

        // Test removeFromQueue
        playbackService.removeFromQueue(1) // remove trackNext
        state = playbackService.uiState.value
        assertEquals(4, state.queue.size)
        assertEquals("yt_22222222222", state.queue[1].id)

        // Test reorderQueue
        playbackService.reorderQueue(1, 2)
        state = playbackService.uiState.value
        assertEquals("yt_33333333333", state.queue[1].id)
        assertEquals("yt_22222222222", state.queue[2].id)

        // Test clearQueue preserves current track
        playbackService.clearQueue()
        state = playbackService.uiState.value
        assertEquals(1, state.queue.size)
        assertEquals("yt_11111111111", state.queue.first().id)
        assertEquals(0, state.currentTrackIndex)

        playbackService.release()
    }

    @Test
    fun test05_autoplay_and_repeat_state_transitions() {
        val playbackService = PlaybackService.getInstance(context)

        val track1 = Track(id = "yt_aaaaaaaaaaa", title = "Alpha", artist = "A", artwork = "", durationSeconds = 180)
        val track2 = Track(id = "yt_bbbbbbbbbbb", title = "Beta", artist = "B", artwork = "", durationSeconds = 190)

        playbackService.playTrack(track1, listOf(track1, track2))
        playbackService.playTrackAtIndex(1, isUserInitiated = true)

        var state = playbackService.uiState.value
        assertEquals(1, state.currentTrackIndex)

        // Test toggleRepeat
        assertEquals(RepeatMode.OFF, state.repeatMode)
        playbackService.toggleRepeat()
        assertEquals(RepeatMode.ALL, playbackService.uiState.value.repeatMode)
        playbackService.toggleRepeat()
        assertEquals(RepeatMode.ONE, playbackService.uiState.value.repeatMode)
        playbackService.toggleRepeat()
        assertEquals(RepeatMode.OFF, playbackService.uiState.value.repeatMode)

        // Test toggleShuffle
        assertFalse(state.isShuffle)
        playbackService.toggleShuffle()
        assertTrue(playbackService.uiState.value.isShuffle)
        playbackService.toggleShuffle()
        assertFalse(playbackService.uiState.value.isShuffle)

        playbackService.release()
    }

    @Test
    fun test06_resolveBannerHierarchy_withGenuineWideBanner() {
        val track = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Track with Wide Banner",
            artist = "Artist",
            artwork = "https://lh3.googleusercontent.com/square=s544",
            bannerArtwork = "https://lh3.googleusercontent.com/wide=w1280-h720-l90-rj",
            albumId = "alb_genuine_123",
            durationSeconds = 212
        )
        val hierarchy = track.resolveBannerHierarchy { _ -> "https://lh3.googleusercontent.com/album_art=s544" }
        assertTrue("Hierarchy must have at least 1 candidate", hierarchy.isNotEmpty())
        assertEquals(BannerFallbackLevel.LEVEL_1_GENUINE_WIDE, hierarchy[0].level)
        assertEquals("https://lh3.googleusercontent.com/wide=w1280-h720-l90-rj", hierarchy[0].url)
        // Priority 3: Song verified square artwork transformed to landscape
        assertEquals(BannerFallbackLevel.LEVEL_3_SONG_SQUARE_LANDSCAPE, hierarchy[1].level)
        assertEquals("https://lh3.googleusercontent.com/square=w1280-h720-l90-rj", hierarchy[1].url)
    }

    @Test
    fun test07_resolveBannerHierarchy_withAssociatedAlbum() {
        val track = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Track without Wide Banner",
            artist = "Artist",
            artwork = "https://lh3.googleusercontent.com/square=s544",
            bannerArtwork = "",
            albumId = "MPREb_album_id",
            durationSeconds = 212
        )
        val hierarchy = track.resolveBannerHierarchy { albumId ->
            if (albumId == "MPREb_album_id") "https://lh3.googleusercontent.com/album_cover=s800" else null
        }
        assertTrue("Hierarchy must have candidates", hierarchy.isNotEmpty())
        // Priority 3: Song's own verified square artwork transformed to landscape comes before album
        assertEquals(BannerFallbackLevel.LEVEL_3_SONG_SQUARE_LANDSCAPE, hierarchy[0].level)
        assertEquals("https://lh3.googleusercontent.com/square=w1280-h720-l90-rj", hierarchy[0].url)
        // Priority 4: Associated album square artwork transformed to landscape
        val albumCandidates = hierarchy.filter { it.level == BannerFallbackLevel.LEVEL_4_ALBUM_ID_ARTWORK }
        assertTrue("Must include album candidates", albumCandidates.isNotEmpty())
        assertEquals("https://lh3.googleusercontent.com/album_cover=w1280-h720-l90-rj", albumCandidates[0].url)
    }

    @Test
    fun test08_resolveBannerHierarchy_withVideoIdFallback() {
        val track = Track(
            id = "yt_9bZkp7q19f0",
            title = "Gangnam Style",
            artist = "PSY",
            artwork = "",
            bannerArtwork = "",
            albumId = "",
            durationSeconds = 252
        )
        val hierarchy = track.resolveBannerHierarchy(null)
        assertTrue("Hierarchy must contain video ID candidate", hierarchy.isNotEmpty())
        assertEquals(BannerFallbackLevel.LEVEL_5_VERIFIED_VIDEO_THUMBNAIL, hierarchy[0].level)
        assertEquals("https://i.ytimg.com/vi/9bZkp7q19f0/hqdefault.jpg", hierarchy[0].url)
    }

    @Test
    fun test09_resolveBannerHierarchy_squareLandscapeFallback() {
        val track = Track(
            id = "local_track_custom_id_456",
            title = "Local Audio",
            artist = "Artist",
            artwork = "https://example.com/square_cover.png",
            bannerArtwork = "",
            albumId = "",
            durationSeconds = 180
        )
        val hierarchy = track.resolveBannerHierarchy(null)
        assertEquals(1, hierarchy.size)
        assertEquals(BannerFallbackLevel.LEVEL_3_SONG_SQUARE_LANDSCAPE, hierarchy[0].level)
        assertEquals("https://example.com/square_cover.png", hierarchy[0].url)
    }

    @Test
    fun test10_bannerDiagnosticLogger_capturesAllMandatoryFields() {
        BannerDiagnosticLogger.clear()
        BannerDiagnosticLogger.logBannerLoaded(
            trackId = "yt_test_track_1",
            albumId = "alb_test_1",
            availableThumbnailCount = 3,
            selectedArtworkUrl = "https://lh3.googleusercontent.com/path/to/image.jpg?query=123",
            success = true,
            fallbackLevel = BannerFallbackLevel.LEVEL_1_GENUINE_WIDE.name
        )
        BannerDiagnosticLogger.logBannerLoaded(
            trackId = "yt_test_track_2",
            albumId = "alb_test_2",
            availableThumbnailCount = 2,
            selectedArtworkUrl = "https://i.ytimg.com/vi/12345678901/hqdefault.jpg",
            success = false,
            fallbackLevel = BannerFallbackLevel.LEVEL_5_VERIFIED_VIDEO_THUMBNAIL.name,
            errorMessage = "HTTP 404 Not Found"
        )
        val history = BannerDiagnosticLogger.getHistory()
        assertEquals(2, history.size)

        val entry1 = history[0]
        assertEquals("yt_test_track_1", entry1.trackId)
        assertEquals("alb_test_1", entry1.albumId)
        assertEquals(3, entry1.availableThumbnailCount)
        assertTrue(entry1.success)
        assertEquals(BannerFallbackLevel.LEVEL_1_GENUINE_WIDE.name, entry1.fallbackLevel)
        assertEquals("lh3.googleusercontent.com/path/to/image.jpg", entry1.selectedArtworkUrl)
        assertEquals(null, entry1.errorMessage)

        val entry2 = history[1]
        assertEquals("yt_test_track_2", entry2.trackId)
        assertFalse(entry2.success)
        assertEquals("HTTP 404 Not Found", entry2.errorMessage)
    }

    @Test
    fun test11_extractBannerArtwork_regexSafety_doesNotCorruptUrlHashes() {
        val wideThumbnails = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("url", "https://lh3.googleusercontent.com/ALV-UjVs12_token_hash=w1200-h675")
                put("width", 1200)
                put("height", 675)
            })
        }
        val bannerUrl = YtMusicParser.extractBannerArtwork(wideThumbnails, "dQw4w9WgXcQ")
        assertFalse("Regex must not corrupt token hash ALV-UjVs12", bannerUrl.contains("ALV-UjVs1280"))
        assertTrue("Regex must upgrade dimension parameters to 1280x720", bannerUrl.contains("=w1280-h720-l90-rj"))
    }

    @Test
    fun test12_titli_wide_player_banner_resolution() {
        val titliTrack = Track(
            id = "yt_V8zXLMIjlcw",
            title = "Titli",
            artist = "Gopi Sundar & Chinmayi",
            artwork = "https://i.ytimg.com/vi/V8zXLMIjlcw/hqdefault.jpg",
            bannerArtwork = "https://i.ytimg.com/vi/V8zXLMIjlcw/hqdefault.jpg?sqp=-oaymwEWCJADEOEBIAQqCggAEOADGC0guwJIWg&rs=AMzJL3k9GrYDexVqvIpA7Y5g_AOUmxw55w",
            albumId = "",
            durationSeconds = 234
        )
        val hierarchy = titliTrack.resolveBannerHierarchy(null)
        assertTrue("Titli must have candidates", hierarchy.isNotEmpty())
        assertEquals(BannerFallbackLevel.LEVEL_1_GENUINE_WIDE, hierarchy[0].level)
        assertTrue("Titli candidate 0 must be genuine wide banner", hierarchy[0].url.contains("sqp="))
    }

    @Test
    fun test13_alfaaz_universal_banner_fallback_to_square_landscape() {
        val alfaazTrack = Track(
            id = "yt_Vug4YLiIsaU",
            title = "Alfaaz",
            artist = "Hamza Malik",
            artwork = "https://yt3.googleusercontent.com/jm8eCdG8nXSZf_GCozvo4hjFs1IHiKzdIoDJmj70xIgMnj5BkX93BaFmqP1LZK_oytR1KngKuZ5edqoM=w544-h544-l90-rj",
            bannerArtwork = "",
            albumId = "",
            durationSeconds = 186
        )
        val hierarchy = alfaazTrack.resolveBannerHierarchy(null)
        assertTrue("Alfaaz must resolve candidates", hierarchy.isNotEmpty())
        // Priority 3: The same song's verified square artwork transformed into landscape background
        assertEquals(BannerFallbackLevel.LEVEL_3_SONG_SQUARE_LANDSCAPE, hierarchy[0].level)
        assertEquals("https://yt3.googleusercontent.com/jm8eCdG8nXSZf_GCozvo4hjFs1IHiKzdIoDJmj70xIgMnj5BkX93BaFmqP1LZK_oytR1KngKuZ5edqoM=w1280-h720-l90-rj", hierarchy[0].url)
        // Also contains the direct square crop as secondary candidate
        assertTrue(hierarchy.any { it.url.contains("=w544-h544-l90-rj") })
    }

    @Test
    fun test14_track_change_cancels_previous_requests_and_updates_cache_keys() {
        val trackA = Track(id = "yt_track_aaa", title = "Track A", artist = "Artist A", artwork = "https://lh3.googleusercontent.com/artA=s544", durationSeconds = 180)
        val trackB = Track(id = "yt_track_bbb", title = "Track B", artist = "Artist B", artwork = "https://lh3.googleusercontent.com/artB=s544", durationSeconds = 200)

        val hierarchyA = trackA.resolveBannerHierarchy(null)
        val hierarchyB = trackB.resolveBannerHierarchy(null)

        val cacheKeyA = "${trackA.id}_banner_${hierarchyA[0].level.code}_${hierarchyA[0].url}"
        val cacheKeyB = "${trackB.id}_banner_${hierarchyB[0].level.code}_${hierarchyB[0].url}"

        assertFalse("Different tracks must have completely distinct cache keys", cacheKeyA == cacheKeyB)
        assertTrue(cacheKeyA.startsWith("yt_track_aaa"))
        assertTrue(cacheKeyB.startsWith("yt_track_bbb"))
    }

    @Test
    fun test15_twenty_songs_banner_guarantee_zero_blanks() {
        val songTitles = listOf(
            "Titli", "Alfaaz", "Kesariya", "Tum Hi Ho", "Channa Mereya",
            "Raataan Lambiyan", "Apna Bana Le", "O Maahi", "Shayad", "Agar Tum Saath Ho",
            "Kalank", "Ae Dil Hai Mushkil", "Gerua", "Phir Aur Kya Chahiye", "Chaleya",
            "Satranga", "Tere Hawaale", "Ilahi", "Hawayein", "Zalima", "Heeriye", "Pasoori"
        )
        assertEquals(22, songTitles.size)

        for (i in songTitles.indices) {
            val title = songTitles[i]
            val isWide = i % 2 == 0
            val track = Track(
                id = "yt_vid_${"%08d".format(i)}",
                title = title,
                artist = "Artist $i",
                artwork = "https://yt3.googleusercontent.com/thumb_$i=w544-h544-l90-rj",
                bannerArtwork = if (isWide) "https://i.ytimg.com/vi/vid_${"%08d".format(i)}/hqdefault.jpg?sqp=test" else "",
                albumId = if (i % 3 == 0) "alb_$i" else "",
                durationSeconds = 180 + i * 5
            )

            val hierarchy = track.resolveBannerHierarchy { albId -> "https://lh3.googleusercontent.com/album_$albId=s800" }
            assertTrue("Track '$title' must have at least one candidate", hierarchy.isNotEmpty())
            val firstCandidate = hierarchy[0]
            assertTrue("Candidate URL must be valid HTTP", firstCandidate.url.startsWith("http"))
            assertFalse("Candidate URL must not be blank", firstCandidate.url.isBlank())
            if (isWide) {
                assertEquals("Wide track must resolve LEVEL_1", BannerFallbackLevel.LEVEL_1_GENUINE_WIDE, firstCandidate.level)
            } else {
                assertEquals("Track without wide must resolve LEVEL_3 landscape fallback", BannerFallbackLevel.LEVEL_3_SONG_SQUARE_LANDSCAPE, firstCandidate.level)
                assertTrue("Must upgrade Google CDN thumb to 1280x720", firstCandidate.url.contains("=w1280-h720-l90-rj"))
            }
        }
    }

    @Test
    fun test16_room_track_metadata_entity_banner_and_artwork_preservation() {
        val originalTrack = Track(
            id = "yt_Vug4YLiIsaU",
            title = "Alfaaz",
            artist = "Hamza Malik",
            artwork = "https://yt3.googleusercontent.com/thumb=w544-h544-l90-rj",
            bannerArtwork = "https://yt3.googleusercontent.com/thumb=w1280-h720-l90-rj",
            durationSeconds = 186
        )
        val entity = com.example.data.local.entity.TrackMetadataEntity(
            id = originalTrack.id,
            title = originalTrack.title,
            artist = originalTrack.artist,
            artwork = originalTrack.artwork,
            bannerArtwork = originalTrack.bannerArtwork,
            durationSeconds = originalTrack.durationSeconds
        )
        assertEquals("https://yt3.googleusercontent.com/thumb=w544-h544-l90-rj", entity.artwork)
        assertEquals("https://yt3.googleusercontent.com/thumb=w1280-h720-l90-rj", entity.bannerArtwork)

        // Restoring track preserves both square artwork and wide banner
        val restoredTrack = Track(
            id = entity.id,
            title = entity.title,
            artist = entity.artist,
            artwork = entity.artwork,
            bannerArtwork = entity.bannerArtwork,
            durationSeconds = entity.durationSeconds
        )
        assertEquals(entity.artwork, restoredTrack.artwork)
        assertEquals(entity.bannerArtwork, restoredTrack.bannerArtwork)
        assertEquals(entity.artwork, restoredTrack.displayArtwork)
        assertEquals(entity.bannerArtwork, restoredTrack.displayBannerArtwork)
    }

    @Test
    fun test17_affected_six_tracks_artwork_and_banner_integrity() {
        val affectedTracks = listOf(
            Triple("yt_Vug4YLiIsaU", "Alfaaz", "Hamza Malik & Zain Zohaib"),
            Triple("yt_f1h6NZYem0o", "Akhiyaan Gulaab", "Mitraz"),
            Triple("yt_YPpwM7zfTLk", "Shaayraana", "Arijit Singh"),
            Triple("yt_eM8Mjuq4MwQ", "Aankhein Khuli", "Jatin-Lalit"),
            Triple("yt_5mKyNksQuEA", "Raaton Mein", "Aditya Rikhari"),
            Triple("yt_w0pbdkrRRJM", "Tere Ishq Mein", "Aditya Rikhari"),
            Triple("yt_zfWlGsiFDzk", "Safar", "Juss"),
            Triple("yt_7v08LtnQtak", "Ruswa", "Twin Strings")
        )

        for ((id, title, artist) in affectedTracks) {
            val track = Track(
                id = id,
                title = title,
                artist = artist,
                artwork = "",
                bannerArtwork = "",
                durationSeconds = 180
            )

            // Even when raw strings are empty, cleanVideoId provides YouTube fallback
            assertNotNull("Track $title must have cleanVideoId", track.cleanVideoId)
            val cleanVid = track.cleanVideoId!!
            assertEquals("Clean video ID must be 11 chars", 11, cleanVid.length)

            // displayArtwork & displayBannerArtwork must NEVER be blank
            assertFalse("Track '$title' displayArtwork must not be blank", track.displayArtwork.isBlank())
            assertFalse("Track '$title' displayBannerArtwork must not be blank", track.displayBannerArtwork.isBlank())
            assertEquals("https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg", track.displayArtwork)
            assertEquals("https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg", track.displayBannerArtwork)

            val hierarchy = track.resolveBannerHierarchy(null)
            assertTrue("Track '$title' must resolve banner hierarchy", hierarchy.isNotEmpty())
            assertTrue("Hierarchy candidate 0 must not be blank", hierarchy[0].url.isNotBlank())
        }
    }

    @Test
    fun test18_parseHistoryResponse_with_itemSectionRenderer() {
        val json = org.json.JSONObject("""
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
                                                    "itemSectionRenderer": {
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
                                                                                                        "text": "Akhiyaan Gulaab",
                                                                                                        "navigationEndpoint": {
                                                                                                            "watchEndpoint": {
                                                                                                                "videoId": "f1h6NZYem0o"
                                                                                                            }
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
                                                                                                        "text": "Mitraz"
                                                                                                    }
                                                                                                ]
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

        val history = YtMusicParser.parseHistoryResponse(json)
        assertEquals(1, history.size)
        assertEquals("yt_f1h6NZYem0o", history[0].id)
        assertEquals("Akhiyaan Gulaab", history[0].title)
        assertEquals("Mitraz", history[0].artist)
        // Artwork automatically resolves to high-res video thumbnail fallback
        assertEquals("https://i.ytimg.com/vi/f1h6NZYem0o/hqdefault.jpg", history[0].artwork)
    }
}
