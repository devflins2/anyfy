package com.example

import com.example.data.remote.dto.MusicTrackDto
import com.example.data.repository.MusicSearchRepository
import com.example.model.MusicSearchUiItem
import com.example.model.toTrack
import com.example.model.toUiItem
import com.example.viewmodel.MusicSearchUiState
import com.example.viewmodel.MusicSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicSearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun musicTrackDto_mappingToUiItem_formatsAccurately() {
        val dto = MusicTrackDto(
            trackId = 123456L,
            trackName = "Kesariya",
            artistName = "Arijit Singh",
            collectionName = "Brahmastra",
            artworkUrl100 = "https://is1-ssl.mzstatic.com/image/thumb/Music112/100x100bb.jpg",
            previewUrl = "https://audio-ssl.itunes.apple.com/preview.m4a",
            trackTimeMillis = 268000L, // 4 mins 28 secs
            primaryGenreName = "Bollywood",
            releaseDate = "2022-07-17T12:00:00Z"
        )
        val uiItem = dto.toUiItem()
        assertEquals("api_123456", uiItem.id)
        assertEquals("Kesariya", uiItem.title)
        assertEquals("Arijit Singh", uiItem.artist)
        assertEquals("Brahmastra", uiItem.album)
        assertEquals("4:28", uiItem.durationFormatted)
        assertEquals(268, uiItem.durationSeconds)
        assertTrue(uiItem.artworkUrl.contains("600x600bb"))
        assertEquals("2022", uiItem.releaseYear)
        assertEquals("Bollywood", uiItem.genre)
        assertTrue(uiItem.isStreamable)

        val track = uiItem.toTrack()
        assertEquals(uiItem.id, track.id)
        assertEquals("Kesariya", track.title)
        assertEquals("https://audio-ssl.itunes.apple.com/preview.m4a", track.audioUrl)
    }

    @Test
    fun viewModel_initialState_isIdle() {
        val fakeRepo = object : MusicSearchRepository {
            override suspend fun searchTracks(query: String, limit: Int) = Result.success(emptyList<MusicSearchUiItem>())
        }
        val viewModel = MusicSearchViewModel(fakeRepo)
        assertEquals(MusicSearchUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun viewModel_searchSuccess_updatesStateToSuccess() = runTest(testDispatcher) {
        val mockItems = listOf(
            MusicSearchUiItem(
                id = "1",
                title = "Song A",
                artist = "Artist A",
                album = "Album A",
                artworkUrl = "https://example.com/art.jpg",
                durationFormatted = "3:30",
                durationSeconds = 210,
                audioPreviewUrl = "https://example.com/audio.m4a",
                genre = "Pop",
                releaseYear = "2024"
            )
        )
        val fakeRepo = object : MusicSearchRepository {
            override suspend fun searchTracks(query: String, limit: Int) = Result.success(mockItems)
        }
        val viewModel = MusicSearchViewModel(fakeRepo)

        viewModel.search("Song A")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MusicSearchUiState.Success)
        assertEquals(1, (state as MusicSearchUiState.Success).tracks.size)
        assertEquals("Song A", state.tracks.first().title)
    }

    @Test
    fun viewModel_searchError_updatesStateToError() = runTest(testDispatcher) {
        val fakeRepo = object : MusicSearchRepository {
            override suspend fun searchTracks(query: String, limit: Int) = Result.failure<List<MusicSearchUiItem>>(
                RuntimeException("Network connection timed out")
            )
        }
        val viewModel = MusicSearchViewModel(fakeRepo)

        viewModel.search("Any Query")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MusicSearchUiState.Error)
        assertEquals("Network connection timed out", (state as MusicSearchUiState.Error).message)
    }
}
