package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.importer.PlaylistImportService
import com.example.service.importer.model.MatchConfidence
import com.example.service.importer.model.MatchStatus
import com.example.service.importer.model.PlatformType
import com.example.service.importer.model.PlaylistImportData
import com.example.service.importer.model.PlaylistImportSummary
import com.example.service.importer.model.TrackMatchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportStep {
    INPUT,
    EXTRACTING,
    MATCHING,
    REVIEW,
    IMPORTING,
    SUCCESS,
    ERROR
}

data class ImportProgressState(
    val current: Int = 0,
    val total: Int = 0,
    val message: String = ""
)

class PlaylistImportViewModel(
    private val musicRepository: MusicRepository,
    private val importService: PlaylistImportService = PlaylistImportService(musicRepository)
) : ViewModel() {

    private val _step = MutableStateFlow(ImportStep.INPUT)
    val step: StateFlow<ImportStep> = _step.asStateFlow()

    private val _playlistUrl = MutableStateFlow("")
    val playlistUrl: StateFlow<String> = _playlistUrl.asStateFlow()

    private val _selectedPlatform = MutableStateFlow<PlatformType?>(null)
    val selectedPlatform: StateFlow<PlatformType?> = _selectedPlatform.asStateFlow()

    private val _progress = MutableStateFlow(ImportProgressState())
    val progress: StateFlow<ImportProgressState> = _progress.asStateFlow()

    private val _extractedPlaylist = MutableStateFlow<PlaylistImportData?>(null)
    val extractedPlaylist: StateFlow<PlaylistImportData?> = _extractedPlaylist.asStateFlow()

    private val _matchResults = MutableStateFlow<List<TrackMatchResult>>(emptyList())
    val matchResults: StateFlow<List<TrackMatchResult>> = _matchResults.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _createdPlaylistId = MutableStateFlow<String?>(null)
    val createdPlaylistId: StateFlow<String?> = _createdPlaylistId.asStateFlow()

    private var activeJob: Job? = null

    fun onUrlChanged(newUrl: String) {
        _playlistUrl.value = newUrl
        _errorMessage.value = null

        // Auto-detect platform if not explicitly locked
        val lower = newUrl.lowercase()
        if (lower.contains("spotify.com")) {
            _selectedPlatform.value = PlatformType.SPOTIFY
        } else if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            _selectedPlatform.value = PlatformType.YOUTUBE_MUSIC
        }
    }

    fun onSelectPlatform(platform: PlatformType) {
        _selectedPlatform.value = platform
        _errorMessage.value = null
    }

    fun fetchPlaylist() {
        val raw = _playlistUrl.value.trim()
        if (raw.isBlank()) {
            _errorMessage.value = "Please paste a public playlist URL first."
            return
        }

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _step.value = ImportStep.EXTRACTING
            _errorMessage.value = null
            _progress.value = ImportProgressState(0, 0, "Validating playlist URL...")

            // 1. Validate and parse URL
            val parseResult = importService.validateAndParseUrl(raw)
            if (parseResult.isFailure) {
                _errorMessage.value = parseResult.exceptionOrNull()?.message ?: "Invalid URL."
                _step.value = ImportStep.ERROR
                return@launch
            }

            val parsedUrl = parseResult.getOrThrow()
            _selectedPlatform.value = parsedUrl.platform
            _progress.value = ImportProgressState(0, 0, "Connecting to ${parsedUrl.platform.displayName}...")

            // 2. Extract public playlist
            val extractResult = importService.extractPlaylist(parsedUrl) { loaded, total ->
                _progress.value = ImportProgressState(
                    current = loaded,
                    total = total,
                    message = "Extracting tracks ($loaded of $total)..."
                )
            }

            if (extractResult.isFailure) {
                _errorMessage.value = extractResult.exceptionOrNull()?.message
                    ?: "Could not extract playlist. Please ensure the playlist is public."
                _step.value = ImportStep.ERROR
                return@launch
            }

            val playlistData = extractResult.getOrThrow()
            _extractedPlaylist.value = playlistData

            // 3. Match tracks against Anyfy music catalog
            _step.value = ImportStep.MATCHING
            val initialResults = mutableListOf<TrackMatchResult>()

            val matchedList = importService.matchTracks(playlistData) { current, total, result ->
                initialResults.add(result)
                _matchResults.value = initialResults.toList()
                _progress.value = ImportProgressState(
                    current = current,
                    total = total,
                    message = "Matching: ${result.original.title} ($current/$total)"
                )
            }

            _matchResults.value = matchedList
            _step.value = ImportStep.REVIEW
        }
    }

    fun selectCandidate(resultIndex: Int, selectedTrack: Track) {
        _matchResults.update { current ->
            if (resultIndex in current.indices) {
                val updated = current.toMutableList()
                val old = updated[resultIndex]
                updated[resultIndex] = old.copy(
                    matchedTrack = selectedTrack,
                    status = MatchStatus.MATCHED,
                    confidence = MatchConfidence.HIGH,
                    confidenceScore = 1.0f
                )
                updated
            } else current
        }
    }

    fun removeMatch(resultIndex: Int) {
        _matchResults.update { current ->
            if (resultIndex in current.indices) {
                val updated = current.toMutableList()
                val old = updated[resultIndex]
                updated[resultIndex] = old.copy(
                    matchedTrack = null,
                    status = MatchStatus.NOT_FOUND,
                    confidence = MatchConfidence.NONE,
                    confidenceScore = 0f
                )
                updated
            } else current
        }
    }

    fun commitImport(userId: String) {
        val playlist = _extractedPlaylist.value ?: return
        val currentResults = _matchResults.value

        val tracksToImport = currentResults.mapNotNull { it.matchedTrack }
        if (tracksToImport.isEmpty()) {
            _errorMessage.value = "No matched tracks available to import."
            return
        }

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _step.value = ImportStep.IMPORTING
            _progress.value = ImportProgressState(0, tracksToImport.size, "Creating Anyfy playlist...")

            val commitResult = importService.commitImport(
                playlistName = playlist.playlistName,
                playlistDescription = playlist.playlistDescription,
                artworkUrl = playlist.artwork,
                matchedTracks = tracksToImport,
                userId = userId
            )

            if (commitResult.isSuccess) {
                _createdPlaylistId.value = commitResult.getOrThrow()
                _step.value = ImportStep.SUCCESS
            } else {
                _errorMessage.value = commitResult.exceptionOrNull()?.message ?: "Failed to import playlist."
                _step.value = ImportStep.ERROR
            }
        }
    }

    fun getSummary(): PlaylistImportSummary {
        val pl = _extractedPlaylist.value
        val results = _matchResults.value

        var matched = 0
        var needsReview = 0
        var multiple = 0
        var notFound = 0

        results.forEach { r ->
            when (r.status) {
                MatchStatus.MATCHED -> matched++
                MatchStatus.NEEDS_REVIEW -> needsReview++
                MatchStatus.MULTIPLE_MATCHES -> multiple++
                MatchStatus.NOT_FOUND, MatchStatus.UNAVAILABLE -> notFound++
            }
        }

        return PlaylistImportSummary(
            playlistName = pl?.playlistName ?: "Imported Playlist",
            originalPlatform = pl?.source ?: PlatformType.SPOTIFY,
            totalTracks = pl?.totalTracks ?: results.size,
            matchedCount = matched,
            needsReviewCount = needsReview,
            multipleMatchesCount = multiple,
            notFoundCount = notFound
        )
    }

    fun cancelImport() {
        activeJob?.cancel()
        reset()
    }

    fun reset() {
        activeJob?.cancel()
        _step.value = ImportStep.INPUT
        _playlistUrl.value = ""
        _selectedPlatform.value = null
        _extractedPlaylist.value = null
        _matchResults.value = emptyList()
        _errorMessage.value = null
        _createdPlaylistId.value = null
        _progress.value = ImportProgressState()
    }
}

