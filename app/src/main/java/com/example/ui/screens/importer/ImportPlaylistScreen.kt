package com.example.ui.screens.importer

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.Track
import com.example.service.importer.model.MatchStatus
import com.example.service.importer.model.PlatformType
import com.example.service.importer.model.TrackMatchResult
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassButtonVariant
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconButton
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsError
import com.example.ui.theme.FlinsPink
import com.example.ui.theme.FlinsTheme
import com.example.ui.theme.FlinsViolet
import com.example.viewmodel.ImportStep
import com.example.viewmodel.PlaylistImportViewModel

@Composable
fun ImportPlaylistScreen(
    viewModel: PlaylistImportViewModel,
    userId: String,
    onBack: () -> Unit,
    onNavigateToPlaylist: (playlistId: String) -> Unit,
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val step by viewModel.step.collectAsState()
    val url by viewModel.playlistUrl.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val playlistData by viewModel.extractedPlaylist.collectAsState()
    val matchResults by viewModel.matchResults.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val createdPlaylistId by viewModel.createdPlaylistId.collectAsState()

    var candidatePickerIndex by remember { mutableStateOf<Int?>(null) }

    BackHandler {
        if (step == ImportStep.INPUT || step == ImportStep.SUCCESS || step == ImportStep.ERROR) {
            onBack()
        } else {
            viewModel.cancelImport()
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelImport()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlinsDarkBg)
            .statusBarsPadding()
            .padding(bottom = bottomBarHeight)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Screen Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(
                    onClick = {
                        if (step == ImportStep.INPUT || step == ImportStep.SUCCESS || step == ImportStep.ERROR) {
                            onBack()
                        } else {
                            viewModel.cancelImport()
                            onBack()
                        }
                    },
                    size = 42.dp,
                    testTag = "import_screen_back_button"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = FlinsTheme.colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "PLAYLIST IMPORTER",
                        style = FlinsTheme.typography.overline,
                        color = FlinsTheme.colors.textMuted
                    )
                    Text(
                        text = "Import Playlist",
                        style = FlinsTheme.typography.title,
                        color = FlinsTheme.colors.textPrimary
                    )
                }
            }

            // Step Content
            when (step) {
                ImportStep.INPUT -> {
                    InputStepContent(
                        url = url,
                        selectedPlatform = selectedPlatform,
                        errorMessage = errorMessage,
                        onUrlChange = { viewModel.onUrlChanged(it) },
                        onSelectPlatform = { viewModel.onSelectPlatform(it) },
                        onFetch = { viewModel.fetchPlaylist() }
                    )
                }
                ImportStep.EXTRACTING, ImportStep.MATCHING -> {
                    LoadingStepContent(
                        step = step,
                        progress = progress,
                        onCancel = { viewModel.cancelImport() }
                    )
                }
                ImportStep.REVIEW -> {
                    ReviewStepContent(
                        playlist = playlistData,
                        results = matchResults,
                        onSelectCandidate = { index -> candidatePickerIndex = index },
                        onRemoveMatch = { index -> viewModel.removeMatch(index) },
                        onCommit = { viewModel.commitImport(userId) },
                        onCancel = { viewModel.cancelImport() }
                    )
                }
                ImportStep.IMPORTING -> {
                    LoadingStepContent(
                        step = step,
                        progress = progress,
                        onCancel = { viewModel.cancelImport() }
                    )
                }
                ImportStep.SUCCESS -> {
                    SuccessStepContent(
                        playlistName = playlistData?.playlistName ?: "Imported Playlist",
                        songCount = matchResults.count { it.isMatched },
                        onViewPlaylist = {
                            val id = createdPlaylistId
                            if (!id.isNullOrBlank()) {
                                onNavigateToPlaylist(id)
                            } else {
                                onBack()
                            }
                        },
                        onImportAnother = { viewModel.reset() }
                    )
                }
                ImportStep.ERROR -> {
                    ErrorStepContent(
                        message = errorMessage ?: "An unexpected error occurred.",
                        onRetry = { viewModel.fetchPlaylist() },
                        onChangeLink = { viewModel.reset() }
                    )
                }
            }
        }

        // Candidate Selection Dialog (for Multiple Matches or Needs Review)
        if (candidatePickerIndex != null && candidatePickerIndex!! in matchResults.indices) {
            val idx = candidatePickerIndex!!
            val item = matchResults[idx]
            CandidatePickerDialog(
                originalTrack = item.original,
                candidates = item.candidates,
                currentSelectedId = item.matchedTrack?.id,
                onSelectCandidate = { track ->
                    viewModel.selectCandidate(idx, track)
                    candidatePickerIndex = null
                },
                onDismiss = { candidatePickerIndex = null }
            )
        }
    }
}

