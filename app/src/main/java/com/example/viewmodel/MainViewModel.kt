package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.UserEntity
import com.example.data.repository.MusicRepository
import com.example.data.repository.PersistentSearchHistoryRepository
import com.example.data.repository.SearchCache
import com.example.data.repository.SearchHistoryRepository
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Playlist
import com.example.model.QuickMix
import com.example.model.Room
import com.example.model.SearchCategory
import com.example.model.SearchSortOrder
import com.example.model.Track
import com.example.model.UnifiedSearchResult
import com.example.model.VisualQuality
import com.example.service.PlaybackService
import com.example.service.PlaybackUiState
import com.example.service.integrations.InMemoryRealtimeService
import com.example.service.integrations.RealtimeRoomService
import com.example.service.search.MusicSearchEngine
import com.example.data.repository.PersistentUserPreferencesRepository
import com.example.data.repository.UserGreetingPreferences
import com.example.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class MainTab {
    HOME,
    SEARCH,
    LIBRARY,
    TOGETHER
}

data class RemovedTrackInfo(
    val playlistId: String,
    val track: Track,
    val position: Int
)

sealed interface SubScreenDestination {
    data class ArtistScreen(val artist: Artist) : SubScreenDestination
    data class AlbumScreen(val album: Album) : SubScreenDestination
    data class PlaylistScreen(val playlistId: String) : SubScreenDestination
}

