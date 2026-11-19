package com.example

import android.content.Context
import androidx.room.Room as AndroidRoom
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.data.repository.SearchCache
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.SearchCategory
import com.example.model.Track
import com.example.model.UnifiedSearchResult
import com.example.service.importer.normalizer.TrackNormalizer
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.YtAlbum
import com.example.service.integrations.ytmusic.YtArtist
import com.example.service.integrations.ytmusic.YtHomeShelf
import com.example.service.integrations.ytmusic.YtMusicClient
import com.example.service.integrations.ytmusic.YtMusicContinuationParser
import com.example.service.integrations.ytmusic.YtMusicParser
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtPlaylist
import com.example.service.integrations.ytmusic.YtSearchResult
import com.example.service.integrations.ytmusic.YtTrack
import com.example.service.search.MusicSearchEngine
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeMusicSearchCatalogueRegressionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository

    private val fakeYouTubeService = object : YouTubeService {
        override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean) = Result.success("https://example.com/stream.mp4")
        override suspend fun searchTracks(query: String): List<Track> = emptyList()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = AndroidRoom.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MusicRepository(db.musicDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. MANDATORY QUERY REPAIR & RELEVANCE PRESERVATION
    // =========================================================================

    @Test
    fun mandatoryQuery1_sunMereHumsafar_resolvesToGenuineOfficialSong() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String): Result<YtSearchResult> {
                return Result.success(
                    YtSearchResult(
                        tracks = listOf(
                            YtTrack(
                                id = "yt_Aokj-w3COw0",
                                title = "Humsafar",
                                artist = "Akhil Sachdeva, Mansheel Gujral",
                                album = "Badrinath Ki Dulhania",
                                artwork = "https://example.com/humsafar.jpg",
                                bannerArtwork = "https://example.com/humsafar_banner.jpg",
                                durationSeconds = 269
                            ),
                            YtTrack(
                                id = "yt_8v-TWxPWIWc",
                                title = "Sun Mere Humsafar (Mahesh Babu)",
                                artist = "Pankaj Sahoo",
                                album = "Single",
                                artwork = "https://example.com/fan.jpg",
                                durationSeconds = 180
                            )
                        )
                    )
                )
            }
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)
        val result = engine.search("Sun Mere Humsafar", "guest")

        assertEquals(2, result.tracks.size)
        assertEquals("yt_Aokj-w3COw0", result.tracks[0].id)
        assertEquals("Humsafar", result.tracks[0].title)
        assertEquals("Akhil Sachdeva, Mansheel Gujral", result.tracks[0].artist)
        assertEquals("Badrinath Ki Dulhania", result.tracks[0].album)
    }

    @Test
    fun mandatoryQueries_tumHiHo_and_channaMereya_preservedInOrder() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String): Result<YtSearchResult> {
                return if (query.contains("Tum Hi Ho")) {
                    Result.success(
                        YtSearchResult(
                            tracks = listOf(
                                YtTrack(id = "yt_tumhiho_1", title = "Tum Hi Ho", artist = "Arijit Singh", album = "Aashiqui 2"),
                                YtTrack(id = "yt_tumhiho_2", title = "Tu Hi Hai Aashiqui", artist = "Arijit Singh")
                            )
                        )
                    )
                } else {
                    Result.success(
                        YtSearchResult(
                            tracks = listOf(
                                YtTrack(id = "yt_channa_1", title = "Channa Mereya", artist = "Arijit Singh", album = "Ae Dil Hai Mushkil"),
                                YtTrack(id = "yt_channa_2", title = "Bulleya", artist = "Pritam, Amit Mishra")
                            )
                        )
                    )
                }
            }
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)

        val resTumHiHo = engine.search("Tum Hi Ho", "guest")
        assertEquals("yt_tumhiho_1", resTumHiHo.tracks.first().id)
        assertEquals("Tum Hi Ho", resTumHiHo.tracks.first().title)

        val resChanna = engine.search("Channa Mereya", "guest")
        assertEquals("yt_channa_1", resChanna.tracks.first().id)
        assertEquals("Channa Mereya", resChanna.tracks.first().title)
    }

    @Test
    fun mandatoryQueries_kesariya_satranga_alfaaz_arijitSingh() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String): Result<YtSearchResult> {
                return when (query) {
                    "Kesariya" -> Result.success(YtSearchResult(tracks = listOf(YtTrack(id = "yt_kesariya", title = "Kesariya", artist = "Arijit Singh"))))
                    "Satranga" -> Result.success(YtSearchResult(tracks = listOf(YtTrack(id = "yt_satranga", title = "Satranga", artist = "Arijit Singh", album = "Animal"))))
                    "Alfaaz" -> Result.success(YtSearchResult(tracks = listOf(YtTrack(id = "yt_alfaaz", title = "Alfaaz", artist = "Hamza Malik & Zain Zohaib"))))
                    "Arijit Singh" -> Result.success(
                        YtSearchResult(
                            artists = listOf(YtArtist(id = "UC_arijit", name = "Arijit Singh")),
                            tracks = listOf(YtTrack(id = "yt_oonchi", title = "Oonchi Oonchi Deewarein", artist = "Arijit Singh"))
                        )
                    )
                    else -> Result.success(YtSearchResult())
                }
            }
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)

        val resK = engine.search("Kesariya", "guest")
        assertEquals("yt_kesariya", resK.tracks.first().id)

        val resS = engine.search("Satranga", "guest")
        assertEquals("yt_satranga", resS.tracks.first().id)

        val resA = engine.search("Alfaaz", "guest")
        assertEquals("yt_alfaaz", resA.tracks.first().id)

        val resArtist = engine.search("Arijit Singh", "guest")
        assertEquals("UC_arijit", resArtist.artists.first().id)
        assertEquals("Arijit Singh", resArtist.artists.first().name)
    }

    @Test
    fun partialQueries_sunMere_and_channa_matchRelevantTracks() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String): Result<YtSearchResult> {
                return if (query == "Sun Mere") {
                    Result.success(
                        YtSearchResult(
                            tracks = listOf(
                                YtTrack(id = "yt_humsafar", title = "Humsafar", artist = "Akhil Sachdeva"),
                                YtTrack(id = "yt_mehboob", title = "Sun Mere Mehboob", artist = "Nazia Hassan")
                            )
                        )
                    )
                } else {
                    Result.success(
                        YtSearchResult(
                            tracks = listOf(
                                YtTrack(id = "yt_channa_gippy", title = "Channa", artist = "Gippy Grewal"),
                                YtTrack(id = "yt_chann_arijit", title = "Chann", artist = "Arijit Singh")
                            )
                        )
                    )
                }
            }
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)

        val res1 = engine.search("Sun Mere", "guest")
        assertEquals(2, res1.tracks.size)

        val res2 = engine.search("Channa", "guest")
        assertEquals(2, res2.tracks.size)
        assertEquals("yt_channa_gippy", res2.tracks.first().id)
    }

    // =========================================================================
    // 2. MULTILINGUAL & DEVANAGARI UNICODE NORMALIZATION
    // =========================================================================

    @Test
    fun devanagari_and_multilingualQueries_preservedWithoutCorruption() {
        val hindiKesariya = "केसरिया"
        val hindiHumsafar = "सुन मेरे हमसफ़र"
        val hindiTumHiHo = "तुम ही हो"
        val arabicSong = "حبيبي"

        // Matras and Unicode letters must NOT be wiped or corrupted
        assertEquals("केसरिया", TrackNormalizer.stripAccents(hindiKesariya))
        assertEquals("सुन मेरे हमसफ़र", TrackNormalizer.stripAccents(hindiHumsafar))
        assertEquals("तुम ही हो", TrackNormalizer.stripAccents(hindiTumHiHo))
        assertEquals("حبيبي", TrackNormalizer.stripAccents(arabicSong))

        // simplifyForComparison must preserve Unicode letters (\p{L}) and not return empty string
        assertEquals("केसरिया", TrackNormalizer.simplifyForComparison(hindiKesariya))
        assertEquals("सुन मेरे हमसफ़र", TrackNormalizer.simplifyForComparison(hindiHumsafar))
        assertEquals("तुम ही हो", TrackNormalizer.simplifyForComparison(hindiTumHiHo))
        assertEquals("حبيبي", TrackNormalizer.simplifyForComparison(arabicSong))

        // Latin diacritics must still be cleanly normalized
        assertEquals("deja vu", TrackNormalizer.simplifyForComparison("Déjà Vu"))
        assertEquals("beyonce", TrackNormalizer.simplifyForComparison("Beyoncé"))
    }

    // =========================================================================
    // 3. WORKING YOUTUBE MUSIC SEARCH FILTERS
    // =========================================================================

    @Test
    fun searchFilters_sendGenuineParametersAndReturnCategoryItems() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun searchWithFilter(query: String, filter: SearchCategory): Result<YtSearchResult> {
                return when (filter) {
                    SearchCategory.SONGS -> Result.success(
                        YtSearchResult(
                            tracks = listOf(
                                YtTrack(id = "yt_s1", title = "Humsafar", artist = "Akhil Sachdeva"),
                                YtTrack(id = "yt_s2", title = "Samjhawan", artist = "Arijit Singh")
                            ),
                            continuationToken = "token_songs_page2"
                        )
                    )
                    SearchCategory.ALBUMS -> Result.success(
                        YtSearchResult(
                            albums = listOf(
                                YtAlbum(id = "alb_1", title = "Badrinath Ki Dulhania", artist = "Various Artists"),
                                YtAlbum(id = "alb_2", title = "Aashiqui 2", artist = "Mithoon, Ankit Tiwari")
                            ),
                            continuationToken = "token_albums_page2"
                        )
                    )
                    SearchCategory.ARTISTS -> Result.success(
                        YtSearchResult(
                            artists = listOf(
                                YtArtist(id = "art_1", name = "Akhil Sachdeva"),
                                YtArtist(id = "art_2", name = "Arijit Singh")
                            )
                        )
                    )
                    SearchCategory.PLAYLISTS -> Result.success(
                        YtSearchResult(
                            playlists = listOf(
                                YtPlaylist(id = "pl_1", title = "Bollywood Romantic"),
                                YtPlaylist(id = "pl_2", title = "Arijit Singh Hits")
                            )
                        )
                    )
                    SearchCategory.ALL -> Result.success(YtSearchResult())
                }
            }
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)

        // 1. Songs Filter
        val songsResult = engine.search("Arijit", "guest", SearchCategory.SONGS)
        assertEquals(2, songsResult.tracks.size)
        assertEquals("token_songs_page2", songsResult.continuationToken)
        assertEquals(SearchCategory.SONGS, songsResult.category)

        // 2. Albums Filter
        val albumsResult = engine.search("Arijit", "guest", SearchCategory.ALBUMS)
        assertEquals(2, albumsResult.albums.size)
        assertEquals("alb_1", albumsResult.albums[0].id)
        assertEquals(SearchCategory.ALBUMS, albumsResult.category)

        // 3. Artists Filter
        val artistsResult = engine.search("Arijit", "guest", SearchCategory.ARTISTS)
        assertEquals(2, artistsResult.artists.size)
        assertEquals("art_1", artistsResult.artists[0].id)
        assertEquals(SearchCategory.ARTISTS, artistsResult.category)

        // 4. Playlists Filter
        val playlistsResult = engine.search("Arijit", "guest", SearchCategory.PLAYLISTS)
        assertEquals(2, playlistsResult.playlists.size)
        assertEquals("pl_1", playlistsResult.playlists[0].id)
        assertEquals(SearchCategory.PLAYLISTS, playlistsResult.category)
    }

    // =========================================================================
    // 4. PAGINATION & CONTINUATION TOKENS
    // =========================================================================

    @Test
    fun pagination_appendsSubsequentPagesWithoutDuplicates() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun searchWithFilter(query: String, filter: SearchCategory) = Result.success(
                YtSearchResult(
                    tracks = listOf(
                        YtTrack(id = "yt_1", title = "Track 1", artist = "Artist"),
                        YtTrack(id = "yt_2", title = "Track 2", artist = "Artist")
                    ),
                    continuationToken = "page2_token"
                )
            )
            override suspend fun searchContinuation(continuationToken: String) = Result.success(
                YtSearchResult(
                    tracks = listOf(
                        YtTrack(id = "yt_2", title = "Track 2 (duplicate)", artist = "Artist"),
                        YtTrack(id = "yt_3", title = "Track 3", artist = "Artist")
                    ),
                    continuationToken = "page3_token"
                )
            )
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)
        val initial = engine.search("Track", "guest", SearchCategory.SONGS)
        assertEquals(2, initial.tracks.size)
        assertEquals("page2_token", initial.continuationToken)

        val page2 = engine.searchContinuation(initial.continuationToken!!, SearchCategory.SONGS)
        val combinedTracks = (initial.tracks + page2.tracks).distinctBy { it.id }

        assertEquals(3, combinedTracks.size)
        assertEquals("yt_1", combinedTracks[0].id)
        assertEquals("yt_2", combinedTracks[1].id)
        assertEquals("yt_3", combinedTracks[2].id)
        assertEquals("page3_token", page2.continuationToken)
    }

    // =========================================================================
    // 5. PARSER RENDERER SUPPORT
    // =========================================================================

    @Test
    fun parser_supportsAllSearchRenderers() {
        val json = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("tabbedSearchResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tabRenderer", JSONObject().apply {
                                put("content", JSONObject().apply {
                                    put("sectionListRenderer", JSONObject().apply {
                                        put("contents", JSONArray().apply {
                                            // 1. musicCardShelfRenderer (Top result)
                                            put(JSONObject().apply {
                                                put("musicCardShelfRenderer", JSONObject().apply {
                                                    put("title", JSONObject().apply {
                                                        put("runs", JSONArray().apply { put(JSONObject().put("text", "Top Song")) })
                                                    })
                                                    put("subtitle", JSONObject().apply {
                                                        put("runs", JSONArray().apply { put(JSONObject().put("text", "Akhil Sachdeva")) })
                                                    })
                                                    put("onTap", JSONObject().apply {
                                                        put("watchEndpoint", JSONObject().put("videoId", "top_vid_123"))
                                                    })
                                                })
                                            })
                                            // 2. itemSectionRenderer with musicResponsiveListItemRenderer
                                            put(JSONObject().apply {
                                                put("itemSectionRenderer", JSONObject().apply {
                                                    put("contents", JSONArray().apply {
                                                        put(JSONObject().apply {
                                                            put("musicResponsiveListItemRenderer", JSONObject().apply {
                                                                put("playlistItemData", JSONObject().put("videoId", "rsp_vid_456"))
                                                                put("flexColumns", JSONArray().apply {
                                                                    put(JSONObject().apply {
                                                                        put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                            put("text", JSONObject().apply {
                                                                                put("runs", JSONArray().apply { put(JSONObject().put("text", "Responsive Track")) })
                                                                            })
                                                                        })
                                                                    })
                                                                    put(JSONObject().apply {
                                                                        put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                            put("text", JSONObject().apply {
                                                                                put("runs", JSONArray().apply {
                                                                                    put(JSONObject().put("text", "Song"))
                                                                                    put(JSONObject().put("text", " • "))
                                                                                    put(JSONObject().put("text", "Artist Name"))
                                                                                })
                                                                            })
                                                                        })
                                                                    })
                                                                })
                                                            })
                                                        })
                                                        // 3. musicTwoRowItemRenderer (Album)
                                                        put(JSONObject().apply {
                                                            put("musicTwoRowItemRenderer", JSONObject().apply {
                                                                put("title", JSONObject().apply {
                                                                    put("runs", JSONArray().apply { put(JSONObject().put("text", "TwoRow Album")) })
                                                                })
                                                                put("subtitle", JSONObject().apply {
                                                                    put("runs", JSONArray().apply { put(JSONObject().put("text", "Album • 2024")) })
                                                                })
                                                                put("navigationEndpoint", JSONObject().apply {
                                                                    put("browseEndpoint", JSONObject().put("browseId", "MPREb_album123"))
                                                                })
                                                            })
                                                        })
                                                        // 4. compactVideoRenderer
                                                        put(JSONObject().apply {
                                                            put("compactVideoRenderer", JSONObject().apply {
                                                                put("videoId", "vid_vid_789")
                                                                put("title", JSONObject().apply {
                                                                    put("runs", JSONArray().apply { put(JSONObject().put("text", "Music Video Track")) })
                                                                })
                                                                put("shortBylineText", JSONObject().apply {
                                                                    put("runs", JSONArray().apply { put(JSONObject().put("text", "Video Artist")) })
                                                                })
                                                            })
                                                        })
                                                    })
                                                    put("continuations", JSONArray().apply {
                                                        put(JSONObject().apply {
                                                            put("nextContinuationData", JSONObject().put("continuation", "token_item_sec_1"))
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

        val result = YtMusicParser.parseSearchResponse(json)
        assertEquals(3, result.tracks.size)
        assertEquals("yt_top_vid_123", result.tracks[0].id)
        assertEquals("yt_rsp_vid_456", result.tracks[1].id)
        assertEquals("yt_vid_vid_789", result.tracks[2].id)
        assertEquals(1, result.albums.size)
        assertEquals("MPREb_album123", result.albums[0].id)
        assertEquals("TwoRow Album", result.albums[0].title)
        assertEquals("token_item_sec_1", result.continuationToken)
    }
}