@Composable
private fun InputStepContent(
    url: String,
    selectedPlatform: PlatformType?,
    errorMessage: String?,
    onUrlChange: (String) -> Unit,
    onSelectPlatform: (PlatformType) -> Unit,
    onFetch: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            // Hero Intro Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                backgroundColor = Color(0xFF202020),
                borderColor = Color(0xFF383838)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        text = "Bring your favourite playlists to Anyfy.",
                        style = FlinsTheme.typography.title,
                        color = FlinsTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Recreate public playlists from Spotify and YouTube Music seamlessly using tracks in Anyfy's music catalog. No account login required.",
                        style = FlinsTheme.typography.bodyMedium,
                        color = FlinsTheme.colors.textSecondary
                    )
                }
            }
        }

        // Platform Selection
        item {
            Text(
                text = "SELECT SOURCE PLATFORM",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Spotify Pill
                PlatformSelectPill(
                    platform = PlatformType.SPOTIFY,
                    isSelected = selectedPlatform == PlatformType.SPOTIFY,
                    onClick = { onSelectPlatform(PlatformType.SPOTIFY) },
                    modifier = Modifier.weight(1f)
                )

                // YouTube Music Pill
                PlatformSelectPill(
                    platform = PlatformType.YOUTUBE_MUSIC,
                    isSelected = selectedPlatform == PlatformType.YOUTUBE_MUSIC,
                    onClick = { onSelectPlatform(PlatformType.YOUTUBE_MUSIC) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Playlist URL Input Box
        item {
            Text(
                text = "PLAYLIST LINK",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted
            )
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x1AFFFFFF))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (url.isEmpty()) {
                            Text(
                                text = "Paste your public playlist link here",
                                style = FlinsTheme.typography.body,
                                color = FlinsTheme.colors.textMuted
                            )
                        }
                        BasicTextField(
                            value = url,
                            onValueChange = onUrlChange,
                            textStyle = FlinsTheme.typography.body.copy(color = FlinsTheme.colors.textPrimary),
                            cursorBrush = SolidColor(FlinsCyan),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("playlist_url_input")
                        )
                    }

                    if (url.isNotEmpty()) {
                        GlassIconButton(
                            onClick = { onUrlChange("") },
                            size = 36.dp
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = FlinsTheme.colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Paste from clipboard button
                    GlassIconButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                onUrlChange(clipText.trim())
                            }
                        },
                        size = 38.dp,
                        testTag = "paste_clipboard_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            tint = FlinsCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = FlinsError,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = errorMessage,
                        style = FlinsTheme.typography.caption,
                        color = FlinsError
                    )
                }
            }
        }

        // Fetch Button
        item {
            GlassButton(
                onClick = onFetch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                variant = GlassButtonVariant.PRIMARY,
                testTag = "fetch_playlist_button"
            ) {
                Text(
                    text = "Fetch Playlist",
                    style = FlinsTheme.typography.button,
                    color = Color.Black
                )
            }
        }

        // Supported Formats Info
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                backgroundColor = Color(0x12FFFFFF)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Supported Public Links",
                        style = FlinsTheme.typography.label,
                        color = FlinsTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Spotify: https://open.spotify.com/playlist/{id}\n• YouTube Music: https://music.youtube.com/playlist?list={id}\n• YouTube: https://www.youtube.com/playlist?list={id}",
                        style = FlinsTheme.typography.caption,
                        color = FlinsTheme.colors.textSecondary,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Note: Private, unlisted or deleted playlists cannot be accessed without login.",
                        style = FlinsTheme.typography.caption,
                        color = FlinsTheme.colors.textMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformSelectPill(
    platform: PlatformType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) Color(0xFFBDBDBD) else Color(0xFF383838)
    val bgColor = if (isSelected) Color(0xFF454545) else Color(0xFF202020)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFFF0F0F0) else Color(0xFF707070))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = platform.displayName,
                style = FlinsTheme.typography.label,
                color = if (isSelected) FlinsTheme.colors.textPrimary else FlinsTheme.colors.textSecondary
            )
        }
    }
}

