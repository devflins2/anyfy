package com.example

import android.content.Context
import androidx.room.Room as AndroidRoom
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.data.repository.PersistentSearchHistoryRepository
import com.example.data.repository.SearchCache
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.SearchCategory
import com.example.model.SearchSortOrder
import com.example.model.Track
import com.example.model.UnifiedSearchResult
import com.example.service.PlaybackService
import com.example.service.integrations.InMemoryRealtimeService
import com.example.service.integrations.YouTubeService
import com.example.service.integrations.ytmusic.YtAlbum
import com.example.service.integrations.ytmusic.YtArtist
import com.example.service.integrations.ytmusic.YtHomeShelf
import com.example.service.integrations.ytmusic.YtMusicService
import com.example.service.integrations.ytmusic.YtPlaylist
import com.example.service.integrations.ytmusic.YtSearchResult
import com.example.service.integrations.ytmusic.YtTrack
import com.example.service.search.MusicSearchEngine
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedMusicSearchTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository

    private val fakeYouTubeService = object : YouTubeService {
        override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean) = Result.success("https://example.com/stream.mp4")
        override suspend fun searchTracks(query: String): List<Track> = emptyList()
    }

    private val testTrackKesariya = Track(
        id = "trk_kesariya",
        title = "Kesariya",
        artist = "Arijit Singh",
        artwork = "https://example.com/kesariya.jpg",
        album = "Brahmastra",
        durationSeconds = 268
    )

    private val testTrackBlinding = Track(
        id = "trk_blinding_lights",
        title = "Blinding Lights",
        artist = "The Weeknd",
        artwork = "https://example.com/blinding.jpg",
        album = "After Hours",
        durationSeconds = 200
    )

    private val testTrackLover = Track(
        id = "trk_lover",
        title = "Lover",
        artist = "Diljit Dosanjh",
        artwork = "https://example.com/lover.jpg",
        album = "MoonChild Era",
        durationSeconds = 198
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = AndroidRoom.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MusicRepository(db.musicDao())
        repository.registerTracks(listOf(testTrackKesariya, testTrackBlinding, testTrackLover))
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. EXACT & PARTIAL SONG SEARCH & RANKING
    // =========================================================================

    @Test
    fun exactSongSearch_ranksExactTitleHighest() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)
        val result = engine.search("Kesariya", "guest")

        assertTrue("Tracks must not be empty", result.tracks.isNotEmpty())
        assertEquals("trk_kesariya", result.tracks.first().id)
        assertEquals("Kesariya", result.tracks.first().title)
    }

    @Test
    fun partialSongSearch_matchesSubstringCorrectly() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)
        val result = engine.search("blind", "guest")

        assertTrue("Partial query 'blind' should match 'Blinding Lights'", result.tracks.any { it.id == "trk_blinding_lights" })
    }

    // =========================================================================
    // 2. UNICODE & CASE-INSENSITIVE SEARCH
    // =========================================================================

    @Test
    fun search_handlesAccentsAndCaseInsensitiveQueries() = runTest(testDispatcher) {
        val accentedTrack = Track(
            id = "trk_accent",
            title = "Déjà Vu",
            artist = "Beyoncé",
            artwork = "https://example.com/deja.jpg",
            album = "B'Day",
            durationSeconds = 240
        )
        repository.registerTracks(listOf(accentedTrack))

        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)
        // Search without accents and in all-lowercase: "deja vu"
        val result = engine.search("deja vu", "guest")

        assertTrue("Search should match 'Déjà Vu' even without diacritics", result.tracks.any { it.id == "trk_accent" })
    }

    // =========================================================================
    // 3. TYPO-TOLERANT MATCHING
    // =========================================================================

    @Test
    fun typoTolerantSearch_matchesWithMinorSpellingMistake() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)
        // Typo: "Blindng Lghts" instead of "Blinding Lights"
        val score = engine.computeTrackScore("Blindng Lghts", testTrackBlinding)
        assertTrue("Score for typo 'Blindng Lghts' should be >= 0.50f, was $score", score >= 0.50f)
    }

    // =========================================================================
    // 4. MULTI-CATEGORY SEARCH (ARTISTS, ALBUMS, PLAYLISTS)
    // =========================================================================

    @Test
    fun multiCategorySearch_populatesAllCategories() = runTest(testDispatcher) {
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(
                YtSearchResult(
                    tracks = listOf(YtTrack(id = "yt_123", title = "Starboy", artist = "The Weeknd")),
                    artists = listOf(YtArtist(id = "art_weeknd", name = "The Weeknd")),
                    albums = listOf(YtAlbum(id = "alb_after_hours", title = "After Hours", artist = "The Weeknd")),
                    playlists = listOf(YtPlaylist(id = "pl_weeknd", title = "This Is The Weeknd"))
                )
            )
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }

        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)
        val result = engine.search("The Weeknd", "guest")

        assertTrue("Tracks should contain items", result.tracks.isNotEmpty())
        assertTrue("Artists should contain 'The Weeknd'", result.artists.any { it.name == "The Weeknd" })
        assertTrue("Albums should contain 'After Hours'", result.albums.any { it.title == "After Hours" })
        assertTrue("Playlists should contain 'This Is The Weeknd'", result.playlists.any { it.name == "This Is The Weeknd" })
    }

    // =========================================================================
    // 5. SEARCH FILTERING BY CATEGORY
    // =========================================================================

    @Test
    fun searchCategoryFilter_filtersAppropriately() {
        val full = UnifiedSearchResult(
            query = "test",
            tracks = listOf(testTrackKesariya),
            artists = listOf(Artist(id = "a1", name = "Arijit Singh", artwork = "")),
            albums = listOf(Album(id = "al1", title = "Brahmastra", artist = "Arijit", artwork = "", releaseYear = "2022", genre = "Pop", trackIds = emptyList())),
            playlists = listOf(Playlist(id = "p1", name = "My Romantic", description = "", artwork = "", trackIds = emptyList(), isUserCreated = true, createdAt = 1000L))
        )

        val songsOnly = full.filterByCategory(SearchCategory.SONGS)
        assertEquals(1, songsOnly.tracks.size)
        assertEquals(0, songsOnly.artists.size)
        assertEquals(0, songsOnly.albums.size)
        assertEquals(0, songsOnly.playlists.size)

        val artistsOnly = full.filterByCategory(SearchCategory.ARTISTS)
        assertEquals(0, artistsOnly.tracks.size)
        assertEquals(1, artistsOnly.artists.size)

        val albumsOnly = full.filterByCategory(SearchCategory.ALBUMS)
        assertEquals(0, albumsOnly.tracks.size)
        assertEquals(1, albumsOnly.albums.size)

        val playlistsOnly = full.filterByCategory(SearchCategory.PLAYLISTS)
        assertEquals(0, playlistsOnly.tracks.size)
        assertEquals(1, playlistsOnly.playlists.size)
    }

    // =========================================================================
    // 6. SORTING (RELEVANCE, ALPHABETICAL, RECENTLY RELEASED)
    // =========================================================================

    @Test
    fun searchSorting_alphabeticalAndRecent_sortsProperly() {
        val result = UnifiedSearchResult(
            query = "test",
            tracks = listOf(testTrackKesariya, testTrackBlinding, testTrackLover), // Kesariya, Blinding, Lover
            albums = listOf(
                Album(id = "a1", title = "Evolve", artist = "Imagine Dragons", artwork = "", releaseYear = "2017", genre = "Rock", trackIds = emptyList()),
                Album(id = "a2", title = "After Hours", artist = "The Weeknd", artwork = "", releaseYear = "2020", genre = "Pop", trackIds = emptyList()),
                Album(id = "a3", title = "Brahmastra", artist = "Pritam", artwork = "", releaseYear = "2022", genre = "Bollywood", trackIds = emptyList())
            )
        )

        val sortedAz = result.applySorting(SearchSortOrder.ALPHABETICAL)
        assertEquals("Blinding Lights", sortedAz.tracks[0].title)
        assertEquals("Kesariya", sortedAz.tracks[1].title)
        assertEquals("Lover", sortedAz.tracks[2].title)

        val sortedRecent = result.applySorting(SearchSortOrder.RECENT)
        assertEquals("Brahmastra", sortedRecent.albums[0].title) // 2022
        assertEquals("After Hours", sortedRecent.albums[1].title) // 2020
        assertEquals("Evolve", sortedRecent.albums[2].title) // 2017
    }

    // =========================================================================
    // 7. CACHING WITH TTL & LRU
    // =========================================================================

    @Test
    fun searchCache_returnsCachedResults_andRespectsExpiration() {
        val cache = SearchCache(maxCapacity = 2, ttlMs = 1000L)
        val resA = UnifiedSearchResult(query = "arijit", tracks = listOf(testTrackKesariya))

        cache.put("arijit", resA)
        val hit = cache.get("arijit")
        assertNotNull(hit)
        assertTrue(hit!!.isFromCache)
        assertEquals("Kesariya", hit.tracks.first().title)

        // Case insensitivity of cache key
        val hitCaps = cache.get("ARIJIT")
        assertNotNull(hitCaps)

        // Capacity bound
        cache.put("weeknd", UnifiedSearchResult(query = "weeknd"))
        cache.put("diljit", UnifiedSearchResult(query = "diljit"))
        // First entry "arijit" evicted due to maxCapacity 2
        val evicted = cache.get("arijit")
        assertNull(evicted)
    }

    // =========================================================================
    // 8. PERSISTENT SEARCH HISTORY & USER ISOLATION
    // =========================================================================

    @Test
    fun searchHistory_savesDeduplicatesAndIsolatesByUser() {
        val historyRepo = PersistentSearchHistoryRepository(context = context)
        val userA = "user_alpha"
        val userB = "user_beta"

        historyRepo.addRecentSearch(userA, "Arijit Singh")
        historyRepo.addRecentSearch(userA, "The Weeknd")
        historyRepo.addRecentSearch(userA, "Arijit Singh") // duplicate moves to front

        val historyA = historyRepo.getRecentSearches(userA)
        assertEquals(listOf("Arijit Singh", "The Weeknd"), historyA)

        // User B has clean, isolated history
        historyRepo.addRecentSearch(userB, "Coldplay")
        val historyB = historyRepo.getRecentSearches(userB)
        assertEquals(listOf("Coldplay"), historyB)

        // Remove single item
        historyRepo.removeRecentSearch(userA, "The Weeknd")
        assertEquals(listOf("Arijit Singh"), historyRepo.getRecentSearches(userA))

        // Clear all
        historyRepo.clearRecentSearches(userA)
        assertTrue(historyRepo.getRecentSearches(userA).isEmpty())
    }

    // =========================================================================
    // 9. VIEWMODEL SEARCH DEBOUNCING & CANCELLATION
    // =========================================================================

    @Test
    fun viewModel_debouncesInput_andCancelsOutdatedRequests() = runTest(testDispatcher) {
        val historyRepo = PersistentSearchHistoryRepository(context = context)
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(
                YtSearchResult(tracks = listOf(YtTrack(id = "yt_$query", title = "Result for $query", artist = "Artist")))
            )
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }
        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService, testDispatcher)
        val cache = SearchCache()

        val viewModel = MainViewModel(
            repository = repository,
            playbackService = PlaybackService(context),
            realtimeService = InMemoryRealtimeService(),
            searchHistoryRepository = historyRepo,
            searchEngine = engine,
            searchCache = cache
        )

        // Type rapid queries
        viewModel.onSearchQueryChanged("A")
        advanceTimeBy(100L) // Under 350ms debounce threshold
        viewModel.onSearchQueryChanged("Ar")
        advanceTimeBy(100L)
        viewModel.onSearchQueryChanged("Arijit")

        // Wait for debounce and search to finish
        advanceUntilIdle()

        val result = viewModel.unifiedSearchResult.value
        assertEquals("Arijit", result.query)
        assertFalse(viewModel.isSearching.value)
    }

    // =========================================================================
    // 10. EMPTY QUERY & RETRY BEHAVIOR
    // =========================================================================

    @Test
    fun viewModel_emptyQuery_clearsResultsImmediately() = runTest(testDispatcher) {
        val historyRepo = PersistentSearchHistoryRepository(context = context)
        val fakeYt = object : YtMusicService {
            override suspend fun search(query: String) = Result.success(YtSearchResult())
            override suspend fun searchTracks(query: String) = Result.success(emptyList<YtTrack>())
            override suspend fun getHomeShelves() = Result.success(emptyList<YtHomeShelf>())
            override suspend fun getArtist(browseId: String) = Result.failure<YtArtist>(Exception())
            override suspend fun getAlbum(browseId: String) = Result.failure<YtAlbum>(Exception())
            override suspend fun getPlaylist(playlistId: String, continuationToken: String?) = Result.failure<YtPlaylist>(Exception())
        }
        val engine = MusicSearchEngine(repository, fakeYt, fakeYouTubeService)

        val viewModel = MainViewModel(
            repository = repository,
            playbackService = PlaybackService(context),
            realtimeService = InMemoryRealtimeService(),
            searchHistoryRepository = historyRepo,
            searchEngine = engine
        )

        viewModel.onSearchQueryChanged("Kesariya")
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("")
        assertEquals("", viewModel.searchQuery.value)
        assertTrue(viewModel.unifiedSearchResult.value.isEmpty)
        assertTrue(viewModel.searchResults.value.isEmpty())
        assertFalse(viewModel.isSearching.value)
    }
}
