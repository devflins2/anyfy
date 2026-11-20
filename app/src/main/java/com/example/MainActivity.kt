package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.AppDatabase
import com.example.data.repository.MusicRepository
import com.example.model.Album
import com.example.model.Artist
import com.example.model.VisualQuality
import com.example.service.PlaybackService
import com.example.service.MusicPlayerService
import android.content.ServiceConnection
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.os.Build
import com.example.ui.components.AddToPlaylistBottomSheet
import com.example.ui.components.CreatePlaylistBottomSheet
import com.example.ui.components.EditNameBottomSheet
import com.example.ui.components.LoadingSkeleton
import com.example.ui.components.QueueBottomSheet
import com.example.ui.components.TrackOptionsBottomSheet
import com.example.ui.glass.LiquidGlassBackground
import com.example.ui.navigation.GlassTabBar
import com.example.ui.screens.album.AlbumScreen
import com.example.ui.screens.artist.ArtistScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.login.LoginScreen
import com.example.ui.screens.player.FullPlayerScreen
import com.example.ui.screens.player.MiniPlayer
import com.example.ui.screens.playlist.PlaylistDetailScreen
import com.example.ui.screens.search.SearchScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.splash.SplashScreen
import com.example.ui.screens.together.CreateRoomBottomSheet
import com.example.ui.screens.together.JoinRoomBottomSheet
import com.example.ui.screens.together.TogetherMiniSessionBar
import com.example.ui.screens.together.TogetherScreen
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsMusicTheme
import com.example.ui.theme.FlinsTheme
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import com.example.viewmodel.AuthState
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.MainTab
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private var musicPlayerService: MusicPlayerService? = null
    private var isBound = false
    private var mainViewModel: MainViewModel? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlayerService.MusicBinder
            val s = binder.getService()
            musicPlayerService = s
            isBound = true
            s.setPlaybackService(PlaybackService.getInstance(applicationContext))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicPlayerService = null
            isBound = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if ((uri.scheme == "anyfy" || uri.scheme == "flinsmusic") && uri.host == "together") {
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrBlank()) {
                mainViewModel?.joinRoom(code)
                mainViewModel?.selectTab(MainTab.TOGETHER)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkNotificationPermission()
        val db = AppDatabase.getInstance(applicationContext)
        val userPrefsRepo = com.example.data.repository.PersistentUserPreferencesRepository.getInstance(applicationContext)
        val repository = MusicRepository(db.musicDao(), userPrefsRepo)
        val updateManager = com.example.service.update.AppUpdateManager.getInstance(applicationContext)
        val playbackService = PlaybackService.getInstance(applicationContext)
        val serverConfigRepo = com.example.data.repository.ServerConfigRepository.getInstance(applicationContext)
        val realtimeService = com.example.service.integrations.DynamicSocketIoRealtimeService(
            configRepository = serverConfigRepo,
            persistentUserIdProvider = {
                val app = applicationContext
                val ytAccount = com.example.service.integrations.ytmusic.YtAccountManager.getInstance(app).activeAccount.value
                if (ytAccount != null && ytAccount.id.isNotBlank()) {
                    "usr_yt_${ytAccount.id.takeLast(12)}"
                } else {
                    val prefs = app.getSharedPreferences("flinsmusic_user_identity", Context.MODE_PRIVATE)
                    var uid = prefs.getString("together_user_id", null)
                    if (uid.isNullOrBlank()) {
                        uid = "usr_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                        prefs.edit().putString("together_user_id", uid).apply()
                    }
                    uid
                }
            }
        )

        // Bind and start MusicPlayerService
        Intent(this, MusicPlayerService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            try {
                startService(intent)
            } catch (_: Exception) {}
        }

        setContent {
            FlinsMusicTheme {
                val authViewModel: AuthViewModel = viewModel {
                    AuthViewModel(playbackService = playbackService)
                }
                val viewModel: MainViewModel = viewModel {
                    MainViewModel(
                        repository = repository,
                        playbackService = playbackService,
                        realtimeService = realtimeService,
                        userPreferencesRepository = userPrefsRepo,
                        appUpdateManager = updateManager
                    )
                }
                mainViewModel = viewModel

                // Automatic Update Check on app start & onResume
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            updateManager.checkForUpdates(force = false)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                LaunchedEffect(Unit) {
                    handleDeepLink(intent)
                    updateManager.checkForUpdates(force = false)
                }

                FlinsAuthGate(
                    authViewModel = authViewModel,
                    mainViewModel = viewModel
                )

                // Global In-App Update Dialog (Rendered at top-level regardless of auth state once splash completes or when downloading/verifying)
                val updateStatus by updateManager.updateStatus.collectAsState()
                val isSplashCompleted by viewModel.isSplashCompleted.collectAsState()
                val showUpdateDialog = when (val s = updateStatus) {
                    is com.example.service.update.UpdateStatus.UpdateAvailable -> isSplashCompleted && (!s.isDismissed || s.isMandatory)
                    is com.example.service.update.UpdateStatus.Downloading -> true
                    is com.example.service.update.UpdateStatus.Verifying -> true
                    is com.example.service.update.UpdateStatus.ReadyToInstall -> true
                    is com.example.service.update.UpdateStatus.PermissionRequired -> true
                    is com.example.service.update.UpdateStatus.Failed -> s.manifest != null && s.canRetry
                    else -> false
                }

                if (showUpdateDialog) {
                    com.example.ui.components.UpdateDialog(
                        status = updateStatus,
                        onUpdateNow = { manifest -> updateManager.startDownload(manifest) },
                        onInstallNow = { updateManager.installUpdate(this@MainActivity) },
                        onRetry = { updateManager.startDownload() },
                        onDismiss = { updateManager.dismissUpdate() }
                    )
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            musicPlayerService?.updateNotificationForCurrentState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

/**
 * Top-level auth and navigation gate.
 * Shows Animated SplashScreen upon cold start, then transitions smoothly
 * to either MainAppContent (if signed in) or LoginScreen (if signed out).
 * Once completed, the splash screen is never replayed when returning from background.
 */
@Composable
fun FlinsAuthGate(authViewModel: AuthViewModel, mainViewModel: MainViewModel) {
    val authState by authViewModel.authState.collectAsState()
    val isSigningIn by authViewModel.isSigningIn.collectAsState()
    val errorMessage by authViewModel.errorMessage.collectAsState()
    val isSplashCompleted by mainViewModel.isSplashCompleted.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(authState) {
        val currentUserId = when (val state = authState) {
            is AuthState.SignedIn -> "yt_${state.account.id}"
            else -> ""
        }
        mainViewModel.setCurrentUserId(currentUserId)
    }

    if (!isSplashCompleted) {
        SplashScreen(
            onSplashFinished = { mainViewModel.completeSplash() }
        )
    } else {
        when (authState) {
            is AuthState.SignedIn -> {
                FlinsMusicApp(viewModel = mainViewModel, authViewModel = authViewModel)
            }
            is AuthState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(FlinsDarkBg),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFBDBDBD),
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 2.5.dp
                    )
                }
            }
            is AuthState.SignedOut -> {
                LoginScreen(
                    isSigningIn = isSigningIn,
                    errorMessage = errorMessage,
                    onSignInWithCookie = { cookie, onFinished ->
                        authViewModel.signInWithCookie(cookie, onFinished)
                    },
                    onErrorDismiss = { authViewModel.clearError() }
                )
            }
        }
    }
}