@Composable
private fun LoadingStepContent(
    step: ImportStep,
    progress: com.example.viewmodel.ImportProgressState,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            backgroundColor = Color(0xFF202020),
            borderColor = Color(0xFF383838)
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = FlinsCyan,
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = when (step) {
                        ImportStep.EXTRACTING -> "Extracting Public Playlist..."
                        ImportStep.MATCHING -> "Matching Songs in Anyfy Catalog..."
                        ImportStep.IMPORTING -> "Saving to Your Library..."
                        else -> "Working..."
                    },
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = progress.message.ifBlank { "Please wait a moment." },
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (progress.total > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val fraction = (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = FlinsCyan,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${progress.current} of ${progress.total}",
                        style = FlinsTheme.typography.caption,
                        color = FlinsTheme.colors.textMuted
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                GlassButton(
                    onClick = onCancel,
                    variant = GlassButtonVariant.GHOST,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Cancel", style = FlinsTheme.typography.button, color = FlinsTheme.colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun ReviewStepContent(
    playlist: com.example.service.importer.model.PlaylistImportData?,
    results: List<TrackMatchResult>,
    onSelectCandidate: (index: Int) -> Unit,
    onRemoveMatch: (index: Int) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("All") }

    val filteredResults = remember(results, selectedFilter) {
        when (selectedFilter) {
            "Matched" -> results.filter { it.status == MatchStatus.MATCHED }
            "Needs Review" -> results.filter { it.status == MatchStatus.NEEDS_REVIEW || it.status == MatchStatus.MULTIPLE_MATCHES }
            "Unmatched" -> results.filter { it.status == MatchStatus.NOT_FOUND || it.status == MatchStatus.UNAVAILABLE }
            else -> results
        }
    }

    val matchedCount = results.count { it.isMatched }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Playlist Preview Hero Card
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    backgroundColor = Color(0xFF202020),
                    borderColor = Color(0xFF383838)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Artwork
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x22FFFFFF))
                        ) {
                            if (!playlist?.artwork.isNullOrBlank()) {
                                AsyncImage(
                                    model = playlist!!.artwork,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = FlinsCyan,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .align(Alignment.Center)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist?.playlistName ?: "Imported Playlist",
                                style = FlinsTheme.typography.title,
                                color = FlinsTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val badgeColor = Color(android.graphics.Color.parseColor(
                                    playlist?.source?.badgeColorHex ?: "#1DB954"
                                ))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(badgeColor.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = playlist?.source?.displayName ?: "Source",
                                        style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                                        color = badgeColor
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${playlist?.totalTracks ?: results.size} tracks",
                                    style = FlinsTheme.typography.bodyMedium,
                                    color = FlinsTheme.colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Matching Stats Counters
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatBadge("Total", "${results.size}", Color(0xFF282828), Modifier.weight(1f), Color(0xFFF0F0F0))
                    StatBadge("Matched", "$matchedCount", Color(0xFF282828), Modifier.weight(1f), Color(0xFFE0E0E0))
                    val reviewCount = results.count { it.status == MatchStatus.NEEDS_REVIEW || it.status == MatchStatus.MULTIPLE_MATCHES }
                    StatBadge("Review", "$reviewCount", Color(0xFF282828), Modifier.weight(1f), Color(0xFFBDBDBD))
                    val missingCount = results.count { it.status == MatchStatus.NOT_FOUND }
                    StatBadge("Missing", "$missingCount", Color(0xFF282828), Modifier.weight(1f), Color(0xFF707070))
                }
            }

            // Filter Tabs
            item {
                val filters = listOf("All", "Matched", "Needs Review", "Unmatched")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { f ->
                        val isSelected = f == selectedFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                .background(if (isSelected) Color(0xFF454545) else Color(0xFF202020))
                                .clickable { selectedFilter = f }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = f,
                                style = FlinsTheme.typography.label,
                                color = if (isSelected) Color(0xFFF0F0F0) else FlinsTheme.colors.textSecondary
                            )
                        }
                    }
                }
            }

            // Track Comparison Rows
            itemsIndexed(filteredResults) { _, matchResult ->
                val originalIndex = results.indexOf(matchResult)
                TrackComparisonRow(
                    result = matchResult,
                    onSelectAlternate = { onSelectCandidate(originalIndex) },
                    onRemove = { onRemoveMatch(originalIndex) }
                )
            }
        }

        // Bottom Actions Bar (Completely elevated above persistent Bottom Navigation Bar)
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            backgroundColor = FlinsDarkBg.copy(alpha = 0.98f),
            borderColor = Color.White.copy(alpha = 0.15f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(0.35f).height(48.dp),
                        variant = GlassButtonVariant.SECONDARY,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Cancel", style = FlinsTheme.typography.button, color = FlinsTheme.colors.textSecondary)
                    }

                    GlassButton(
                        onClick = onCommit,
                        modifier = Modifier.weight(0.65f).height(48.dp),
                        variant = GlassButtonVariant.PRIMARY,
                        shape = RoundedCornerShape(16.dp),
                        testTag = "commit_import_button"
                    ) {
                        Text(
                            text = "Import $matchedCount Songs",
                            style = FlinsTheme.typography.button,
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadge(
    label: String,
    value: String,
    bgColor: Color,
    modifier: Modifier = Modifier,
    textColor: Color = FlinsTheme.colors.textPrimary
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = FlinsTheme.typography.title, color = textColor)
            Text(text = label, style = FlinsTheme.typography.caption, color = FlinsTheme.colors.textMuted)
        }
    }
}

