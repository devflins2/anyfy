package com.example.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.Track
import com.example.model.VisualQuality
import com.example.ui.components.EmptyState
import com.example.ui.components.FluidAudioVisualizer
import com.example.ui.components.GlassIconButton
import com.example.ui.components.VisualizerMode
import com.example.ui.glass.LiquidGlassBackground
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsPink
import com.example.ui.theme.FlinsTheme
import com.example.service.PlayerStatus
import com.example.ui.theme.FlinsWarning
import androidx.compose.material.icons.filled.Refresh
import com.example.ui.theme.FlinsViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    track: Track,
    isPlaying: Boolean,
    playerStatus: PlayerStatus = PlayerStatus.PLAYING,
    errorMessage: String? = null,
    currentPositionMs: Long,
    durationMs: Long,
    isShuffle: Boolean,
    isRepeat: Boolean,
    repeatMode: com.example.service.RepeatMode = if (isRepeat) com.example.service.RepeatMode.ALL else com.example.service.RepeatMode.OFF,
    isLiked: Boolean,
    bassPulse: Float,
    beatDeformation: Float,
    energyLevel: Float,
    visualQuality: VisualQuality,
    isLyricsMode: Boolean,
    onToggleLyricsMode: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onRetry: () -> Unit = {},
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTrackOptions: () -> Unit,
    onClose: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    albumArtworkProvider: ((String) -> String?)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onClose)

    var isSeeking by remember(track.id) { mutableStateOf(false) }
    var seekFraction by remember(track.id) { mutableFloatStateOf(0f) }

    val effectiveDurationMs = if (durationMs > 0) durationMs else (track.durationSeconds * 1000L).coerceAtLeast(1000L)
    val actualFraction = (currentPositionMs.toFloat() / effectiveDurationMs).coerceIn(0f, 1f)
    val displayFraction = if (isSeeking) seekFraction else actualFraction

    // Helper formatter for time
    fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "%d:%02d".format(mins, secs)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlinsDarkBg)
    ) {
        // 1. Dynamic Liquid Glass living reactive environment behind the player (Section 19)
        LiquidGlassBackground(
            primaryColorHex = track.paletteColorHex,
            isPlaying = isPlaying,
            bassPulse = bassPulse,
            beatDeformation = beatDeformation,
            energyLevel = energyLevel,
            quality = visualQuality
        )

        // 2. High-res widescreen banner artwork background with universal 4-tier fallback hierarchy
        val bannerCandidates = remember(track.id, track.bannerArtwork, track.artwork) {
            track.resolveBannerHierarchy(albumArtworkProvider)
        }
        var activeBannerIndex by remember(track.id) { androidx.compose.runtime.mutableIntStateOf(0) }

        if (bannerCandidates.isNotEmpty() && activeBannerIndex in bannerCandidates.indices) {
            val candidate = bannerCandidates[activeBannerIndex]
            androidx.compose.runtime.key(track.id, activeBannerIndex) {
                coil.compose.SubcomposeAsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(candidate.url)
                        .memoryCacheKey("${track.id}_banner_${candidate.level.code}_${candidate.url}")
                        .diskCacheKey("${track.id}_banner_${candidate.level.code}_${candidate.url}")
                        .crossfade(300)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(alpha = 0.28f),
                    onSuccess = {
                        com.example.service.diagnostics.BannerDiagnosticLogger.logBannerLoaded(
                            trackId = track.id,
                            albumId = track.albumId,
                            availableThumbnailCount = bannerCandidates.size,
                            selectedArtworkUrl = candidate.url,
                            success = true,
                            fallbackLevel = candidate.level.name
                        )
                    },
                    onError = { state ->
                        com.example.service.diagnostics.BannerDiagnosticLogger.logBannerLoaded(
                            trackId = track.id,
                            albumId = track.albumId,
                            availableThumbnailCount = bannerCandidates.size,
                            selectedArtworkUrl = candidate.url,
                            success = false,
                            fallbackLevel = candidate.level.name,
                            errorMessage = state.result.throwable.message
                        )
                        if (activeBannerIndex < bannerCandidates.lastIndex) {
                            activeBannerIndex++
                        }
                    }
                )
            }
        }

        // 3. Dark gradient overlay for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x8807070A),
                            Color(0xCC07070A),
                            FlinsDarkBg.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Back, Title, More
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(
                    onClick = onClose,
                    size = 44.dp,
                    testTag = "full_player_collapse"
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "Collapse Player",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PLAYING FROM ENVIRONMENT",
                        style = FlinsTheme.typography.overline,
                        color = FlinsTheme.colors.textMuted
                    )
                    Text(
                        text = track.album,
                        style = FlinsTheme.typography.titleMedium,
                        color = FlinsTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                GlassIconButton(
                    onClick = onOpenTrackOptions,
                    size = 44.dp,
                    testTag = "full_player_more_options"
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Center Content: Artwork OR Synchronized Lyrics (Section 23)
            if (isLyricsMode) {
                // Synced Lyrics View
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 16.dp)
                ) {
                    if (track.lyrics.isEmpty()) {
                        EmptyState(
                            title = "Instrumental Frequency",
                            subtitle = "No words recorded for this soundscape. Enjoy the organic flow.",
                            icon = Icons.Default.FormatQuote
                        )
                    } else {
                        val listState = rememberLazyListState()
                        val hasTimedLyrics = remember(track.lyrics) {
                            track.lyrics.any { it.timeMs >= 0L }
                        }
                        val currentLineIndex = remember(currentPositionMs, track.lyrics, hasTimedLyrics) {
                            if (hasTimedLyrics) {
                                val idx = track.lyrics.indexOfLast { it.timeMs in 0L..currentPositionMs }
                                if (idx >= 0) idx else 0
                            } else {
                                -1
                            }
                        }

                        // Track manual user scroll to pause auto-scrolling for 3 seconds
                        var lastUserScrollTime by remember { mutableLongStateOf(0L) }
                        if (listState.isScrollInProgress) {
                            lastUserScrollTime = System.currentTimeMillis()
                        }

                        LaunchedEffect(currentLineIndex) {
                            if (hasTimedLyrics && currentLineIndex >= 0) {
                                val timeSinceUserScroll = System.currentTimeMillis() - lastUserScrollTime
                                if (timeSinceUserScroll > 3000L) {
                                    listState.animateScrollToItem((currentLineIndex - 2).coerceAtLeast(0))
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            contentPadding = PaddingValues(vertical = 40.dp)
                        ) {
                            itemsIndexed(track.lyrics) { index, line ->
                                val isCurrent = hasTimedLyrics && index == currentLineIndex
                                Text(
                                    text = line.text,
                                    style = if (isCurrent) {
                                        FlinsTheme.typography.hero.copy(fontWeight = FontWeight.Bold)
                                    } else {
                                        FlinsTheme.typography.titleMedium
                                    },
                                    color = if (isCurrent) Color(0xFFF0F0F0) else FlinsTheme.colors.textMuted.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (line.timeMs >= 0L) {
                                                onSeekTo(line.timeMs)
                                            }
                                        }
                                        .padding(horizontal = 8.dp),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            } else {
                // Large Artwork as Primary Visual Anchor (Section 19)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Pulsating Harmonic Radial Visualizer Aura
                    FluidAudioVisualizer(
                        isPlaying = isPlaying,
                        bassPulse = bassPulse,
                        beatDeformation = beatDeformation,
                        energyLevel = energyLevel,
                        primaryColorHex = track.paletteColorHex,
                        bpm = track.bpm,
                        initialMode = VisualizerMode.RADIAL_AURA,
                        modifier = Modifier.size(360.dp)
                    )

                    val artworkShape = RoundedCornerShape(28.dp)
                    Box(
                        modifier = Modifier
                            .size(300.dp)
                            .clip(artworkShape)
                            .background(Color(0xFF202020))
                            .border(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF454545),
                                        Color(0xFF282828),
                                        Color(0xFF383838)
                                    )
                                ),
                                shape = artworkShape
                            )
                    ) {
                        coil.compose.SubcomposeAsyncImage(
                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(track.displayArtwork.ifBlank { track.artwork })
                                .crossfade(true)
                                .build(),
                            contentDescription = "${track.title} cover",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                val cleanVid = track.id.removePrefix("yt_").removePrefix("online_").removePrefix("api_").trim()
                                if (cleanVid.length == 11 && !cleanVid.startsWith("PL") && !cleanVid.startsWith("UC")) {
                                    coil.compose.SubcomposeAsyncImage(
                                        model = "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg",
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.size(64.dp)
                                        )
                                    }
                                }
                            }
                        )
                        // Subtle specular internal highlight
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.12f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.35f)
                                        )
                                    )
                                )
                        )
                    }
                }
            }

            // Bottom Player Section (Song Info, Slider, Controls)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                // Song Title, Artist & Like Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = FlinsTheme.typography.hero.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = FlinsTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = track.artist,
                            style = FlinsTheme.typography.titleMedium,
                            color = FlinsTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .testTag("full_player_artist_text")
                                .clickable(
                                    enabled = onArtistClick != null,
                                    indication = ripple(bounded = false, radius = 24.dp, color = Color(0x33FFFFFF)),
                                    interactionSource = null,
                                    onClick = { onArtistClick?.invoke(track.artist) }
                                )
                        )
                        when (playerStatus) {
                            PlayerStatus.EXTRACTING -> {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = Color(0xFFBDBDBD),
                                        strokeWidth = 1.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Extracting audio...",
                                        style = FlinsTheme.typography.caption,
                                        color = Color(0xFFBDBDBD)
                                    )
                                }
                            }
                            PlayerStatus.CONNECTING, PlayerStatus.LOADING -> {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = Color(0xFFBDBDBD),
                                        strokeWidth = 1.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Connecting stream...",
                                        style = FlinsTheme.typography.caption,
                                        color = Color(0xFFBDBDBD)
                                    )
                                }
                            }
                            PlayerStatus.BUFFERING -> {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = Color(0xFFBDBDBD),
                                        strokeWidth = 1.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Buffering audio...",
                                        style = FlinsTheme.typography.caption,
                                        color = Color(0xFFBDBDBD)
                                    )
                                }
                            }
                            PlayerStatus.PLAYING -> {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Playing",
                                    style = FlinsTheme.typography.caption,
                                    color = Color(0xFFBDBDBD)
                                )
                            }
                            PlayerStatus.PAUSED, PlayerStatus.READY -> {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Paused",
                                    style = FlinsTheme.typography.caption,
                                    color = FlinsTheme.colors.textMuted
                                )
                            }
                            PlayerStatus.RECONNECTING -> {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = Color(0xFFA0A0A0),
                                        strokeWidth = 1.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Reconnecting stream...",
                                        style = FlinsTheme.typography.caption,
                                        color = Color(0xFFA0A0A0)
                                    )
                                }
                            }
                            PlayerStatus.UNAVAILABLE, PlayerStatus.ERROR -> {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                        .background(Color(0xFF282828))
                                        .clickable(onClick = onRetry)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Retry",
                                        tint = Color(0xFFD0D0D0),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = errorMessage ?: "Temporarily unavailable • Tap to retry",
                                        style = FlinsTheme.typography.caption,
                                        color = Color(0xFFD0D0D0)
                                    )
                                }
                            }
                            else -> {}
                        }
                    }

                    // Like Button
                    IconButton(
                        onClick = onToggleLike,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("full_player_like_button")
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            tint = if (isLiked) Color(0xFFF0F0F0) else FlinsTheme.colors.textMuted,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Interactive Living Canvas Audio Visualizer (Tap to cycle: Waveform / Bars / Radial)
                FluidAudioVisualizer(
                    isPlaying = isPlaying && playerStatus == PlayerStatus.PLAYING,
                    bassPulse = if (playerStatus == PlayerStatus.PLAYING) bassPulse else 0.05f,
                    beatDeformation = if (playerStatus == PlayerStatus.PLAYING) beatDeformation else 0.02f,
                    energyLevel = if (playerStatus == PlayerStatus.PLAYING) energyLevel else 0.1f,
                    primaryColorHex = track.paletteColorHex,
                    bpm = track.bpm,
                    initialMode = VisualizerMode.WAVE_RIBBON,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Seekbar Progress Slider (Section 19)
                Slider(
                    value = displayFraction,
                    onValueChange = {
                        isSeeking = true
                        seekFraction = it
                    },
                    onValueChangeFinished = {
                        isSeeking = false
                        onSeekTo((seekFraction * effectiveDurationMs).toLong().coerceIn(0L, effectiveDurationMs))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("player_progress_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFBDBDBD),
                        activeTrackColor = Color(0xFFBDBDBD),
                        inactiveTrackColor = Color(0xFF383838)
                    )
                )

                // Current time & Total duration labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val currentTimeMs = if (isSeeking) (seekFraction * effectiveDurationMs).toLong() else currentPositionMs
                    Text(
                        text = formatTime(currentTimeMs),
                        style = FlinsTheme.typography.metadata,
                        color = FlinsTheme.colors.textSecondary
                    )
                    Text(
                        text = formatTime(effectiveDurationMs),
                        style = FlinsTheme.typography.metadata,
                        color = FlinsTheme.colors.textMuted
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Primary Playback Controls: Shuffle, Previous, Play/Pause (Strongest Hierarchy), Next, Repeat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle
                    IconButton(
                        onClick = onToggleShuffle,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffle) Color(0xFFF0F0F0) else FlinsTheme.colors.textMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Previous
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Track",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Play/Pause (STRONGEST HIERARCHY - Section 19)
                    Box(
                        modifier = Modifier
                            .testTag("full_player_play_pause_button")
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFF0F0F0), Color(0xFFD0D0D0))
                                )
                            )
                            .border(
                                width = 1.5.dp,
                                color = Color(0xFF454545),
                                shape = CircleShape
                            )
                            .clickable(onClick = onTogglePlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        if (playerStatus == PlayerStatus.EXTRACTING ||
                            playerStatus == PlayerStatus.CONNECTING ||
                            playerStatus == PlayerStatus.BUFFERING ||
                            playerStatus == PlayerStatus.LOADING
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color(0xFF101010),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying && playerStatus == PlayerStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying && playerStatus == PlayerStatus.PLAYING) "Pause" else "Play",
                                tint = Color(0xFF101010),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    // Next
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Repeat (3-state: OFF, ALL, ONE)
                    IconButton(
                        onClick = onToggleRepeat,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (repeatMode == com.example.service.RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode != com.example.service.RepeatMode.OFF) Color(0xFFF0F0F0) else FlinsTheme.colors.textMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Bottom Utilities: Lyrics Toggle and Queue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Lyrics Toggle (Section 23)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                            .background(if (isLyricsMode) Color(0xFF454545) else Color(0xFF202020))
                            .border(1.dp, if (isLyricsMode) Color(0xFFBDBDBD) else Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.pill))
                            .clickable(onClick = onToggleLyricsMode)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FormatQuote,
                                contentDescription = "Lyrics",
                                tint = if (isLyricsMode) Color(0xFFF0F0F0) else FlinsTheme.colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Lyrics",
                                style = FlinsTheme.typography.label,
                                color = if (isLyricsMode) Color(0xFFF0F0F0) else FlinsTheme.colors.textPrimary
                            )
                        }
                    }

                    // Queue Button (Section 21)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                            .background(Color(0xFF202020))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.pill))
                            .clickable(onClick = onOpenQueue)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Queue",
                                tint = Color(0xFFC0C0C0),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Queue",
                                style = FlinsTheme.typography.label,
                                color = FlinsTheme.colors.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