class MainViewModel(
    val repository: MusicRepository,
    private val playbackService: PlaybackService = PlaybackService(),
    private val realtimeService: RealtimeRoomService = InMemoryRealtimeService(),
    private val searchHistoryRepository: SearchHistoryRepository = PersistentSearchHistoryRepository(),
    private val searchEngine: MusicSearchEngine = MusicSearchEngine(repository),
    private val searchCache: SearchCache = SearchCache(),
    val userPreferencesRepository: UserPreferencesRepository = PersistentUserPreferencesRepository(),
    val appUpdateManager: com.example.service.update.AppUpdateManager? = null
) : ViewModel() {

    // Splash animation completion flag
    private val _isSplashCompleted = MutableStateFlow(false)
    val isSplashCompleted: StateFlow<Boolean> = _isSplashCompleted.asStateFlow()

    // Tab Navigation
    private val _currentTab = MutableStateFlow(MainTab.HOME)
    val currentTab: StateFlow<MainTab> = _currentTab.asStateFlow()

    // Sub-Screen Navigation Stack
    private val _subScreenStack = MutableStateFlow<List<SubScreenDestination>>(emptyList())
    val subScreenStack: StateFlow<List<SubScreenDestination>> = _subScreenStack.asStateFlow()
    val currentSubScreen: StateFlow<SubScreenDestination?> = _subScreenStack.map { it.lastOrNull() }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    private val _selectedArtist = MutableStateFlow<Artist?>(null)
    val selectedArtist: StateFlow<Artist?> = _selectedArtist.asStateFlow()

    private val _selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedAlbum: StateFlow<Album?> = _selectedAlbum.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<String?>(null)
    private val _selectedPlaylistOverride = MutableStateFlow<Playlist?>(null)
    val selectedPlaylist: StateFlow<Playlist?> = combine(
        _selectedPlaylistId,
        repository.playlists,
        _selectedPlaylistOverride
    ) { id, list, override ->
        if (id == null) null
        else {
            val fromList = list.find { it.id == id || it.id == id.removePrefix("VL") || "VL${it.id}" == id }
            val dynamic = repository.getDynamicPlaylist(id) ?: repository.getDynamicPlaylist(id.removePrefix("VL"))
            override?.takeIf { it.id == id || it.id == id.removePrefix("VL") || "VL${it.id}" == id } ?: dynamic ?: fromList
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    // Settings Screen Navigation
    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    // Admin Dashboard Navigation
    private val _isAdminDashboardOpen = MutableStateFlow(false)
    val isAdminDashboardOpen: StateFlow<Boolean> = _isAdminDashboardOpen.asStateFlow()

    // Import Playlist Screen Navigation
    private val _isImportPlaylistOpen = MutableStateFlow(false)
    val isImportPlaylistOpen: StateFlow<Boolean> = _isImportPlaylistOpen.asStateFlow()

    // Player Sheets & Modal States
    private val _isFullPlayerVisible = MutableStateFlow(false)
    val isFullPlayerVisible: StateFlow<Boolean> = _isFullPlayerVisible.asStateFlow()

    private val _isLyricsMode = MutableStateFlow(false)
    val isLyricsMode: StateFlow<Boolean> = _isLyricsMode.asStateFlow()

    private val _isEditingNameSheetVisible = MutableStateFlow(false)
    val isEditingNameSheetVisible: StateFlow<Boolean> = _isEditingNameSheetVisible.asStateFlow()

    private val _isQueueSheetVisible = MutableStateFlow(false)
    val isQueueSheetVisible: StateFlow<Boolean> = _isQueueSheetVisible.asStateFlow()

    private val _activeTrackOptions = MutableStateFlow<Track?>(null)
    val activeTrackOptions: StateFlow<Track?> = _activeTrackOptions.asStateFlow()

    private val _addToPlaylistTrack = MutableStateFlow<Track?>(null)
    val addToPlaylistTrack: StateFlow<Track?> = _addToPlaylistTrack.asStateFlow()

    private val _undoRemovalState = MutableStateFlow<RemovedTrackInfo?>(null)
    val undoRemovalState: StateFlow<RemovedTrackInfo?> = _undoRemovalState.asStateFlow()
    private var undoExpirationJob: kotlinx.coroutines.Job? = null

    private val _isCreatePlaylistSheetVisible = MutableStateFlow(false)
    val isCreatePlaylistSheetVisible: StateFlow<Boolean> = _isCreatePlaylistSheetVisible.asStateFlow()

    private val _isCreateRoomSheetVisible = MutableStateFlow(false)
    val isCreateRoomSheetVisible: StateFlow<Boolean> = _isCreateRoomSheetVisible.asStateFlow()

    private val _isJoinRoomSheetVisible = MutableStateFlow(false)
    val isJoinRoomSheetVisible: StateFlow<Boolean> = _isJoinRoomSheetVisible.asStateFlow()

    // Transient feedback messages (e.g. Added to queue)
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()
    private var messageClearJob: kotlinx.coroutines.Job? = null

    fun showUserMessage(message: String) {
        _userMessage.value = message
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            kotlinx.coroutines.delay(2200L)
            if (_userMessage.value == message) {
                _userMessage.value = null
            }
        }
    }

    fun clearUserMessage() {
        messageClearJob?.cancel()
        _userMessage.value = null
    }

    private val _joinErrorMessage = MutableStateFlow<String?>(null)
    val joinErrorMessage: StateFlow<String?> = _joinErrorMessage.asStateFlow()

    // Quick Mix filter on Home
    private val _selectedQuickMix = MutableStateFlow<String?>(null)
    val selectedQuickMix: StateFlow<String?> = _selectedQuickMix.asStateFlow()

    // Search State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow("All")
    val selectedSearchFilter: StateFlow<String> = _selectedSearchFilter.asStateFlow()

    private val _recentSearches = MutableStateFlow(listOf("Arijit Singh", "The Weeknd", "Diljit Dosanjh", "Trending Hits"))
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val musicSource: com.example.service.MusicSource = com.example.service.FlinsMusicSource(repository)
    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _unifiedSearchResult = MutableStateFlow(UnifiedSearchResult())
    val unifiedSearchResult: StateFlow<UnifiedSearchResult> = _unifiedSearchResult.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private val _selectedSearchSort = MutableStateFlow(SearchSortOrder.RELEVANCE)
    val selectedSearchSort: StateFlow<SearchSortOrder> = _selectedSearchSort.asStateFlow()

    private val _searchErrorMessage = MutableStateFlow<String?>(null)
    val searchErrorMessage: StateFlow<String?> = _searchErrorMessage.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isLoadingMoreSearch = MutableStateFlow(false)
    val isLoadingMoreSearch: StateFlow<Boolean> = _isLoadingMoreSearch.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    // YouTube Music Account Management (SimpMusic Pattern)
    private val _ytAccounts = MutableStateFlow<List<com.example.service.integrations.ytmusic.YtAccount>>(emptyList())
    val ytAccounts: StateFlow<List<com.example.service.integrations.ytmusic.YtAccount>> = _ytAccounts.asStateFlow()

    private val _activeYtAccount = MutableStateFlow<com.example.service.integrations.ytmusic.YtAccount?>(null)
    val activeYtAccount: StateFlow<com.example.service.integrations.ytmusic.YtAccount?> = _activeYtAccount.asStateFlow()

    private val _isSyncingYtLibrary = MutableStateFlow(false)
    val isSyncingYtLibrary: StateFlow<Boolean> = _isSyncingYtLibrary.asStateFlow()

    // Catalog & Repository Flows (Dynamic Live Feed + Catalog Fallback)
    val homeTracks: StateFlow<List<Track>> = repository.homeTracks
    val homeArtists: StateFlow<List<Artist>> = repository.homeArtists
    val homeAlbums: StateFlow<List<Album>> = repository.homeAlbums
    val libraryAlbums: StateFlow<List<Album>> = repository.libraryAlbums
    val libraryArtists: StateFlow<List<Artist>> = repository.libraryArtists
    val isLoadingRealData: StateFlow<Boolean> = repository.isLoadingRealData

    val catalogTracks: List<Track> get() = repository.catalogTracks
    val artists: List<Artist> get() = repository.artists
    val albums: List<Album> get() = repository.albums
    val quickMixes: List<QuickMix> get() = repository.quickMixes

    fun getAlbumArtwork(albumId: String): String? {
        if (albumId.isBlank() || albumId == "alb_1" || albumId == "alb_yt") return null
        return repository.getAlbumById(albumId)?.displayArtwork
            ?: albums.find { it.id == albumId }?.displayArtwork
    }

    val userProfile: StateFlow<UserEntity> = repository.userProfile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserEntity(name = "Gaurav", visualQuality = "BALANCED")
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val greetingPreferences: StateFlow<UserGreetingPreferences> = repository.currentUserId.flatMapLatest { uid ->
        userPreferencesRepository.getGreetingPreferencesFlow(uid)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = userPreferencesRepository.getGreetingPreferences(repository.currentUserId.value)
    )

    val greetingText: StateFlow<String> = greetingPreferences.map { prefs ->
        prefs.resolveGreeting()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = greetingPreferences.value.resolveGreeting()
    )

    val likedTrackIds: StateFlow<Set<String>> = repository.likedTrackIds.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    val recentlyPlayed: StateFlow<List<Track>> = repository.recentlyPlayedTracks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val playlists: StateFlow<List<Playlist>> = repository.playlists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val allTracks: StateFlow<List<Track>> = repository.allKnownTracks

    // Playback State Flow
    val playbackState: StateFlow<PlaybackUiState> = playbackService.uiState

    // Listen Together State Flow
    val activeRoom: StateFlow<Room?> = realtimeService.activeRoom.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    private val _publicRooms = MutableStateFlow<List<com.example.model.PublicRoomSummary>>(emptyList())
    val publicRooms: StateFlow<List<com.example.model.PublicRoomSummary>> = _publicRooms.asStateFlow()

    private val _isRefreshingPublicRooms = MutableStateFlow(false)
    val isRefreshingPublicRooms: StateFlow<Boolean> = _isRefreshingPublicRooms.asStateFlow()

    private var isProcessingRemoteSync = false
    private var lastProcessedEventTimestamp: Long = 0L
    private var lastProcessedSequenceNumber: Long = 0L
    private var lastRequestedSyncTrackId: String? = null
    private var lastPeriodicSyncPosition: Long = 0L

    init {
        realtimeService.onMemberJoinedListener = { _ ->
            if (activeRoom.value != null && realtimeService.isHost() && !isProcessingRemoteSync) {
                syncRoomPlayback(action = "sync")
            }
        }

        viewModelScope.launch {
            activeRoom.collect { room ->
                playbackService.isRoomParticipantSlaved = (room != null && !realtimeService.isHost())
                if (room != null) {
                    isProcessingRemoteSync = true
                    try {
                        handleParticipantSync(room)
                    } finally {
                        isProcessingRemoteSync = false
                    }
                } else {
                    lastProcessedEventTimestamp = 0L
                    lastProcessedSequenceNumber = 0L
                    lastRequestedSyncTrackId = null
                }
            }
        }

        viewModelScope.launch {
            playbackService.uiState.collect { state ->
                if (activeRoom.value != null) {
                    if (realtimeService.isHost() && state.isPlaying && !isProcessingRemoteSync) {
                        val currentPos = state.currentPositionMs
                        if (kotlin.math.abs(currentPos - lastPeriodicSyncPosition) >= 4000L) {
                            lastPeriodicSyncPosition = currentPos
                            syncRoomPlayback(action = "sync")
                        }
                    }

                    val status = when (state.status) {
                        com.example.service.PlayerStatus.BUFFERING,
                        com.example.service.PlayerStatus.EXTRACTING,
                        com.example.service.PlayerStatus.CONNECTING,
                        com.example.service.PlayerStatus.LOADING -> com.example.model.MemberStatus.BUFFERING
                        com.example.service.PlayerStatus.PLAYING -> com.example.model.MemberStatus.ONLINE
                        com.example.service.PlayerStatus.ERROR, com.example.service.PlayerStatus.UNAVAILABLE -> com.example.model.MemberStatus.RECONNECTING
                        else -> com.example.model.MemberStatus.ONLINE
                    }
                    realtimeService.updateMemberStatus(status)
                }
            }
        }
    }

    private fun handleParticipantSync(room: Room) {
        // Drop stale events if an event with a newer sequence number or newer timestamp was already processed
        if (room.sequenceNumber > 0L && room.sequenceNumber < lastProcessedSequenceNumber) {
            return
        }
        if (room.sequenceNumber > 0L && room.sequenceNumber == lastProcessedSequenceNumber && room.lastEventTimestamp > 0L && room.lastEventTimestamp <= lastProcessedEventTimestamp) {
            return
        }
        if (room.lastEventTimestamp > 0L && room.lastEventTimestamp < lastProcessedEventTimestamp && (room.sequenceNumber == 0L || room.sequenceNumber <= lastProcessedSequenceNumber)) {
            return
        }
        if (room.sequenceNumber > 0L) {
            lastProcessedSequenceNumber = maxOf(lastProcessedSequenceNumber, room.sequenceNumber)
        }
        if (room.lastEventTimestamp > 0L) {
            lastProcessedEventTimestamp = maxOf(lastProcessedEventTimestamp, room.lastEventTimestamp)
        }

        val targetTrackId = room.currentTrackId
        val currentTrack = playbackService.uiState.value.currentTrack

        // 1. Synchronize track change
        if (!targetTrackId.isNullOrBlank() && targetTrackId != currentTrack?.id) {
            if (targetTrackId == lastRequestedSyncTrackId) {
                // Track resolution already launched; update seek and play/pause state without restarting
                if (room.playbackPositionMs > 0L) {
                    playbackService.seekTo(room.playbackPositionMs)
                }
                if (room.isPlaying != playbackService.uiState.value.isPlaying) {
                    if (room.isPlaying) playbackService.resume() else playbackService.pause()
                }
                return
            }

            lastRequestedSyncTrackId = targetTrackId
            val trackToPlay = repository.getTrackById(targetTrackId)
                ?: room.currentTrack
                ?: Track(
                    id = targetTrackId,
                    title = room.currentTrack?.title ?: "Shared Track",
                    artist = room.currentTrack?.artist ?: "Together Session",
                    artistId = "art_together",
                    album = "Shared",
                    albumId = "alb_together",
                    artwork = room.currentTrack?.artwork ?: "",
                    durationSeconds = room.currentTrack?.durationSeconds ?: 180,
                    audioUrl = null
                )

            repository.registerTrack(trackToPlay)
            playbackService.playTrack(trackToPlay)

            if (room.playbackPositionMs > 0L) {
                playbackService.seekTo(room.playbackPositionMs)
            }
            if (!room.isPlaying) {
                playbackService.pause()
            }
            return
        }

        // 2. Synchronize play/pause state
        if (room.isPlaying != playbackService.uiState.value.isPlaying && currentTrack != null) {
            if (room.isPlaying) {
                playbackService.resume()
            } else {
                playbackService.pause()
            }
        }

        // 3. Synchronize playback position
        if (currentTrack != null) {
            val localPos = playbackService.uiState.value.currentPositionMs
            val drift = kotlin.math.abs(localPos - room.playbackPositionMs)
            if (drift > 2000L) {
                playbackService.seekTo(room.playbackPositionMs)
            }
        }
    }

    init {
        playbackService.setOnTrackPlayedListener { trackId ->
            viewModelScope.launch {
                val current = playbackService.uiState.value.currentTrack
                if (current != null && current.id == trackId) {
                    repository.registerTrack(current)
                }
                repository.recordRecentlyPlayed(trackId)
                if (activeRoom.value != null && realtimeService.isHost() && !isProcessingRemoteSync && trackId != lastRequestedSyncTrackId) {
                    syncRoomPlayback(action = "track_change")
                }
            }
        }
        // Trigger real online music feed loading asynchronously (non-blocking, keeps catalog fallback)
        viewModelScope.launch {
            repository.loadRealMusicFeed(com.example.service.integrations.LiveYouTubeService())
        }
        // Restore saved playback queue after startup safely
        viewModelScope.launch {
            kotlinx.coroutines.delay(200L)
            playbackService.restoreSavedQueue { ids ->
                val all = repository.getAllKnownTracks()
                ids.mapNotNull { id -> all.find { it.id == id } }
            }
        }

        // Restore saved YouTube Music accounts (SimpMusic pattern)
        com.example.FlinsApplication.instance?.let { app ->
            val accountManager = com.example.service.integrations.ytmusic.YtAccountManager.getInstance(app)
            _ytAccounts.value = accountManager.accounts.value
            _activeYtAccount.value = accountManager.activeAccount.value
        }

        // Automatic synchronized lyrics background fetcher for active playback
        viewModelScope.launch {
            playbackService.uiState
                .map { it.currentTrack }
                .distinctUntilChangedBy { it?.id }
                .collect { track ->
                    if (track != null && track.lyrics.isEmpty()) {
                        val app = com.example.FlinsApplication.instance
                        if (app != null) {
                            try {
                                val lyrics = com.example.service.lyrics.LyricsRepository.getInstance(app).getLyricsForTrack(track)
                                if (lyrics.isNotEmpty()) {
                                    playbackService.updateCurrentTrackLyrics(track.id, lyrics)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
        }
    }

    fun startRadio(track: Track) {
        viewModelScope.launch {
            try {
                showUserMessage("Starting YouTube Music radio for ${track.title}...")
                val res = com.example.service.integrations.ytmusic.YtMusicService.getInstance().getRadioTracks(track.id)
                val radioTracks = res.getOrNull()?.map { it.toTrack() } ?: emptyList()
                if (radioTracks.isNotEmpty()) {
                    repository.registerTracks(radioTracks)
                    playbackService.playTrack(track, listOf(track) + radioTracks)
                    showUserMessage("Radio started with ${radioTracks.size} similar tracks")
                } else {
                    playbackService.playTrack(track, listOf(track))
                }
            } catch (e: Exception) {
                playbackService.playTrack(track, listOf(track))
            }
        }
    }

    fun completeSplash() {
        _isSplashCompleted.value = true
    }

    fun selectTab(tab: MainTab) {
        _currentTab.value = tab
        _subScreenStack.value = emptyList()
        _subScreenStack.value = emptyList()
        _selectedArtist.value = null
        _selectedAlbum.value = null
        _selectedPlaylistId.value = null
    }

    // Sub-Screen navigation
    fun navigateToArtist(artist: Artist) {
        val cached = repository.getArtistById(artist.id)
            ?: repository.artists.find { it.name.equals(artist.name, ignoreCase = true) }
        val target = if (cached != null && (cached.tracks.isNotEmpty() || cached.albums.isNotEmpty())) cached else artist
        _subScreenStack.update { it + SubScreenDestination.ArtistScreen(target) }
        _selectedArtist.value = target
        _isFullPlayerVisible.value = false
        if (target.id.isNotBlank()) {
            viewModelScope.launch {
                val fullArtist = repository.fetchYtArtist(target.id, target.name)
                if (fullArtist != null) {
                    _selectedArtist.value = fullArtist
                    _subScreenStack.update { stack ->
                        stack.map { dest ->
                            if (dest is SubScreenDestination.ArtistScreen &&
                                (dest.artist.id == target.id || dest.artist.name.equals(target.name, ignoreCase = true))
                            ) {
                                SubScreenDestination.ArtistScreen(fullArtist)
                            } else dest
                        }
                    }
                }
            }
        }
    }

    fun navigateToArtistByName(name: String, artistId: String = "") {
        if (name.isBlank()) return
        _isFullPlayerVisible.value = false
        val allArtists = repository.artists
        val existing = allArtists.find { it.name.equals(name, ignoreCase = true) }
            ?: (if (artistId.isNotBlank()) allArtists.find { it.id == artistId } else null)
        val targetArtist = existing ?: Artist(
            id = if (artistId.isNotBlank()) artistId else "art_${name.lowercase().replace(" ", "_")}",
            name = name,
            artwork = ""
        )
        navigateToArtist(targetArtist)
    }

    private fun isGenericAlbumTitle(title: String): Boolean {
        val lower = title.trim().lowercase()
        return lower.isBlank() || lower == "unknown album" || lower == "single" || lower == "ep" || lower == "album"
    }

    fun navigateToAlbum(album: Album) {
        val cached = repository.getAlbumById(album.id)
            ?: (if (!isGenericAlbumTitle(album.title)) repository.albums.find { it.title.equals(album.title, ignoreCase = true) && (album.artist.isBlank() || it.artist.equals(album.artist, ignoreCase = true)) } else null)
        val isCachedValid = cached != null && cached.tracks.isNotEmpty() &&
                            cached.title.isNotBlank() && cached.title != "Unknown Album" &&
                            cached.artist.isNotBlank() && cached.artist != "Various Artists"
        val target = if (isCachedValid) cached!! else album
        _subScreenStack.update { it + SubScreenDestination.AlbumScreen(target) }
        _selectedAlbum.value = target
        _isFullPlayerVisible.value = false
        if (target.id.isNotBlank() || target.title.isNotBlank()) {
            viewModelScope.launch {
                val fullAlbum = repository.fetchYtAlbum(target.id, target.title, fallbackAlbum = target)
                if (fullAlbum != null) {
                    _selectedAlbum.value = fullAlbum
                    _subScreenStack.update { stack ->
                        stack.map { dest ->
                            if (dest is SubScreenDestination.AlbumScreen &&
                                (dest.album.id == target.id || dest.album.title.equals(target.title, ignoreCase = true))
                            ) {
                                SubScreenDestination.AlbumScreen(fullAlbum)
                            } else dest
                        }
                    }
                }
            }
        }
    }

    fun navigateToAlbumByTitle(title: String, artist: String = "", albumId: String = "") {
        if (title.isBlank()) return
        _isFullPlayerVisible.value = false
        val allAlbums = repository.albums
        val existing = (if (albumId.isNotBlank() && !albumId.startsWith("alb_")) allAlbums.find { it.id == albumId } else null)
            ?: (if (!isGenericAlbumTitle(title)) allAlbums.find { it.title.equals(title, ignoreCase = true) && (artist.isBlank() || it.artist.equals(artist, ignoreCase = true)) } else null)
        val targetAlbum = existing ?: Album(
            id = if (albumId.isNotBlank()) albumId else "alb_${title.lowercase().replace(" ", "_")}",
            title = title,
            artist = artist,
            artwork = ""
        )
        navigateToAlbum(targetAlbum)
    }

    fun navigateToPlaylist(playlist: Playlist) {
        _subScreenStack.update { it + SubScreenDestination.PlaylistScreen(playlist.id) }
        _selectedPlaylistId.value = playlist.id
        _selectedPlaylistOverride.value = playlist
        if (playlist.id.isNotBlank()) {
            viewModelScope.launch {
                val fullPl = repository.fetchYtPlaylist(playlist.id, forceRefresh = true)
                if (fullPl != null && _selectedPlaylistId.value == playlist.id) {
                    _selectedPlaylistOverride.value = fullPl
                }
            }
        }
    }

    fun refreshPlaylist(playlistId: String) {
        viewModelScope.launch {
            val fullPl = repository.fetchYtPlaylist(playlistId, forceRefresh = true)
            if (fullPl != null && _selectedPlaylistId.value == playlistId) {
                _selectedPlaylistOverride.value = fullPl
                showUserMessage("Playlist refreshed")
            }
        }
    }

    fun openImportPlaylistScreen() {
        _isImportPlaylistOpen.value = true
    }

    fun closeImportPlaylistScreen() {
        _isImportPlaylistOpen.value = false
    }

    fun navigateBack() {
        when {
            _isAdminDashboardOpen.value -> _isAdminDashboardOpen.value = false
            _isSettingsOpen.value -> _isSettingsOpen.value = false
            _isImportPlaylistOpen.value -> _isImportPlaylistOpen.value = false
            _subScreenStack.value.isNotEmpty() -> {
                val next = _subScreenStack.value.dropLast(1)
                _subScreenStack.value = next
                val top = next.lastOrNull()
                _selectedArtist.value = (top as? SubScreenDestination.ArtistScreen)?.artist
                _selectedAlbum.value = (top as? SubScreenDestination.AlbumScreen)?.album
                _selectedPlaylistId.value = (top as? SubScreenDestination.PlaylistScreen)?.playlistId
                if (_selectedPlaylistId.value == null) {
                    _selectedPlaylistOverride.value = null
                }
            }
            _selectedArtist.value != null -> _selectedArtist.value = null
            _selectedAlbum.value != null -> _selectedAlbum.value = null
            _selectedPlaylistId.value != null -> {
                _selectedPlaylistId.value = null
                _selectedPlaylistOverride.value = null
            }
            _isFullPlayerVisible.value -> _isFullPlayerVisible.value = false
            else -> {}
        }
    }

    // Quick mix filter
    fun toggleQuickMix(mixId: String) {
        _selectedQuickMix.update { current ->
            if (current == mixId) null else mixId
        }
    }

    // Playback controls
    fun playTrack(track: Track, queue: List<Track> = emptyList()) {
        val currentRoom = activeRoom.value
        if (currentRoom != null && !realtimeService.isHost()) {
            showUserMessage("Only the party host can change tracks")
            return
        }
        lastRequestedSyncTrackId = null
        repository.registerTrack(track)
        if (queue.isNotEmpty()) repository.registerTracks(queue)
        playbackService.playTrack(track, queue)
        syncRoomPlayback(action = "track_change")
    }

    fun playTrackAtIndex(index: Int) {
        val currentRoom = activeRoom.value
        if (currentRoom != null && !realtimeService.isHost()) {
            showUserMessage("Only the party host can change tracks")
            return
        }
        lastRequestedSyncTrackId = null
        playbackService.playTrackAtIndex(index, isUserInitiated = true)
        syncRoomPlayback(action = "track_change")
    }

    fun togglePlayPause() {
        val currentRoom = activeRoom.value
        if (currentRoom != null && !realtimeService.isHost()) {
            showUserMessage("Only the party host controls playback")
            return
        }
        val wasPlaying = playbackService.uiState.value.isPlaying
        playbackService.togglePlayPause()
        syncRoomPlayback(action = if (wasPlaying) "pause" else "play")
    }

    fun retryCurrentTrack() = playbackService.retryCurrentTrack()

    fun seekTo(positionMs: Long) {
        val currentRoom = activeRoom.value
        if (currentRoom != null && !realtimeService.isHost()) {
            showUserMessage("Only the party host controls playback position")
            return
        }
        playbackService.seekTo(positionMs)
        syncRoomPlayback(action = "seek", explicitPosition = positionMs)
    }

    fun next() {
        val currentRoom = activeRoom.value
        if (currentRoom != null && !realtimeService.isHost()) {
            showUserMessage("Only the party host can skip tracks")
            return
        }
        playbackService.next()
        syncRoomPlayback(action = "track_change")
    }

    fun previous() {
        val currentRoom = activeRoom.value
        if (currentRoom != null && !realtimeService.isHost()) {
            showUserMessage("Only the party host can change tracks")
            return
        }
        playbackService.previous()
        syncRoomPlayback(action = "track_change")
    }

    private fun syncRoomPlayback(action: String = "sync", explicitPosition: Long? = null) {
        if (isProcessingRemoteSync) return
        val room = activeRoom.value ?: return
        if (!realtimeService.isHost()) return

        val state = playbackService.uiState.value
        val track = state.currentTrack ?: return
        if (action == "track_change" && track.id == lastRequestedSyncTrackId) {
            // Echo loop prevention: do not echo back track changes received from remote room state
            return
        }
        val pos = explicitPosition ?: state.currentPositionMs
        viewModelScope.launch {
            realtimeService.syncPlayback(
                com.example.service.integrations.RemotePlaybackState(
                    trackId = track.id,
                    position = pos,
                    playing = state.isPlaying,
                    track = track,
                    action = action
                )
            )
        }
    }
    fun toggleShuffle() = playbackService.toggleShuffle()
    fun toggleAutoplay() = playbackService.toggleAutoplay()
    fun toggleRepeat() = playbackService.toggleRepeat()
    fun setPlaybackSpeed(speed: Float) = playbackService.setPlaybackSpeed(speed)
    fun setSleepTimer(minutes: Int) = playbackService.setSleepTimer(minutes)
    fun cancelSleepTimer() = playbackService.cancelSleepTimer()
    fun setSponsorBlockEnabled(enabled: Boolean) = playbackService.setSponsorBlockEnabled(enabled)
    fun openAudioEqualizer(context: Context) = playbackService.openAudioEqualizer(context)
    fun playNext(track: Track) {
        repository.registerTrack(track)
        playbackService.playNext(track)
        showUserMessage("Will play next: ${track.title}")
    }
    fun addToQueue(track: Track) {
        repository.registerTrack(track)
        playbackService.addToQueue(track)
        showUserMessage("Added to queue: ${track.title}")
    }
    fun removeFromQueue(index: Int) = playbackService.removeFromQueue(index)
    fun reorderQueue(fromIndex: Int, toIndex: Int) = playbackService.reorderQueue(fromIndex, toIndex)
    fun clearQueue() {
        playbackService.clearQueue()
        showUserMessage("Queue cleared")
    }
    fun getAllTracks(): List<Track> = repository.getAllKnownTracks()

    // Full Player & Sheet visibility
    fun openFullPlayer() { _isFullPlayerVisible.value = true }
    fun closeFullPlayer() { _isFullPlayerVisible.value = false }
    fun toggleLyricsMode() { _isLyricsMode.update { !it } }
    fun openEditNameSheet() { _isEditingNameSheetVisible.value = true }
    fun closeEditNameSheet() { _isEditingNameSheetVisible.value = false }
    fun openQueueSheet() { _isQueueSheetVisible.value = true }
    fun closeQueueSheet() { _isQueueSheetVisible.value = false }
    fun openTrackOptions(track: Track) { _activeTrackOptions.value = track }
    fun closeTrackOptions() { _activeTrackOptions.value = null }
    fun openCreatePlaylistSheet() { _isCreatePlaylistSheetVisible.value = true }
    fun closeCreatePlaylistSheet() { _isCreatePlaylistSheetVisible.value = false }
    fun openCreateRoomSheet() { _isCreateRoomSheetVisible.value = true }
    fun closeCreateRoomSheet() { _isCreateRoomSheetVisible.value = false }
    fun openJoinRoomSheet() {
        _joinErrorMessage.value = null
        _isJoinRoomSheetVisible.value = true
    }
    fun closeJoinRoomSheet() { _isJoinRoomSheetVisible.value = false }

    fun setCurrentUserId(userId: String?) {
        val previousUserId = repository.currentUserId.value
        repository.setCurrentUserId(userId)
        if (userId.isNullOrBlank()) {
            stopAndClearPlayback()
        } else if (userId != previousUserId && userId.startsWith("yt_")) {
            // New YouTube Music account signed in or switched!
            // Automatically sync their YouTube Music cloud library (liked songs, playlists, history)
            // and refresh the home music feed with personalized InnerTube recommendations.
            syncYouTubeMusicLibrary()
            refreshHomeFeed(force = true)
        }
    }

    fun refreshHomeFeed(force: Boolean = false) {
        viewModelScope.launch {
            repository.loadRealMusicFeed(
                youTubeService = com.example.service.integrations.LiveYouTubeService(),
                force = force
            )
        }
    }

    fun stopAndClearPlayback() {
        playbackService.stopAndClearPlayback()
        _isFullPlayerVisible.value = false
        _isQueueSheetVisible.value = false
        _activeTrackOptions.value = null
        _addToPlaylistTrack.value = null
        _subScreenStack.value = emptyList()
        _selectedArtist.value = null
        _selectedAlbum.value = null
        _selectedPlaylistId.value = null
    }

    // User Profile Actions
    fun updateUserName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val uid = repository.currentUserId.value
            userPreferencesRepository.saveDisplayName(uid, trimmed)
            repository.updateUserName(trimmed)
            showUserMessage("Greeting preference saved")
            closeEditNameSheet()
        }
    }

    fun updateCustomGreeting(greeting: String) {
        val trimmed = greeting.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val uid = repository.currentUserId.value
            userPreferencesRepository.saveCustomGreeting(uid, trimmed)
            showUserMessage("Custom greeting saved")
            closeEditNameSheet()
        }
    }

    fun resetGreetingToDefault() {
        viewModelScope.launch {
            val uid = repository.currentUserId.value
            userPreferencesRepository.resetToDefault(uid)
            repository.updateUserName(UserGreetingPreferences.DEFAULT_NAME)
            showUserMessage("Default name restored")
            closeEditNameSheet()
        }
    }

    fun checkForUpdates(force: Boolean = true) {
        if (appUpdateManager != null) {
            appUpdateManager.checkForUpdates(force = force)
        } else {
            showUserMessage("Update manager unavailable")
        }
    }

    fun updateVisualQuality(quality: VisualQuality) {
        viewModelScope.launch {
            repository.updateVisualQuality(quality)
        }
    }

    // Like Action
    fun toggleLike(trackId: String) {
        viewModelScope.launch {
            repository.toggleLike(trackId)
        }
    }

    // Playlist Actions (YouTube Music is the single authoritative source of truth)
    fun createPlaylist(name: String, description: String = "", artwork: String = "", privacyStatus: String = "PRIVATE") {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            showUserMessage("Playlist name cannot be empty")
            return
        }
        viewModelScope.launch {
            showUserMessage("Creating playlist on YouTube Music...")
            val result = repository.createPlaylistResult(trimmed, description.trim(), artwork.trim(), privacyStatus)
            if (result.isSuccess) {
                showUserMessage("Created '$trimmed' on YouTube Music")
                closeCreatePlaylistSheet()
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Could not create playlist"
                showUserMessage(msg)
            }
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val result = repository.renamePlaylistResult(playlistId, trimmed)
            if (result.isSuccess) {
                showUserMessage("Renamed to '$trimmed'")
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Could not rename playlist"
                showUserMessage(msg)
            }
        }
    }

    fun updatePlaylist(playlistId: String, name: String, description: String, artwork: String = "") {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        viewModelScope.launch {
            val result = repository.updatePlaylistResult(playlistId, trimmedName, description.trim(), artwork.trim())
            if (result.isSuccess) {
                showUserMessage("Playlist updated on YouTube Music")
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Could not update playlist"
                showUserMessage(msg)
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            showUserMessage("Deleting playlist from YouTube Music...")
            val result = repository.deletePlaylistResult(playlistId)
            if (result.isSuccess) {
                if (_selectedPlaylistId.value == playlistId) {
                    _selectedPlaylistId.value = null
                    _selectedPlaylistOverride.value = null
                    navigateBack()
                }
                showUserMessage("Playlist deleted from YouTube Music")
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Could not delete playlist"
                showUserMessage(msg)
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, track: Track, position: Int? = null) {
        val pos = position ?: try {
            (selectedPlaylist.value?.takeIf { it.id == playlistId } ?: playlists.value.find { it.id == playlistId })
                ?.trackIds?.indexOf(track.id)?.takeIf { it >= 0 } ?: 0
        } catch (_: Exception) { 0 }

        _undoRemovalState.value = RemovedTrackInfo(playlistId, track, pos)
        showUserMessage("Removing '${track.title}'...")

        undoExpirationJob?.cancel()
        undoExpirationJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5000L)
            if (_undoRemovalState.value?.track?.id == track.id) {
                _undoRemovalState.value = null
            }
        }

        viewModelScope.launch {
            val result = repository.removeTrackFromPlaylistResult(playlistId, track.id)
            if (result.isSuccess) {
                showUserMessage("Removed '${track.title}' from playlist")
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Could not remove song"
                showUserMessage(msg)
            }
        }
    }

    fun removeMultipleTracksFromPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch {
            val result = repository.removeTracksFromPlaylistResult(playlistId, trackIds)
            _undoRemovalState.value = null
            undoExpirationJob?.cancel()
            if (result.isSuccess) {
                val count = trackIds.size
                showUserMessage("Removed $count ${if (count == 1) "song" else "songs"} from playlist")
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Could not remove songs"
                showUserMessage(msg)
            }
        }
    }

    fun reorderPlaylistTracks(playlistId: String, trackIds: List<String>) {
        viewModelScope.launch {
            repository.reorderPlaylistTracks(playlistId, trackIds)
        }
    }

    fun undoRemoveTrack() {
        val info = _undoRemovalState.value
        if (info == null) {
            showUserMessage("Undo is no longer available")
            return
        }
        _undoRemovalState.value = null
        undoExpirationJob?.cancel()
        viewModelScope.launch {
            val restored = repository.restoreTrackToPlaylist(info.playlistId, info.track, info.position)
            if (restored) {
                showUserMessage("Restored '${info.track.title}'")
            } else {
                showUserMessage("Could not restore song")
            }
        }
    }

    fun dismissUndo() {
        _undoRemovalState.value = null
        undoExpirationJob?.cancel()
    }

    fun getPlaylistTracks(playlistId: String): Flow<List<Track>> {
        return repository.getPlaylistTracks(playlistId)
    }

    fun openAddToPlaylist(track: Track) {
        _addToPlaylistTrack.value = track
        _activeTrackOptions.value = null
        viewModelScope.launch {
            repository.fetchUserPlaylists()
        }
    }

    fun closeAddToPlaylist() {
        _addToPlaylistTrack.value = null
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            val plName = playlists.value.find { it.id == playlistId }?.name ?: "playlist"
            showUserMessage("Adding to $plName...")
            val result = repository.addTrackToPlaylistResult(playlistId, track)
            if (result.isSuccess) {
                showUserMessage("Added to $plName")
            } else {
                val err = result.exceptionOrNull()
                if (err is IllegalStateException && err.message?.contains("already in playlist", ignoreCase = true) == true) {
                    showUserMessage("Already in $plName")
                } else {
                    val msg = err?.message ?: "Could not add to playlist"
                    showUserMessage(msg)
                }
            }
            closeAddToPlaylist()
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val track = repository.getTrackById(trackId)
        if (track != null) {
            addTrackToPlaylist(playlistId, track)
        } else {
            viewModelScope.launch {
                val plName = playlists.value.find { it.id == playlistId }?.name ?: "playlist"
                showUserMessage("Adding to $plName...")
                val result = repository.addTrackToPlaylistResult(playlistId, trackId)
                if (result.isSuccess) {
                    showUserMessage("Added to $plName")
                } else {
                    val err = result.exceptionOrNull()
                    if (err is IllegalStateException && err.message?.contains("already in playlist", ignoreCase = true) == true) {
                        showUserMessage("Already in $plName")
                    } else {
                        val msg = err?.message ?: "Could not add to playlist"
                        showUserMessage(msg)
                    }
                }
                closeAddToPlaylist()
            }
        }
    }

    fun createPlaylistAndAddTrack(name: String, description: String = "", track: Track, privacyStatus: String = "PRIVATE") {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            showUserMessage("Playlist name cannot be empty")
            return
        }
        viewModelScope.launch {
            showUserMessage("Creating playlist on YouTube Music...")
            val createRes = repository.createPlaylistResult(trimmed, description.trim(), privacyStatus = privacyStatus)
            if (createRes.isSuccess) {
                val newId = createRes.getOrThrow()
                val addRes = repository.addTrackToPlaylistResult(newId, track)
                if (addRes.isSuccess) {
                    showUserMessage("Created '$trimmed' and added '${track.title}'")
                } else {
                    showUserMessage("Created '$trimmed', but could not add song")
                }
            } else {
                val msg = createRes.exceptionOrNull()?.message ?: "Could not create playlist"
                showUserMessage(msg)
            }
            closeAddToPlaylist()
        }
    }

    fun createPlaylistAndAddTrack(name: String, description: String = "", trackId: String, privacyStatus: String = "PRIVATE") {
        val track = repository.getTrackById(trackId)
        if (track != null) {
            createPlaylistAndAddTrack(name, description, track, privacyStatus)
        } else {
            val trimmed = name.trim()
            if (trimmed.isBlank()) {
                showUserMessage("Playlist name cannot be empty")
                return
            }
            viewModelScope.launch {
                showUserMessage("Creating playlist on YouTube Music...")
                val createRes = repository.createPlaylistResult(trimmed, description.trim(), privacyStatus = privacyStatus)
                if (createRes.isSuccess) {
                    val newId = createRes.getOrThrow()
                    val addRes = repository.addTrackToPlaylistResult(newId, trackId)
                    if (addRes.isSuccess) {
                        showUserMessage("Created '$trimmed' and added track")
                    } else {
                        showUserMessage("Created '$trimmed', but could not add song")
                    }
                } else {
                    val msg = createRes.exceptionOrNull()?.message ?: "Could not create playlist"
                    showUserMessage(msg)
                }
                closeAddToPlaylist()
            }
        }
    }

    // Search Actions
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _unifiedSearchResult.value = UnifiedSearchResult()
            _searchSuggestions.value = emptyList()
            _searchErrorMessage.value = null
            _isSearching.value = false
        } else {
            // Immediate suggestions preview
            _searchSuggestions.value = searchEngine.generateSuggestions(
                query = query,
                recentSearches = _recentSearches.value
            )
            executeSearch(query, _selectedSearchFilter.value, isFilterChange = false)
        }
    }

    fun setSearchFilter(filter: String) {
        if (_selectedSearchFilter.value == filter) return
        _selectedSearchFilter.value = filter
        val currentQuery = _searchQuery.value.trim()
        if (currentQuery.isNotBlank()) {
            executeSearch(currentQuery, filter, isFilterChange = true)
        }
    }

    private fun executeSearch(query: String, filter: String, isFilterChange: Boolean) {
        searchJob?.cancel()
        val cacheKey = "${query.trim().lowercase()}_${filter.lowercase()}"
        val cached = searchCache.get(cacheKey)
        if (cached != null) {
            val sortedCached = cached.applySorting(_selectedSearchSort.value)
            _unifiedSearchResult.value = sortedCached
            _searchResults.value = sortedCached.tracks
            _isSearching.value = false
            if (!isFilterChange) return
        }

        searchJob = viewModelScope.launch {
            if (cached == null) {
                _isSearching.value = true
            }
            if (!isFilterChange) {
                kotlinx.coroutines.delay(300L) // 300ms debounce when typing
            }
            try {
                val userId = repository.currentUserId.value.takeIf { it.isNotBlank() } ?: "default"
                val category = SearchCategory.fromLabel(filter)
                val result = searchEngine.search(query, userId, category)
                if (_searchQuery.value != query || _selectedSearchFilter.value != filter) return@launch

                searchCache.put(cacheKey, result)
                val sortedResult = result.applySorting(_selectedSearchSort.value)
                _unifiedSearchResult.value = sortedResult
                _searchResults.value = sortedResult.tracks
                _searchSuggestions.value = searchEngine.generateSuggestions(
                    query = query,
                    tracks = result.tracks,
                    artists = result.artists,
                    albums = result.albums,
                    recentSearches = _recentSearches.value
                )
                _searchErrorMessage.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_unifiedSearchResult.value.isEmpty && _searchQuery.value == query) {
                    _searchErrorMessage.value = e.localizedMessage ?: "Failed to complete search. Tap retry."
                }
            } finally {
                if (_searchQuery.value == query && _selectedSearchFilter.value == filter) {
                    _isSearching.value = false
                }
            }
        }
    }

    fun loadMoreSearchResults() {
        val currentToken = _unifiedSearchResult.value.continuationToken
        if (currentToken.isNullOrBlank() || _isSearching.value || _isLoadingMoreSearch.value) return
        val currentQuery = _searchQuery.value
        val currentFilter = _selectedSearchFilter.value

        viewModelScope.launch {
            _isLoadingMoreSearch.value = true
            try {
                val category = SearchCategory.fromLabel(currentFilter)
                val pageResult = searchEngine.searchContinuation(currentToken, category)
                if (_searchQuery.value != currentQuery || _selectedSearchFilter.value != currentFilter) return@launch

                val current = _unifiedSearchResult.value
                val combinedTracks = (current.tracks + pageResult.tracks).distinctBy { it.id }
                val combinedArtists = (current.artists + pageResult.artists).distinctBy { it.id }
                val combinedAlbums = (current.albums + pageResult.albums).distinctBy { it.id }
                val combinedPlaylists = (current.playlists + pageResult.playlists).distinctBy { it.id }

                val updated = current.copy(
                    tracks = combinedTracks,
                    artists = combinedArtists,
                    albums = combinedAlbums,
                    playlists = combinedPlaylists,
                    continuationToken = pageResult.continuationToken
                )
                _unifiedSearchResult.value = updated
                _searchResults.value = combinedTracks
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Failed to load more search results", e)
            } finally {
                _isLoadingMoreSearch.value = false
            }
        }
    }

    fun setSearchSort(sort: SearchSortOrder) {
        _selectedSearchSort.value = sort
        _unifiedSearchResult.update { it.applySorting(sort) }
        _searchResults.value = _unifiedSearchResult.value.tracks
    }

    fun setSearchSort(sortLabel: String) {
        setSearchSort(SearchSortOrder.fromLabel(sortLabel))
    }

    fun addRecentSearch(query: String) {
        if (query.isNotBlank()) {
            val userId = repository.currentUserId.value.takeIf { it.isNotBlank() } ?: "default"
            searchHistoryRepository.addRecentSearch(userId, query)
            _recentSearches.value = searchHistoryRepository.getRecentSearches(userId)
        }
    }

    fun removeRecentSearch(query: String) {
        val userId = repository.currentUserId.value.takeIf { it.isNotBlank() } ?: "default"
        searchHistoryRepository.removeRecentSearch(userId, query)
        _recentSearches.value = searchHistoryRepository.getRecentSearches(userId)
    }

    fun clearRecentSearches() {
        val userId = repository.currentUserId.value.takeIf { it.isNotBlank() } ?: "default"
        searchHistoryRepository.clearRecentSearches(userId)
        _recentSearches.value = emptyList()
    }

    fun retrySearch() {
        val currentQuery = _searchQuery.value
        if (currentQuery.isNotBlank()) {
            onSearchQueryChanged(currentQuery)
        }
    }

    fun addYouTubeAccount(name: String, email: String, cookie: String, avatarUrl: String = "") {
        val account = com.example.service.integrations.ytmusic.YtAccount(
            id = "yt_acc_${System.currentTimeMillis()}",
            name = name,
            email = email,
            cookie = cookie,
            avatarUrl = avatarUrl,
            isActive = true
        )
        val current = _ytAccounts.value.toMutableList()
        current.removeAll { it.email == email && email.isNotBlank() }
        current.add(account)
        val mapped = current.map { it.copy(isActive = it.id == account.id) }
        _ytAccounts.value = mapped
        _activeYtAccount.value = account
        com.example.FlinsApplication.instance?.let { app ->
            com.example.service.integrations.ytmusic.YtAccountManager.getInstance(app).addOrUpdateAccount(account)
        }
        showUserMessage("Linked YouTube Music: $name")
        syncYouTubeMusicLibrary()
    }

    fun switchYouTubeAccount(accountId: String) {
        val current = _ytAccounts.value
        val target = current.firstOrNull { it.id == accountId } ?: return
        val mapped = current.map { it.copy(isActive = it.id == target.id) }
        _ytAccounts.value = mapped
        _activeYtAccount.value = target.copy(isActive = true)
        com.example.FlinsApplication.instance?.let { app ->
            com.example.service.integrations.ytmusic.YtAccountManager.getInstance(app).switchAccount(accountId)
        }
        showUserMessage("Switched to ${target.name}")
        syncYouTubeMusicLibrary()
    }

    fun removeYouTubeAccount(accountId: String) {
        val current = _ytAccounts.value.toMutableList()
        current.removeAll { it.id == accountId }
        val nextActive = current.firstOrNull()?.copy(isActive = true)
        _ytAccounts.value = current.map { it.copy(isActive = it.id == nextActive?.id) }
        _activeYtAccount.value = nextActive
        com.example.FlinsApplication.instance?.let { app ->
            com.example.service.integrations.ytmusic.YtAccountManager.getInstance(app).removeAccount(accountId)
        }
        showUserMessage("Account removed")
    }

    fun addYouTubeAccountFromCookie(cookie: String, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val app = com.example.FlinsApplication.instance
            if (app == null) {
                onResult?.invoke(false, "Application context unavailable")
                return@launch
            }
            val mgr = com.example.service.integrations.ytmusic.YtAccountManager.getInstance(app)
            val result = mgr.addAccountFromCookie(cookie)
            if (result.isSuccess) {
                val acc = result.getOrThrow()
                _ytAccounts.value = mgr.accounts.value
                _activeYtAccount.value = acc
                showUserMessage("Linked YouTube Music: ${acc.name}")
                syncYouTubeMusicLibrary()
                onResult?.invoke(true, acc.name)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Authentication failed"
                showUserMessage("Sign-in failed: $err")
                onResult?.invoke(false, err)
            }
        }
    }

    fun syncYouTubeMusicLibrary() {
        if (_isSyncingYtLibrary.value) return
        viewModelScope.launch {
            _isSyncingYtLibrary.value = true
            try {
                val service = com.example.service.integrations.ytmusic.YtMusicService.getInstance()
                val targetUserId = repository.currentUserId.value.takeIf { it.isNotBlank() } ?: return@launch
                val result = repository.syncYouTubeMusicLibrary(service, targetUserId)
                if (result.isSuccess) {
                    val count = result.getOrNull() ?: 0
                    if (count > 0) {
                        showUserMessage("Synced $count tracks from YouTube Music")
                    } else {
                        showUserMessage("YouTube Music library up to date")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "syncYouTubeMusicLibrary failed: ${e.message}")
            } finally {
                _isSyncingYtLibrary.value = false
            }
        }
    }

    // Listen Together Actions
    fun createRoom(name: String, isPublic: Boolean) {
        viewModelScope.launch {
            val hostName = userProfile.value.name
            val result = realtimeService.createRoom(name, isPublic, hostName)
            if (result.isSuccess) {
                closeCreateRoomSheet()
            }
        }
    }

    private var joinRoomJob: kotlinx.coroutines.Job? = null

    fun joinRoom(code: String) {
        if (joinRoomJob?.isActive == true) return
        joinRoomJob = viewModelScope.launch {
            val participantName = userProfile.value.name
            val result = realtimeService.joinRoom(code, participantName)
            if (result.isSuccess) {
                _joinErrorMessage.value = null
                closeJoinRoomSheet()
            } else {
                _joinErrorMessage.value = result.exceptionOrNull()?.message ?: "Room not found. Check the code and try again."
            }
        }
    }

    fun leaveRoom() {
        android.util.Log.i("MainViewModel", "[TOGETHER_DIAG] Explicit leaveRoom triggered by user")
        viewModelScope.launch {
            realtimeService.leaveRoom()
        }
    }

    fun endRoom() {
        android.util.Log.i("MainViewModel", "[TOGETHER_DIAG] Host deliberate endRoom triggered by user")
        viewModelScope.launch {
            realtimeService.endRoom()
            showUserMessage("Party ended for everyone")
        }
    }

    fun addTrackToRoomQueue(track: Track) {
        viewModelScope.launch {
            repository.registerTrack(track)
            val res = realtimeService.addToQueue(track)
            if (res.isSuccess) {
                showUserMessage("Added to party queue: ${track.title}")
            }
        }
    }

    fun removeTrackFromRoomQueue(opId: String) {
        viewModelScope.launch {
            realtimeService.removeFromQueue(opId)
        }
    }

    fun reorderRoomQueue(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            realtimeService.reorderQueue(fromIndex, toIndex)
        }
    }

    fun sendChatMessage(text: String, shareCurrentTrack: Boolean = false) {
        viewModelScope.launch {
            val trackToShare = if (shareCurrentTrack) playbackService.uiState.value.currentTrack else null
            val res = realtimeService.sendChatMessage(text, trackToShare)
            if (res.isFailure) {
                val errorMsg = res.exceptionOrNull()?.message ?: "Failed to send chat message"
                showUserMessage(errorMsg)
            }
        }
    }

    fun sendReaction(type: String) {
        viewModelScope.launch {
            realtimeService.sendReaction(type)
        }
    }

    fun refreshPublicRooms(query: String = "") {
        viewModelScope.launch {
            _isRefreshingPublicRooms.value = true
            val result = realtimeService.getPublicRooms(query)
            if (result.isSuccess) {
                _publicRooms.value = result.getOrDefault(emptyList())
            }
            _isRefreshingPublicRooms.value = false
        }
    }

    fun createRoomAdvanced(
        name: String,
        description: String = "",
        visibility: String = "PUBLIC",
        coverUrl: String = "",
        maxParticipants: Int = 20
    ) {
        viewModelScope.launch {
            val hostName = userProfile.value.name
            val result = realtimeService.createRoomAdvanced(
                name = name,
                description = description,
                visibility = visibility,
                coverUrl = coverUrl,
                maxParticipants = maxParticipants,
                hostName = hostName
            )
            if (result.isSuccess) {
                closeCreateRoomSheet()
                showUserMessage("Room created: ${result.getOrNull()?.name}")
            } else {
                showUserMessage(result.exceptionOrNull()?.message ?: "Failed to create room")
            }
        }
    }

    fun transferRoomHost(targetUserId: String) {
        viewModelScope.launch {
            val res = realtimeService.transferHost(targetUserId)
            if (res.isSuccess) {
                showUserMessage("Host privileges transferred")
            } else {
                showUserMessage(res.exceptionOrNull()?.message ?: "Failed to transfer host")
            }
        }
    }

    fun kickRoomParticipant(targetUserId: String) {
        viewModelScope.launch {
            val res = realtimeService.kickParticipant(targetUserId)
            if (res.isSuccess) {
                showUserMessage("Participant removed from room")
            } else {
                showUserMessage(res.exceptionOrNull()?.message ?: "Failed to remove participant")
            }
        }
    }

    fun toggleRoomLock() {
        val room = activeRoom.value ?: return
        val newLock = !room.isLocked
        viewModelScope.launch {
            val res = realtimeService.setRoomLock(newLock)
            if (res.isSuccess) {
                showUserMessage(if (newLock) "Room locked: new participants cannot join" else "Room unlocked")
            }
        }
    }

    fun toggleChatMute() {
        val room = activeRoom.value ?: return
        val newMute = !room.isChatMuted
        viewModelScope.launch {
            val res = realtimeService.setChatMuted(newMute)
            if (res.isSuccess) {
                showUserMessage(if (newMute) "Chat muted for participants" else "Chat unmuted")
            }
        }
    }

    fun deleteRoomChatMessage(messageId: String) {
        viewModelScope.launch {
            realtimeService.deleteChatMessage(messageId)
        }
    }

    fun submitSongRequest(track: Track) {
        viewModelScope.launch {
            val res = realtimeService.submitSongRequest(track)
            if (res.isSuccess) {
                showUserMessage("Song requested: ${track.title}")
            } else {
                showUserMessage(res.exceptionOrNull()?.message ?: "Failed to request song")
            }
        }
    }

    fun reviewSongRequest(requestId: String, approved: Boolean) {
        viewModelScope.launch {
            val res = realtimeService.reviewSongRequest(requestId, approved)
            if (res.isSuccess) {
                showUserMessage(if (approved) "Song request approved" else "Song request rejected")
            }
        }
    }

    fun setRoomPlaybackModes(repeatMode: String, shuffleMode: Boolean) {
        viewModelScope.launch {
            realtimeService.setPlaybackModes(repeatMode, shuffleMode)
        }
    }

    fun isRoomHost(): Boolean = realtimeService.isHost()

    fun nextInRoomOrLocal() {
        val room = activeRoom.value
        if (room != null && room.sharedQueue.isNotEmpty()) {
            val nextQueueItem = room.sharedQueue.first()
            playTrack(nextQueueItem.track)
            viewModelScope.launch {
                realtimeService.advanceQueue()
            }
        } else {
            next()
        }
    }

    fun selectTrackInRoom(track: Track, opId: String? = null) {
        android.util.Log.i("MainViewModel", "[TOGETHER_DIAG] selectTrackInRoom requested: trackId=${track.id}, opId=$opId")
        viewModelScope.launch {
            repository.registerTrack(track)
            playbackService.playTrack(track)
            lastRequestedSyncTrackId = track.id
            realtimeService.changeTrack(track, opId)
        }
    }


    // Settings Navigation & Preferences
    fun openAdminDashboard() {
        _isAdminDashboardOpen.value = true
    }

    fun closeAdminDashboard() {
        _isAdminDashboardOpen.value = false
    }

    fun openSettings() {
        _isSettingsOpen.value = true
    }

    fun closeSettings() {
        _isSettingsOpen.value = false
    }

    fun updateThemeMode(mode: String) {
        viewModelScope.launch {
            repository.updateThemeMode(mode)
        }
    }

    fun clearAppCache() {
        repository.clearCache()
    }
}