@Composable
private fun TrackComparisonRow(
    result: TrackMatchResult,
    onSelectAlternate: () -> Unit,
    onRemove: () -> Unit
) {
    val orig = result.original
    val match = result.matchedTrack

    val statusColor = when (result.status) {
        MatchStatus.MATCHED -> Color(0xFFF0F0F0)
        MatchStatus.NEEDS_REVIEW -> Color(0xFFBDBDBD)
        MatchStatus.MULTIPLE_MATCHES -> Color(0xFFA0A0A0)
        MatchStatus.NOT_FOUND, MatchStatus.UNAVAILABLE -> Color(0xFF707070)
    }

    val statusLabel = when (result.status) {
        MatchStatus.MATCHED -> "Matched"
        MatchStatus.NEEDS_REVIEW -> "Needs Review"
        MatchStatus.MULTIPLE_MATCHES -> "Multiple Matches"
        MatchStatus.NOT_FOUND -> "Not Found"
        MatchStatus.UNAVAILABLE -> "Unavailable"
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        backgroundColor = Color(0x18FFFFFF),
        borderColor = statusColor.copy(alpha = 0.3f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: original track vs match status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${orig.position + 1}. ${orig.title}",
                    style = FlinsTheme.typography.label,
                    color = FlinsTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                        color = statusColor
                    )
                }
            }

            Text(
                text = orig.displayArtists,
                style = FlinsTheme.typography.caption,
                color = FlinsTheme.colors.textMuted
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Matched track in Anyfy
            if (match != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x16000000))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = match.artwork,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = match.title,
                            style = FlinsTheme.typography.label,
                            color = FlinsCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${match.artist} • ${match.durationFormatted}",
                            style = FlinsTheme.typography.caption,
                            color = FlinsTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (result.candidates.size > 1) {
                        GlassIconButton(
                            onClick = onSelectAlternate,
                            size = 32.dp
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = "Swap candidate",
                                tint = FlinsCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF282828))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "No equivalent track found in Anyfy catalog",
                        style = FlinsTheme.typography.caption,
                        color = FlinsTheme.colors.textMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidatePickerDialog(
    originalTrack: com.example.service.importer.model.ImportTrackItem,
    candidates: List<Track>,
    currentSelectedId: String?,
    onSelectCandidate: (Track) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Select Anyfy Match",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
                Text(
                    text = "Original: ${originalTrack.title} by ${originalTrack.primaryArtist}",
                    style = FlinsTheme.typography.caption,
                    color = FlinsTheme.colors.textMuted
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(candidates) { candidate ->
                    val isSelected = candidate.id == currentSelectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFF454545) else Color(0xFF202020))
                            .clickable { onSelectCandidate(candidate) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = candidate.artwork,
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = candidate.title,
                                style = FlinsTheme.typography.label,
                                color = if (isSelected) FlinsCyan else FlinsTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${candidate.artist} • ${candidate.durationFormatted}",
                                style = FlinsTheme.typography.caption,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = FlinsCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = FlinsCyan, style = FlinsTheme.typography.button)
            }
        },
        containerColor = FlinsDarkBg,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun SuccessStepContent(
    playlistName: String,
    songCount: Int,
    onViewPlaylist: () -> Unit,
    onImportAnother: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            backgroundColor = Color(0xFF202020),
            borderColor = Color(0xFF383838)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF282828)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFFF0F0F0),
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "Import Complete!",
                    style = FlinsTheme.typography.hero,
                    color = FlinsTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Created '$playlistName' with $songCount tracks in your Anyfy Library. All songs are ready to stream.",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                GlassButton(
                    onClick = onViewPlaylist,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    variant = GlassButtonVariant.PRIMARY,
                    testTag = "view_imported_playlist_button"
                ) {
                    Text("View in Library", style = FlinsTheme.typography.button, color = Color.Black)
                }
                Spacer(modifier = Modifier.height(12.dp))
                GlassButton(
                    onClick = onImportAnother,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    variant = GlassButtonVariant.GHOST
                ) {
                    Text("Import Another Playlist", style = FlinsTheme.typography.button, color = FlinsCyan)
                }
            }
        }
    }
}

@Composable
private fun ErrorStepContent(
    message: String,
    onRetry: () -> Unit,
    onChangeLink: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            backgroundColor = Color(0xFF202020),
            borderColor = Color(0xFF383838)
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFD0D0D0),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Import Failed",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                GlassButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    variant = GlassButtonVariant.PRIMARY
                ) {
                    Text("Try Again", style = FlinsTheme.typography.button, color = Color.Black)
                }
                Spacer(modifier = Modifier.height(10.dp))
                GlassButton(
                    onClick = onChangeLink,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    variant = GlassButtonVariant.GHOST
                ) {
                    Text("Enter Another Link", style = FlinsTheme.typography.button, color = FlinsCyan)
                }
            }
        }
    }
}

