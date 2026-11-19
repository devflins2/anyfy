package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.model.Track
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsError
import com.example.ui.theme.FlinsTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    currentTrack: Track?,
    currentTrackIndex: Int = 0,
    queue: List<Track>,
    isPlaying: Boolean = true,
    onSelectTrack: (Track) -> Unit = {},
    onSelectTrackIndex: ((Int, Track) -> Unit)? = null,
    onRemoveFromQueue: (Int) -> Unit,
    onReorderQueue: (Int, Int) -> Unit = { _, _ -> },
    onClearQueue: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val safeCurrentIndex = remember(currentTrack, currentTrackIndex, queue) {
        if (currentTrackIndex in queue.indices && queue[currentTrackIndex].id == currentTrack?.id) {
            currentTrackIndex
        } else {
            queue.indexOfFirst { it.id == currentTrack?.id }.coerceAtLeast(0)
        }
    }

    val upNextItems = remember(queue, safeCurrentIndex) {
        if (safeCurrentIndex + 1 <= queue.size) {
            queue.subList(safeCurrentIndex + 1, queue.size)
        } else {
            emptyList()
        }
    }

    // Confirmation dialog before clearing queue
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = FlinsDarkBg.copy(alpha = 0.98f),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Clear Up Next?",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "All upcoming songs will be removed from your queue. The current track will continue playing.",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearQueue()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text(
                        text = "Clear Queue",
                        color = FlinsError,
                        style = FlinsTheme.typography.button
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = FlinsTheme.colors.textSecondary,
                        style = FlinsTheme.typography.button
                    )
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FlinsDarkBg.copy(alpha = 0.97f),
        contentColor = FlinsTheme.colors.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header with Clear action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PLAYBACK QUEUE",
                        style = FlinsTheme.typography.overline,
                        color = FlinsTheme.colors.textMuted
                    )
                    Text(
                        text = "Queue & Up Next",
                        style = FlinsTheme.typography.title,
                        color = FlinsTheme.colors.textPrimary
                    )
                }
                if (upNextItems.isNotEmpty()) {
                    GlassIconButton(
                        onClick = { showClearConfirmDialog = true },
                        size = 38.dp,
                        testTag = "clear_queue_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.ClearAll,
                            contentDescription = "Clear queue",
                            tint = FlinsCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Now Playing Section (Highlighted with liquid glass aura)
            if (currentTrack != null) {
                Text(
                    text = "NOW PLAYING",
                    style = FlinsTheme.typography.metadata,
                    color = FlinsCyan
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FlinsTheme.radii.md))
                        .background(FlinsCyan.copy(alpha = 0.12f))
                        .border(1.dp, FlinsCyan.copy(alpha = 0.38f), RoundedCornerShape(FlinsTheme.radii.md))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(FlinsTheme.radii.sm))
                    ) {
                        val nowArtwork = currentTrack.displayArtwork.ifBlank { currentTrack.artwork }
                        val nowCleanVid = currentTrack.cleanVideoId
                        val nowYtFallback = if (nowCleanVid != null) "https://i.ytimg.com/vi/$nowCleanVid/hqdefault.jpg" else ""
                        AsyncImage(
                            model = nowArtwork.ifBlank { nowYtFallback },
                            contentDescription = currentTrack.title,
                            modifier = Modifier.size(46.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTrack.title,
                            style = FlinsTheme.typography.titleMedium,
                            color = FlinsCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentTrack.artist} • ${currentTrack.album}",
                            style = FlinsTheme.typography.bodyMedium,
                            color = FlinsTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentTrack.durationFormatted,
                        style = FlinsTheme.typography.metadata,
                        color = FlinsCyan.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    MiniAudioEqualizer(
                        isPlaying = isPlaying,
                        modifier = Modifier.size(width = 18.dp, height = 14.dp),
                        color = FlinsCyan
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
            }

            // Up Next Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UP NEXT (${upNextItems.size})",
                    style = FlinsTheme.typography.metadata,
                    color = FlinsTheme.colors.textMuted
                )
                if (upNextItems.size > 1) {
                    Text(
                        text = "Drag handle to reorder",
                        style = FlinsTheme.typography.caption,
                        color = FlinsTheme.colors.textMuted
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (upNextItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FlinsTheme.radii.md))
                        .background(Color(0x08FFFFFF))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(FlinsTheme.radii.md))
                        .padding(vertical = 28.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = FlinsTheme.colors.textMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Up Next queue is empty",
                            style = FlinsTheme.typography.label,
                            color = FlinsTheme.colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Use 'Play Next' or 'Add to Queue' on any track menu",
                            style = FlinsTheme.typography.caption,
                            color = FlinsTheme.colors.textMuted
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                var draggingIndex by remember { mutableStateOf<Int?>(null) }
                var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }
                val itemHeightPx = with(LocalDensity.current) { 56.dp.toPx() }

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val maxListHeight = (maxHeight * 0.55f).coerceIn(240.dp, 460.dp)

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = maxListHeight),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(upNextItems, key = { _, track -> "${track.id}_${track.title}" }) { index, track ->
                            val isDragging = draggingIndex == index
                            val fullQueueIndex = safeCurrentIndex + 1 + index

                            Row(
                                modifier = Modifier
                                    .testTag("queue_item_$index")
                                    .fillMaxWidth()
                                    .zIndex(if (isDragging) 10f else 1f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragAccumulatedPx else 0f
                                        scaleX = if (isDragging) 1.02f else 1f
                                        scaleY = if (isDragging) 1.02f else 1f
                                    }
                                    .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                                    .background(if (isDragging) Color(0xFF454545) else Color(0xFF202020))
                                    .border(
                                        width = 1.dp,
                                        color = if (isDragging) Color(0xFFBDBDBD) else Color(0xFF383838),
                                        shape = RoundedCornerShape(FlinsTheme.radii.sm)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Isolated Drag Handle (Dragging will not trigger track selection)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .pointerInput(upNextItems.size, index) {
                                            detectVerticalDragGestures(
                                                onDragStart = {
                                                    draggingIndex = index
                                                    dragAccumulatedPx = 0f
                                                },
                                                onDragEnd = {
                                                    draggingIndex = null
                                                    dragAccumulatedPx = 0f
                                                },
                                                onDragCancel = {
                                                    draggingIndex = null
                                                    dragAccumulatedPx = 0f
                                                },
                                                onVerticalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragAccumulatedPx += dragAmount
                                                    val currentIndex = draggingIndex ?: return@detectVerticalDragGestures
                                                    val threshold = itemHeightPx * 0.75f
                                                    if (dragAccumulatedPx > threshold && currentIndex < upNextItems.lastIndex) {
                                                        onReorderQueue(
                                                            safeCurrentIndex + 1 + currentIndex,
                                                            safeCurrentIndex + 1 + currentIndex + 1
                                                        )
                                                        draggingIndex = currentIndex + 1
                                                        dragAccumulatedPx -= itemHeightPx
                                                    } else if (dragAccumulatedPx < -threshold && currentIndex > 0) {
                                                        onReorderQueue(
                                                            safeCurrentIndex + 1 + currentIndex,
                                                            safeCurrentIndex + 1 + currentIndex - 1
                                                        )
                                                        draggingIndex = currentIndex - 1
                                                        dragAccumulatedPx += itemHeightPx
                                                    }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = if (isDragging) FlinsCyan else FlinsTheme.colors.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // 2. Isolated Track Content Click Area (Plays this track on tap)
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            if (onSelectTrackIndex != null) {
                                                onSelectTrackIndex(fullQueueIndex, track)
                                            } else {
                                                onSelectTrack(track)
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Artwork with fallback
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF202020))
                                    ) {
                                        val artworkUrl = track.displayArtwork.ifBlank { track.artwork }
                                        val cleanVid = track.cleanVideoId
                                        val ytFallbackUrl = if (cleanVid != null) "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg" else ""

                                        AsyncImage(
                                            model = artworkUrl.ifBlank { ytFallbackUrl },
                                            contentDescription = track.title,
                                            modifier = Modifier.size(42.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    // Title & Artist
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = FlinsTheme.typography.label,
                                            color = FlinsTheme.colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artist,
                                            style = FlinsTheme.typography.caption,
                                            color = FlinsTheme.colors.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Duration
                                Text(
                                    text = track.durationFormatted,
                                    style = FlinsTheme.typography.metadata,
                                    color = FlinsTheme.colors.textMuted
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                // 3. Isolated Remove Button (Does not bubble to track selection)
                                IconButton(
                                    onClick = { onRemoveFromQueue(fullQueueIndex) },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("remove_queue_item_$index")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove from queue",
                                        tint = FlinsTheme.colors.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
