package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.MusicSearchRepository
import com.example.data.repository.NetworkMusicSearchRepository
import com.example.model.MusicSearchUiItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI State representation for the Music Search feature.
 */
sealed interface MusicSearchUiState {
    object Idle : MusicSearchUiState
    object Loading : MusicSearchUiState
    data class Success(val tracks: List<MusicSearchUiItem>) : MusicSearchUiState
    object Empty : MusicSearchUiState
    data class Error(val message: String) : MusicSearchUiState
}

/**
 * ViewModel responsible for managing music search operations, debounced query inputs,
 * and communicating with the Retrofit-backed repository.
 */
class MusicSearchViewModel(
    private val repository: MusicSearchRepository = NetworkMusicSearchRepository()
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiState = MutableStateFlow<MusicSearchUiState>(MusicSearchUiState.Idle)
    val uiState: StateFlow<MusicSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Updates the active query and automatically schedules a debounced API request.
     */
    fun onQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = MusicSearchUiState.Idle
            return
        }

        searchJob = viewModelScope.launch {
            delay(350L) // Debounce network calls to prevent rapid successive queries
            executeSearch(query)
        }
    }

    /**
     * Executes the search operation against the Retrofit public API.
     */
    fun search(query: String = _searchQuery.value) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = MusicSearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            executeSearch(query)
        }
    }

    private suspend fun executeSearch(query: String) {
        _uiState.value = MusicSearchUiState.Loading
        val result = repository.searchTracks(query)
        result.fold(
            onSuccess = { tracks ->
                _uiState.value = if (tracks.isEmpty()) {
                    MusicSearchUiState.Empty
                } else {
                    MusicSearchUiState.Success(tracks)
                }
            },
            onFailure = { error ->
                _uiState.value = MusicSearchUiState.Error(
                    message = error.localizedMessage ?: "Failed to fetch music metadata. Please check connection."
                )
            }
        )
    }

    /**
     * Clears current query and resets state to Idle.
     */
    fun clearSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _uiState.value = MusicSearchUiState.Idle
    }

    /**
     * Retries search with the current search query.
     */
    fun retry() {
        search(_searchQuery.value)
    }
}