@Composable
fun FlinsMusicApp(viewModel: MainViewModel, authViewModel: AuthViewModel) {
    MainAppContent(viewModel = viewModel, authViewModel = authViewModel)
}

@Composable
fun MainAppContent(viewModel: MainViewModel, authViewModel: AuthViewModel) {
    val context = LocalContext.current
    val currentTab by viewModel.currentTab.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val likedTrackIds by viewModel.likedTrackIds.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val activeRoom by viewModel.activeRoom.collectAsState()
    val publicRooms by viewModel.publicRooms.collectAsState()
    val isRefreshingPublicRooms by viewModel.isRefreshingPublicRooms.collectAsState()
    val currentSubScreen by viewModel.currentSubScreen.collectAsState()
    val selectedArtist by viewModel.selectedArtist.collectAsState()
    val selectedAlbum by viewModel.selectedAlbum.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val isFullPlayerVisible by viewModel.isFullPlayerVisible.collectAsState()
    val isLyricsMode by viewModel.isLyricsMode.collectAsState()
    val isEditingNameVisible by viewModel.isEditingNameSheetVisible.collectAsState()
    val isQueueVisible by viewModel.isQueueSheetVisible.collectAsState()
    val activeTrackOptions by viewModel.activeTrackOptions.collectAsState()
    val addToPlaylistTrack by viewModel.addToPlaylistTrack.collectAsState()
    val undoRemovalState by viewModel.undoRemovalState.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val isCreatePlaylistVisible by viewModel.isCreatePlaylistSheetVisible.collectAsState()
    val isCreateRoomVisible by viewModel.isCreateRoomSheetVisible.collectAsState()
    val isJoinRoomVisible by viewModel.isJoinRoomSheetVisible.collectAsState()
    val joinErrorMessage by viewModel.joinErrorMessage.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
    val isAdminDashboardOpen by viewModel.isAdminDashboardOpen.collectAsState()
    val isImportPlaylistOpen by viewModel.isImportPlaylistOpen.collectAsState()
    val homeTracks by viewModel.homeTracks.collectAsState()
    val homeArtists by viewModel.homeArtists.collectAsState()
    val homeAlbums by viewModel.homeAlbums.collectAsState()
    val libraryAlbums by viewModel.libraryAlbums.collectAsState()
    val libraryArtists by viewModel.libraryArtists.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchFilter by viewModel.selectedSearchFilter.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val selectedQuickMix by viewModel.selectedQuickMix.collectAsState()
    val ytAccounts by viewModel.ytAccounts.collectAsState()
    val activeYtAccount by viewModel.activeYtAccount.collectAsState()
    val isSyncingYtLibrary by viewModel.isSyncingYtLibrary.collectAsState()

    val currentQuality = remember(userProfile.visualQuality) {
        try {
            VisualQuality.valueOf(userProfile.visualQuality)
        } catch (_: Exception) {
            VisualQuality.BALANCED
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FlinsDarkBg)
    ) {
        // Living Liquid Glass Environment (Active application backdrop; paused when FullPlayer is covering the screen)
        if (!isFullPlayerVisible) {
            LiquidGlassBackground(
                primaryColorHex = playbackState.currentTrack?.paletteColorHex ?: "#00F0FF",
                isPlaying = playbackState.isPlaying,
                bassPulse = playbackState.bassPulse,
                beatDeformation = playbackState.beatDeformation,
                energyLevel = playbackState.energyLevel,
                quality = currentQuality
            )
        }

        val isAnyBackHandled = isFullPlayerVisible || isAdminDashboardOpen || isSettingsOpen || isImportPlaylistOpen || currentSubScreen != null
        BackHandler(enabled = isAnyBackHandled) {
            viewModel.navigateBack()
        }

        // Sub-screen Navigation Layer (Admin Dashboard, Settings, Artist, Album, Playlist Detail)
        when {
            isAdminDashboardOpen -> {
                val userEmail = activeYtAccount?.email?.takeIf { it.isNotBlank() } ?: activeYtAccount?.name
                val isAdmin = com.example.viewmodel.AdminDashboardViewModel.isUserAdmin(userEmail)

                if (!isAdmin) {
                    // Block unauthorized navigation into the dashboard immediately
                    viewModel.closeAdminDashboard()
                } else {
                    val serverConfigRepo = remember { com.example.data.repository.ServerConfigRepository.getInstance(context) }
                    val adminViewModel: com.example.viewmodel.AdminDashboardViewModel = viewModel {
                        com.example.viewmodel.AdminDashboardViewModel(serverConfigRepo)
                    }
                    com.example.ui.screens.admin.AdminDashboardScreen(
                        viewModel = adminViewModel,
                        onBack = { viewModel.closeAdminDashboard() }
                    )
                }
            }
            isSettingsOpen -> {
                val userEmail = activeYtAccount?.email?.takeIf { it.isNotBlank() } ?: activeYtAccount?.name
                val isAdmin = com.example.viewmodel.AdminDashboardViewModel.isUserAdmin(userEmail)
                SettingsScreen(
                    userProfile = userProfile,
                    userEmail = userEmail,
                    isAdmin = isAdmin,
                    onBack = { viewModel.closeSettings() },
                    onEditNameClick = { viewModel.openEditNameSheet() },
                    onVisualQualityChange = { viewModel.updateVisualQuality(it) },
                    onThemeModeChange = { viewModel.updateThemeMode(it) },
                    onClearCacheClick = { viewModel.clearAppCache() },
                    onSignOutClick = {
                        viewModel.closeSettings()
                        viewModel.stopAndClearPlayback()
                        authViewModel.signOut()
                    },
                    onOpenAdminDashboard = {
                        if (isAdmin) {
                            viewModel.openAdminDashboard()
                        }
                    },
                    ytAccounts = ytAccounts,
                    activeYtAccount = activeYtAccount,
                    isSyncingYtLibrary = isSyncingYtLibrary,
                    onAddYouTubeAccount = { cookie, onFinished ->
                        viewModel.addYouTubeAccountFromCookie(cookie, onFinished)
                    },
                    onSwitchYouTubeAccount = { id ->
                        viewModel.switchYouTubeAccount(id)
                    },
                    onRemoveYouTubeAccount = { id ->
                        viewModel.removeYouTubeAccount(id)
                    },
                    onSyncYouTubeLibrary = {
                        viewModel.syncYouTubeMusicLibrary()
                    },
                    isSponsorBlockEnabled = playbackState.isSponsorBlockEnabled,
                    onToggleSponsorBlock = { enabled ->
                        viewModel.setSponsorBlockEnabled(enabled)
                    },
                    onOpenEqualizer = {
                        viewModel.openAudioEqualizer(context)
                    },
                    onCheckForUpdates = {
                        viewModel.checkForUpdates(force = true)
                    }
                )
            }
            isImportPlaylistOpen || currentSubScreen is com.example.viewmodel.SubScreenDestination.ImportPlaylistScreen -> {
                val importViewModel: com.example.viewmodel.PlaylistImportViewModel = viewModel {
                    com.example.viewmodel.PlaylistImportViewModel(viewModel.repository)
                }
                val currentUserId = (authViewModel.authState.value as? com.example.viewmodel.AuthState.SignedIn)
                    ?.account?.id?.takeIf { it.isNotBlank() }?.let { "yt_$it" } ?: ""
                val navBarBottomInset = WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding()
                val hasMiniPlayer = playbackState.currentTrack != null && !isFullPlayerVisible && !isSettingsOpen && !isAdminDashboardOpen
                val bottomStackHeight = 76.dp + navBarBottomInset + (if (hasMiniPlayer) 72.dp else 0.dp)

                com.example.ui.screens.importer.ImportPlaylistScreen(
                    viewModel = importViewModel,
                    userId = currentUserId,
                    onBack = {
                        importViewModel.cancelImport()
                        viewModel.closeImportPlaylistScreen()
                    },
                    onNavigateToPlaylist = { plId ->
                        importViewModel.reset()
                        viewModel.closeImportPlaylistScreen()
                        val pl = playlists.find { it.id == plId }
                        if (pl != null) {
                            viewModel.navigateToPlaylist(pl)
                        }
                    },
                    bottomBarHeight = bottomStackHeight
                )
            }
            currentSubScreen != null -> {
                when (val sub = currentSubScreen) {
                    is com.example.viewmodel.SubScreenDestination.ArtistScreen -> {
                        ArtistScreen(
                            artist = sub.artist,
                            allTracks = viewModel.getAllTracks(),
                            allAlbums = if (homeAlbums.isNotEmpty()) homeAlbums else viewModel.albums,
                            allArtists = if (homeArtists.isNotEmpty()) homeArtists else viewModel.artists,
                            likedTrackIds = likedTrackIds,
                            currentPlayingTrack = playbackState.currentTrack,
                            onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                            onLikeToggle = { trackId -> viewModel.toggleLike(trackId) },
                            onTrackOptionsClick = { track -> viewModel.openTrackOptions(track) },
                            onAlbumClick = { album -> viewModel.navigateToAlbum(album) },
                            onRelatedArtistClick = { artist -> viewModel.navigateToArtist(artist) },
                            onBack = { viewModel.navigateBack() }
                        )
                    }
                    is com.example.viewmodel.SubScreenDestination.AlbumScreen -> {
                        AlbumScreen(
                            album = sub.album,
                            allTracks = allTracks,
                            likedTrackIds = likedTrackIds,
                            currentPlayingTrack = playbackState.currentTrack,
                            onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                            onLikeToggle = { trackId -> viewModel.toggleLike(trackId) },
                            onTrackOptionsClick = { track -> viewModel.openTrackOptions(track) },
                            onArtistClick = { artistName -> viewModel.navigateToArtistByName(artistName) },
                            onBack = { viewModel.navigateBack() }
                        )
                    }
                    is com.example.viewmodel.SubScreenDestination.PlaylistScreen -> {
                        val playlist = playlists.find { it.id == sub.playlistId }
                        if (playlist != null) {
                            PlaylistDetailScreen(
                                playlist = playlist,
                                allTracks = allTracks,
                                likedTrackIds = likedTrackIds,
                                currentPlayingTrack = playbackState.currentTrack,
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onLikeToggle = { trackId -> viewModel.toggleLike(trackId) },
                                onTrackOptionsClick = { track -> viewModel.openTrackOptions(track) },
                                onDeletePlaylist = { id -> viewModel.deletePlaylist(id) },
                                onUpdatePlaylist = { id, name, desc, art -> viewModel.updatePlaylist(id, name, desc, art) },
                                onRemoveTrack = { track -> viewModel.removeTrackFromPlaylist(playlist.id, track) },
                                onRemoveMultipleTracks = { trackIds -> viewModel.removeMultipleTracksFromPlaylist(playlist.id, trackIds) },
                                onReorderTracks = { trackIds -> viewModel.reorderPlaylistTracks(playlist.id, trackIds) },
                                onPlayNext = { track -> viewModel.playNext(track) },
                                onAddToQueue = { track -> viewModel.addToQueue(track) },
                                onAddTrackToPlaylist = { plId, track -> viewModel.addTrackToPlaylist(plId, track) },
                                undoRemovalState = undoRemovalState,
                                onUndoRemove = { viewModel.undoRemoveTrack() },
                                onDismissUndo = { viewModel.dismissUndo() },
                                onRefresh = { viewModel.refreshPlaylist(playlist.id) },
                                onArtistClick = { artist -> viewModel.navigateToArtist(artist) },
                                onAlbumClick = { album -> viewModel.navigateToAlbum(album) },
                                onBack = { viewModel.navigateBack() }
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                                LoadingSkeleton(itemCount = 6)
                            }
                        }
                    }
                    is com.example.viewmodel.SubScreenDestination.ImportPlaylistScreen -> {}
                    null -> {}
                }
            }
            else -> {
                // Primary Tab Content
                when (currentTab) {
                    MainTab.HOME -> {
                        val greetingText by viewModel.greetingText.collectAsState()
                        HomeScreen(
                            userName = userProfile.name,
                            visualQuality = currentQuality,
                            tracks = homeTracks,
                            artists = homeArtists,
                            albums = homeAlbums,
                            quickMixes = viewModel.quickMixes,
                            selectedQuickMix = selectedQuickMix,
                            likedTrackIds = likedTrackIds,
                            recentlyPlayed = recentlyPlayed,
                            currentPlayingTrack = playbackState.currentTrack,
                            isPlaying = playbackState.isPlaying,
                            onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                            onLikeToggle = { trackId -> viewModel.toggleLike(trackId) },
                            onTrackOptionsClick = { track -> viewModel.openTrackOptions(track) },
                            onArtistClick = { artist -> viewModel.navigateToArtist(artist) },
                            onAlbumClick = { album -> viewModel.navigateToAlbum(album) },
                            onEditNameClick = { viewModel.openEditNameSheet() },
                            onQuickMixToggle = { mixId -> viewModel.toggleQuickMix(mixId) },
                            onCycleVisualQuality = {
                                val qualities = VisualQuality.entries
                                val nextIndex = (currentQuality.ordinal + 1) % qualities.size
                                viewModel.updateVisualQuality(qualities[nextIndex])
                            },
                            onOpenSettingsClick = { viewModel.openSettings() },
                            onNavigateToLibrary = { viewModel.selectTab(MainTab.LIBRARY) },
                            greetingText = greetingText
                        )
                    }
                    MainTab.SEARCH -> {
                        val searchResults by viewModel.searchResults.collectAsState()
                        val unifiedResult by viewModel.unifiedSearchResult.collectAsState()
                        val searchSuggestions by viewModel.searchSuggestions.collectAsState()
                        val searchSort by viewModel.selectedSearchSort.collectAsState()
                        val searchError by viewModel.searchErrorMessage.collectAsState()
                        val isSearching by viewModel.isSearching.collectAsState()
                        val isLoadingMoreSearch by viewModel.isLoadingMoreSearch.collectAsState()
                        SearchScreen(
                            query = searchQuery,
                            onQueryChange = { viewModel.onSearchQueryChanged(it) },
                            selectedFilter = searchFilter,
                            onFilterChange = { viewModel.setSearchFilter(it) },
                            recentSearches = recentSearches,
                            onSelectRecentSearch = { viewModel.onSearchQueryChanged(it) },
                            onRemoveRecentSearch = { viewModel.removeRecentSearch(it) },
                            onClearRecentSearches = { viewModel.clearRecentSearches() },
                            allTracks = allTracks,
                            allArtists = viewModel.artists,
                            allAlbums = viewModel.albums,
                            likedTrackIds = likedTrackIds,
                            currentPlayingTrack = playbackState.currentTrack,
                            searchResults = if (unifiedResult.tracks.isNotEmpty()) unifiedResult.tracks else searchResults,
                            isSearching = isSearching,
                            searchArtists = unifiedResult.artists,
                            searchAlbums = unifiedResult.albums,
                            searchPlaylists = unifiedResult.playlists,
                            hasMoreResults = !unifiedResult.continuationToken.isNullOrBlank(),
                            isLoadingMore = isLoadingMoreSearch,
                            onLoadMore = { viewModel.loadMoreSearchResults() },
                            onPlaylistClick = { playlist ->
                                viewModel.addRecentSearch(playlist.name)
                                viewModel.navigateToPlaylist(playlist)
                            },
                            selectedSort = searchSort.label,
                            onSortChange = { viewModel.setSearchSort(it) },
                            suggestions = searchSuggestions,
                            errorMessage = searchError,
                            onRetry = { viewModel.retrySearch() },
                            onTrackClick = { track, queue ->
                                viewModel.addRecentSearch(track.title)
                                viewModel.playTrack(track, queue)
                            },
                            onLikeToggle = { trackId -> viewModel.toggleLike(trackId) },
                            onTrackOptionsClick = { track -> viewModel.openTrackOptions(track) },
                            onArtistClick = { artist ->
                                viewModel.addRecentSearch(artist.name)
                                viewModel.navigateToArtist(artist)
                            },
                            onAlbumClick = { album ->
                                viewModel.addRecentSearch(album.title)
                                viewModel.navigateToAlbum(album)
                            }
                        )
                    }
                    MainTab.LIBRARY -> {
                        LibraryScreen(
                            likedTrackIds = likedTrackIds,
                            allTracks = allTracks,
                            playlists = playlists,
                            albums = if (libraryAlbums.isNotEmpty()) libraryAlbums else homeAlbums,
                            artists = if (libraryArtists.isNotEmpty()) libraryArtists else homeArtists,
                            recentlyPlayed = recentlyPlayed,
                            currentPlayingTrack = playbackState.currentTrack,
                            onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                            onLikeToggle = { trackId -> viewModel.toggleLike(trackId) },
                            onTrackOptionsClick = { track -> viewModel.openTrackOptions(track) },
                            onArtistClick = { artist -> viewModel.navigateToArtist(artist) },
                            onAlbumClick = { album -> viewModel.navigateToAlbum(album) },
                            onPlaylistClick = { playlist -> viewModel.navigateToPlaylist(playlist) },
                            onCreatePlaylistClick = { viewModel.openCreatePlaylistSheet() },
                            onImportPlaylistClick = { viewModel.openImportPlaylistScreen() },
                            onDeletePlaylist = { id -> viewModel.deletePlaylist(id) },
                            onSignOut = {
                                viewModel.stopAndClearPlayback()
                                authViewModel.signOut()
                            },
                            onOpenSettingsClick = { viewModel.openSettings() }
                        )
                    }
                    MainTab.TOGETHER -> {
                        TogetherScreen(
                            activeRoom = activeRoom,
                            publicRooms = publicRooms,
                            isRefreshingPublicRooms = isRefreshingPublicRooms,
                            currentTrack = playbackState.currentTrack,
                            queue = playbackState.queue,
                            userName = userProfile.name,
                            isPlaying = playbackState.isPlaying,
                            playbackPositionMs = playbackState.currentPositionMs,
                            durationMs = playbackState.durationMs,
                            isHost = viewModel.isRoomHost(),
                            availableTracks = allTracks,
                            onCreateRoomClick = { viewModel.openCreateRoomSheet() },
                            onJoinRoomClick = { viewModel.openJoinRoomSheet() },
                            onRefreshPublicRooms = { viewModel.refreshPublicRooms() },
                            onJoinDirect = { viewModel.joinRoom(it) },
                            onLeaveRoom = { viewModel.leaveRoom() },
                            onEndRoom = { viewModel.endRoom() },
                            onStartListening = { viewModel.openFullPlayer() },
                            onTogglePlayPause = { viewModel.togglePlayPause() },
                            onNextTrack = { viewModel.nextInRoomOrLocal() },
                            onSeekTo = { viewModel.seekTo(it) },
                            onAddToRoomQueue = { viewModel.addTrackToRoomQueue(it) },
                            onRemoveFromRoomQueue = { viewModel.removeTrackFromRoomQueue(it) },
                            onReorderRoomQueue = { from, to -> viewModel.reorderRoomQueue(from, to) },
                            onSendChatMessage = { text, sharePlaying -> viewModel.sendChatMessage(text, sharePlaying) },
                            onSendReaction = { viewModel.sendReaction(it) },
                            onSelectTrack = { track, opId -> viewModel.selectTrackInRoom(track, opId) },
                            onTransferHost = { viewModel.transferRoomHost(it) },
                            onKickParticipant = { viewModel.kickRoomParticipant(it) },
                            onToggleRoomLock = { viewModel.toggleRoomLock() },
                            onToggleChatMute = { viewModel.toggleChatMute() },
                            onDeleteChatMessage = { viewModel.deleteRoomChatMessage(it) },
                            onSubmitSongRequest = { viewModel.submitSongRequest(it) },
                            onReviewSongRequest = { id, approved -> viewModel.reviewSongRequest(id, approved) },
                            onSetPlaybackModes = { mode, shuffle -> viewModel.setRoomPlaybackModes(mode, shuffle) },
                            onImportPlaylistClick = { viewModel.openImportPlaylistScreen() }
                        )
                    }
                }
            }
        }

        // Floating Bottom Stack: MiniPlayer + GlassTabBar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Persistent Together Mini Session Bar (when in active room but on another tab)
            if (activeRoom != null && currentTab != MainTab.TOGETHER && !isFullPlayerVisible && !isSettingsOpen && !isAdminDashboardOpen) {
                TogetherMiniSessionBar(
                    room = activeRoom!!,
                    onReturnToRoom = { viewModel.selectTab(MainTab.TOGETHER) },
                    onLeaveRoom = { viewModel.leaveRoom() }
                )
            }

            // Floating MiniPlayer (Section 20)
            if (playbackState.currentTrack != null && !isFullPlayerVisible && !isSettingsOpen && !isAdminDashboardOpen) {
                MiniPlayer(
                    currentTrack = playbackState.currentTrack!!,
                    isPlaying = playbackState.isPlaying,
                    currentPositionMs = playbackState.currentPositionMs,
                    durationMs = playbackState.durationMs,
                    playerStatus = playbackState.status,
                    onExpandFullPlayer = { viewModel.openFullPlayer() },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onNext = { viewModel.next() },
                    onArtistClick = { artistName -> viewModel.navigateToArtistByName(artistName) }
                )
            }

            // Glass Tab Bar (Section 12)
            if (!isFullPlayerVisible && !isSettingsOpen && !isAdminDashboardOpen) {
                GlassTabBar(
                    selectedTab = currentTab,
                    onTabSelected = { viewModel.selectTab(it) }
                )
            }
        }

        // Full Screen Cinematic Player (Section 19)
        AnimatedVisibility(
            visible = isFullPlayerVisible && playbackState.currentTrack != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            playbackState.currentTrack?.let { track ->
                FullPlayerScreen(
                    track = track,
                    isPlaying = playbackState.isPlaying,
                    playerStatus = playbackState.status,
                    errorMessage = playbackState.errorMessage,
                    currentPositionMs = playbackState.currentPositionMs,
                    durationMs = playbackState.durationMs,
                    isShuffle = playbackState.isShuffle,
                    isRepeat = playbackState.isRepeat,
                    repeatMode = playbackState.repeatMode,
                    isLiked = likedTrackIds.contains(track.id),
                    bassPulse = playbackState.bassPulse,
                    beatDeformation = playbackState.beatDeformation,
                    energyLevel = playbackState.energyLevel,
                    visualQuality = currentQuality,
                    isLyricsMode = isLyricsMode,
                    onToggleLyricsMode = { viewModel.toggleLyricsMode() },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onRetry = { viewModel.retryCurrentTrack() },
                    onSeekTo = { pos -> viewModel.seekTo(pos) },
                    onNext = { viewModel.next() },
                    onPrevious = { viewModel.previous() },
                    onToggleShuffle = { viewModel.toggleShuffle() },
                    onToggleRepeat = { viewModel.toggleRepeat() },
                    onToggleLike = { viewModel.toggleLike(track.id) },
                    onOpenQueue = { viewModel.openQueueSheet() },
                    onOpenTrackOptions = { viewModel.openTrackOptions(track) },
                    onClose = { viewModel.closeFullPlayer() },
                    onArtistClick = { artistName -> viewModel.navigateToArtistByName(artistName) },
                    albumArtworkProvider = { albumId -> viewModel.getAlbumArtwork(albumId) }
                )
            }
        }

        // --- Bottom Sheets & Modals ---

        // Edit Name Bottom Sheet (Section 9)
        if (isEditingNameVisible) {
            val greetingPrefs by viewModel.greetingPreferences.collectAsState()
            EditNameBottomSheet(
                currentName = greetingPrefs.displayName,
                currentGreeting = greetingPrefs.customGreeting,
                isCustomGreeting = greetingPrefs.isCustomGreetingMode,
                onSaveName = { newName -> viewModel.updateUserName(newName) },
                onSaveCustomGreeting = { customGreeting -> viewModel.updateCustomGreeting(customGreeting) },
                onRestoreDefault = { viewModel.resetGreetingToDefault() },
                onDismiss = { viewModel.closeEditNameSheet() }
            )
        }

        // Queue Bottom Sheet (Section 21)
        if (isQueueVisible) {
            QueueBottomSheet(
                currentTrack = playbackState.currentTrack,
                currentTrackIndex = playbackState.currentTrackIndex,
                queue = playbackState.queue,
                isPlaying = playbackState.isPlaying,
                onSelectTrack = { track ->
                    viewModel.playTrack(track, playbackState.queue)
                    viewModel.closeQueueSheet()
                },
                onSelectTrackIndex = { index, _ ->
                    viewModel.playTrackAtIndex(index)
                    viewModel.closeQueueSheet()
                },
                onRemoveFromQueue = { index -> viewModel.removeFromQueue(index) },
                onReorderQueue = { from, to -> viewModel.reorderQueue(from, to) },
                onClearQueue = { viewModel.clearQueue() },
                onDismiss = { viewModel.closeQueueSheet() }
            )
        }

        // Track Options Bottom Sheet
        if (activeTrackOptions != null) {
            val track = activeTrackOptions!!
            TrackOptionsBottomSheet(
                track = track,
                isLiked = likedTrackIds.contains(track.id),
                onLikeToggle = { viewModel.toggleLike(track.id) },
                onPlayNext = { viewModel.playNext(track) },
                onAddToQueue = { viewModel.addToQueue(track) },
                onAddToPlaylist = {
                    viewModel.openAddToPlaylist(track)
                },
                onStartRadio = { viewModel.startRadio(track) },
                onGoToArtist = {
                    val artist = viewModel.artists.find { it.id == track.artistId || it.name == track.artist }
                        ?: com.example.model.Artist(
                            id = track.artistId.ifBlank { "art_${track.artist.lowercase().replace(" ", "_")}" },
                            name = track.artist,
                            artwork = track.artwork
                        )
                    viewModel.navigateToArtist(artist)
                },
                onGoToAlbum = {
                    val album = viewModel.albums.find { it.id == track.albumId || it.title == track.album }
                        ?: com.example.model.Album(
                            id = track.albumId.ifBlank { "alb_${track.album.lowercase().replace(" ", "_")}" },
                            title = track.album,
                            artist = track.artist,
                            artwork = track.artwork
                        )
                    viewModel.navigateToAlbum(album)
                },
                onDismiss = { viewModel.closeTrackOptions() }
            )
        }

        // Add to Playlist Bottom Sheet (Spotify-style)
        if (addToPlaylistTrack != null) {
            val track = addToPlaylistTrack!!
            AddToPlaylistBottomSheet(
                track = track,
                playlists = playlists,
                onSelectPlaylist = { playlistId ->
                    viewModel.addTrackToPlaylist(playlistId, track)
                },
                onCreateNewPlaylist = { name, desc, privacy ->
                    viewModel.createPlaylistAndAddTrack(name, desc, track, privacyStatus = privacy)
                },
                onDismiss = { viewModel.closeAddToPlaylist() }
            )
        }

        // Create Playlist Bottom Sheet (Section 18)
        if (isCreatePlaylistVisible) {
            CreatePlaylistBottomSheet(
                onCreatePlaylist = { name, desc, privacy ->
                    viewModel.createPlaylist(name, desc, privacyStatus = privacy)
                },
                onDismiss = { viewModel.closeCreatePlaylistSheet() }
            )
        }

        // Create Room Bottom Sheet (Section 24)
        if (isCreateRoomVisible) {
            CreateRoomBottomSheet(
                defaultHostName = userProfile.name,
                onCreateRoom = { name, isPublic ->
                    viewModel.createRoom(name, isPublic)
                },
                onCreateAdvancedRoom = { name, desc, isPublic, maxParticipants ->
                    viewModel.createRoomAdvanced(
                        name = name,
                        description = desc,
                        visibility = if (isPublic) "PUBLIC" else "PRIVATE",
                        maxParticipants = maxParticipants
                    )
                },
                onDismiss = { viewModel.closeCreateRoomSheet() }
            )
        }

        // Join Room Bottom Sheet (Section 26 & 27)
        if (isJoinRoomVisible) {
            JoinRoomBottomSheet(
                errorMessage = joinErrorMessage,
                onJoinRoom = { code ->
                    viewModel.joinRoom(code)
                },
                onDismiss = { viewModel.closeJoinRoomSheet() }
            )
        }

        // In-App Update Feedback Listener
        val updateMgr = viewModel.appUpdateManager
        if (updateMgr != null) {

            LaunchedEffect(updateMgr) {
                updateMgr.updateStatus.collect { status ->
                    if (status is com.example.service.update.UpdateStatus.UpToDate) {
                        viewModel.showUserMessage("Anyfy is up to date (v${status.versionName})")
                    }
                }
            }
        }

        // Floating User Feedback Toast/Banner (Section 21)
        AnimatedVisibility(
            visible = userMessage != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
                .zIndex(100f)
        ) {
            userMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                        .background(Color(0xF0181818))
                        .border(
                            1.dp,
                            Brush.horizontalGradient(listOf(Color(0xFFBDBDBD), Color(0xFF454545))),
                            RoundedCornerShape(FlinsTheme.radii.pill)
                        )
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = msg,
                        style = FlinsTheme.typography.label,
                        color = FlinsTheme.colors.textPrimary
                    )
                }
            }
        }
    }
}
