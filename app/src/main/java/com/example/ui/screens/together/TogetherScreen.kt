package com.example.ui.screens.together

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.model.ChatMessage
import com.example.model.LiveReaction
import com.example.model.MemberStatus
import com.example.model.Participant
import com.example.model.PublicRoomSummary
import com.example.model.Room
import com.example.model.RoomQueueItem
import com.example.model.SongRequestItem
import com.example.model.Track
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassButtonVariant
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconButton
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsEmerald
import com.example.ui.theme.FlinsError
import com.example.ui.theme.FlinsPink
import com.example.ui.theme.FlinsTheme
import com.example.ui.theme.FlinsViolet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class PartyTab {
    QUEUE,
    CHAT,
    REQUESTS,
    MEMBERS
}

private data class AnimatedReactionBubble(
    val id: String,
    val emoji: String,
    val senderName: String,
    val startOffsetX: Float
)

@Composable
fun TogetherScreen(
    activeRoom: Room?,
    currentTrack: Track?,
    queue: List<Track>,
    userName: String,
    isPlaying: Boolean = false,
    playbackPositionMs: Long = 0L,
    durationMs: Long = 0L,
    isHost: Boolean = false,
    availableTracks: List<Track> = emptyList(),
    publicRooms: List<PublicRoomSummary> = emptyList(),
    isRefreshingPublicRooms: Boolean = false,
    onRefreshPublicRooms: (String) -> Unit = {},
    onJoinDirect: (String) -> Unit = {},
    onTransferHost: (String) -> Unit = {},
    onKickParticipant: (String) -> Unit = {},
    onToggleRoomLock: () -> Unit = {},
    onToggleChatMute: () -> Unit = {},
    onDeleteChatMessage: (String) -> Unit = {},
    onSubmitSongRequest: (Track) -> Unit = {},
    onReviewSongRequest: (String, Boolean) -> Unit = { _, _ -> },
    onSetPlaybackModes: (String, Boolean) -> Unit = { _, _ -> },
    onCreateRoomClick: () -> Unit,
    onJoinRoomClick: () -> Unit,
    onLeaveRoom: () -> Unit,
    onEndRoom: () -> Unit = {},
    onStartListening: () -> Unit,
    onTogglePlayPause: () -> Unit = {},
    onNextTrack: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onAddToRoomQueue: (Track) -> Unit = {},
    onRemoveFromRoomQueue: (String) -> Unit = {},
    onReorderRoomQueue: (Int, Int) -> Unit = { _, _ -> },
    onSendChatMessage: (String, Boolean) -> Unit = { _, _ -> },
    onSendReaction: (String) -> Unit = {},
    onSelectTrack: (Track, String?) -> Unit = { _, _ -> },
    onImportPlaylistClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(PartyTab.QUEUE) }
    var showSongPicker by remember { mutableStateOf(false) }
    var showRequestSongPicker by remember { mutableStateOf(false) }
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showInviteSheet by remember { mutableStateOf(false) }
    var chatInputText by remember { mutableStateOf("") }

    // Floating reaction animations
    val floatingReactions = remember { mutableStateListOf<AnimatedReactionBubble>() }

    // Observe incoming reactions and trigger visual animations
    LaunchedEffect(activeRoom?.recentReactions) {
        val latest = activeRoom?.recentReactions?.lastOrNull()
        if (latest != null) {
            val randomOffset = (-120..120).random().toFloat()
            val bubble = AnimatedReactionBubble(
                id = "${latest.id}_${System.currentTimeMillis()}",
                emoji = latest.emoji,
                senderName = latest.senderName,
                startOffsetX = randomOffset
            )
            floatingReactions.add(bubble)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Floating reaction bubbles overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f),
            contentAlignment = Alignment.BottomCenter
        ) {
            floatingReactions.forEach { bubble ->
                FloatingReactionItem(
                    bubble = bubble,
                    onFinished = { floatingReactions.remove(bubble) }
                )
            }
        }

        if (activeRoom == null) {
            // OUT OF ROOM: Discover & Join Lobby
            OutOfRoomContent(
                publicRooms = publicRooms,
                isRefreshing = isRefreshingPublicRooms,
                onRefresh = { onRefreshPublicRooms("") },
                onJoinDirect = onJoinDirect,
                onCreateRoomClick = onCreateRoomClick,
                onJoinRoomClick = onJoinRoomClick
            )
        } else {
            // IN ROOM: Dedicated Social Party Room Experience
            InRoomContent(
                room = activeRoom,
                currentTrack = currentTrack ?: activeRoom.currentTrack,
                isPlaying = isPlaying,
                playbackPositionMs = playbackPositionMs,
                durationMs = durationMs,
                isHost = isHost,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onLeaveClick = { showLeaveConfirmDialog = true },
                onInviteClick = { showInviteSheet = true },
                onTogglePlayPause = onTogglePlayPause,
                onNextTrack = onNextTrack,
                onSeekTo = onSeekTo,
                onSelectTrack = onSelectTrack,
                onOpenSongPicker = { showSongPicker = true },
                onOpenSongRequestPicker = { showRequestSongPicker = true },
                onRemoveQueueItem = onRemoveFromRoomQueue,
                onMoveQueueItem = onReorderRoomQueue,
                chatInputText = chatInputText,
                onChatInputChanged = { chatInputText = it },
                onSendChat = { text, sharePlaying ->
                    if (text.isNotBlank() || sharePlaying) {
                        onSendChatMessage(text.trim(), sharePlaying)
                        chatInputText = ""
                    }
                },
                onReactionClick = { type ->
                    onSendReaction(type)
                },
                onTransferHost = onTransferHost,
                onKickParticipant = onKickParticipant,
                onToggleRoomLock = onToggleRoomLock,
                onToggleChatMute = onToggleChatMute,
                onDeleteChatMessage = onDeleteChatMessage,
                onReviewSongRequest = onReviewSongRequest,
                onSetPlaybackModes = onSetPlaybackModes,
                onExpandFullPlayer = onStartListening,
                onImportPlaylistClick = onImportPlaylistClick
            )
        }

        // Leave Confirmation Dialog
        if (showLeaveConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showLeaveConfirmDialog = false },
                containerColor = FlinsDarkBg.copy(alpha = 0.95f),
                title = {
                    Text(
                        text = if (isHost) "End Party Session?" else "Leave Party Room?",
                        style = FlinsTheme.typography.title,
                        color = FlinsTheme.colors.textPrimary
                    )
                },
                text = {
                    Text(
                        text = if (isHost) {
                            "You are the party host. Do you want to end the party session for all members or just leave?"
                        } else {
                            "You will disconnect from synchronized playback and leave the party chat."
                        },
                        style = FlinsTheme.typography.bodyMedium,
                        color = FlinsTheme.colors.textSecondary
                    )
                },
                confirmButton = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isHost) {
                            TextButton(
                                onClick = {
                                    showLeaveConfirmDialog = false
                                    onEndRoom()
                                }
                            ) {
                                Text("End for Everyone", color = FlinsError, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        TextButton(
                            onClick = {
                                showLeaveConfirmDialog = false
                                onLeaveRoom()
                            }
                        ) {
                            Text(if (isHost) "Leave" else "Leave Room", color = Color(0xFFD0D0D0), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLeaveConfirmDialog = false }) {
                        Text("Stay", color = Color(0xFFF0F0F0))
                    }
                }
            )
        }

        // Add Song To Room Queue Sheet
        if (showSongPicker && activeRoom != null) {
            AddSongToRoomQueueSheet(
                availableTracks = availableTracks,
                sheetTitle = "Add Song to Party Queue",
                onTrackSelected = { track ->
                    onAddToRoomQueue(track)
                    showSongPicker = false
                },
                onImportPlaylistClick = onImportPlaylistClick,
                onDismiss = { showSongPicker = false }
            )
        }

        // Song Request Sheet
        if (showRequestSongPicker && activeRoom != null) {
            AddSongToRoomQueueSheet(
                availableTracks = availableTracks,
                sheetTitle = "Request a Song",
                onTrackSelected = { track ->
                    onSubmitSongRequest(track)
                    showRequestSongPicker = false
                },
                onImportPlaylistClick = onImportPlaylistClick,
                onDismiss = { showRequestSongPicker = false }
            )
        }

        // Invite Sheet
        if (showInviteSheet && activeRoom != null) {
            InviteRoomSheet(
                roomCode = activeRoom.code,
                roomName = activeRoom.name,
                onDismiss = { showInviteSheet = false }
            )
        }
    }
}

// ============================================================================
// IN ROOM SOCIAL PARTY EXPERIENCE
// ============================================================================

@Composable
private fun InRoomContent(
    room: Room,
    currentTrack: Track?,
    isPlaying: Boolean,
    playbackPositionMs: Long,
    durationMs: Long,
    isHost: Boolean,
    selectedTab: PartyTab,
    onTabSelected: (PartyTab) -> Unit,
    onLeaveClick: () -> Unit,
    onInviteClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectTrack: (Track, String?) -> Unit,
    onOpenSongPicker: () -> Unit,
    onOpenSongRequestPicker: () -> Unit,
    onRemoveQueueItem: (String) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    chatInputText: String,
    onChatInputChanged: (String) -> Unit,
    onSendChat: (String, Boolean) -> Unit,
    onReactionClick: (String) -> Unit,
    onTransferHost: (String) -> Unit,
    onKickParticipant: (String) -> Unit,
    onToggleRoomLock: () -> Unit,
    onToggleChatMute: () -> Unit,
    onDeleteChatMessage: (String) -> Unit,
    onReviewSongRequest: (String, Boolean) -> Unit,
    onSetPlaybackModes: (String, Boolean) -> Unit,
    onExpandFullPlayer: () -> Unit,
    onImportPlaylistClick: () -> Unit = {}
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 150.dp)
    ) {
        // 1. Header: Room Info & Exit
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF282828))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isHost) "HOSTING PARTY" else "PARTY LISTENER",
                                    style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFFF0F0F0)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "CODE: ${room.code}",
                                style = FlinsTheme.typography.overline,
                                color = FlinsTheme.colors.textMuted
                            )
                            if (room.isLocked) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    tint = Color(0xFFA0A0A0),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = room.name,
                            style = FlinsTheme.typography.hero.copy(fontSize = 24.sp),
                            color = FlinsTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (room.description.isNotBlank()) {
                            Text(
                                text = room.description,
                                style = FlinsTheme.typography.bodyMedium,
                                color = FlinsTheme.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Invite button
                        GlassIconButton(
                            onClick = onInviteClick,
                            size = 38.dp,
                            testTag = "invite_party_button"
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Invite",
                                tint = Color(0xFFC0C0C0),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Leave button
                        GlassIconButton(
                            onClick = onLeaveClick,
                            size = 38.dp,
                            testTag = "leave_room_button"
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Leave",
                                tint = FlinsError,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Host Recovering Alert Banner
                if (room.isHostRecovering) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF282828))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFC0C0C0),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Host connection recovering... Music preserved.",
                                style = FlinsTheme.typography.caption,
                                color = Color(0xFFF0F0F0)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Members Avatar List with Status Dots
                val displayParticipants = remember(room.participants) {
                    room.participants.distinctBy { it.id.ifBlank { it.name } }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MEMBERS IN PARTY (${displayParticipants.size}/${room.maxParticipants})",
                        style = FlinsTheme.typography.overline,
                        color = FlinsTheme.colors.textMuted
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayParticipants, key = { it.id.ifBlank { it.name } }) { participant ->
                        PartyMemberAvatar(participant = participant)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Now Playing Card in Room
                NowPlayingPartyCard(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    playbackPositionMs = playbackPositionMs,
                    durationMs = durationMs,
                    isHost = isHost,
                    repeatMode = room.repeatMode,
                    shuffleMode = room.shuffleMode,
                    onTogglePlayPause = onTogglePlayPause,
                    onNextTrack = onNextTrack,
                    onSeekTo = onSeekTo,
                    onSetPlaybackModes = onSetPlaybackModes,
                    onShareToChat = {
                        if (currentTrack != null) {
                            onSendChat("Listening to this right now! 🎶", true)
                        }
                    },
                    onCardClick = onExpandFullPlayer
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 4. Quick Reaction Bar
                QuickReactionBar(
                    onReactionClick = onReactionClick
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 5. Party Tabs: [Queue] [Chat] [Requests] [Members]
                PartyTabBar(
                    selectedTab = selectedTab,
                    queueCount = room.sharedQueue.size,
                    chatCount = room.chatMessages.size,
                    requestCount = room.pendingSongRequests.size,
                    memberCount = room.participants.size,
                    onTabSelected = onTabSelected
                )
            }
        }

        // Tab Content
        when (selectedTab) {
            PartyTab.QUEUE -> {
                item {
                    SharedQueueTabContent(
                        room = room,
                        isHost = isHost,
                        onOpenSongPicker = onOpenSongPicker,
                        onImportPlaylistClick = onImportPlaylistClick,
                        onSelectTrack = onSelectTrack,
                        onRemoveItem = onRemoveQueueItem,
                        onMoveItem = onMoveQueueItem
                    )
                }
            }
            PartyTab.CHAT -> {
                item {
                    ChatTabContent(
                        room = room,
                        isHost = isHost,
                        chatInputText = chatInputText,
                        onChatInputChanged = onChatInputChanged,
                        onSendChat = onSendChat,
                        onDeleteMessage = onDeleteChatMessage
                    )
                }
            }
            PartyTab.REQUESTS -> {
                item {
                    RequestsTabContent(
                        requests = room.pendingSongRequests,
                        isHost = isHost,
                        onOpenSongPicker = onOpenSongRequestPicker,
                        onReview = onReviewSongRequest
                    )
                }
            }
            PartyTab.MEMBERS -> {
                item {
                    MembersTabContent(
                        room = room,
                        isHost = isHost,
                        onTransferHost = onTransferHost,
                        onKick = onKickParticipant,
                        onToggleLock = onToggleRoomLock,
                        onToggleMute = onToggleChatMute
                    )
                }
            }
        }
    }
}

// ============================================================================
// PARTY MEMBER AVATAR WITH STATUS INDICATOR
// ============================================================================

@Composable
private fun PartyMemberAvatar(participant: Participant) {
    val statusColor = when (participant.status) {
        MemberStatus.ONLINE -> Color(0xFFF0F0F0)
        MemberStatus.BUFFERING -> Color(0xFFBDBDBD)
        MemberStatus.RECONNECTING -> Color(0xFFA0A0A0)
        MemberStatus.DISCONNECTED -> Color(0xFF707070)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(68.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(android.graphics.Color.parseColor(participant.avatarColorHex.ifBlank { "#00F0FF" })).copy(alpha = 0.6f),
                            Color(0x33FFFFFF)
                        )
                    )
                )
                .border(
                    width = if (participant.isHost) 2.dp else 1.dp,
                    color = if (participant.isHost) FlinsCyan else Color.White.copy(alpha = 0.25f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = participant.name.take(1).uppercase(),
                style = FlinsTheme.typography.titleMedium,
                color = Color.White
            )

            // Status indicator dot
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor)
                    .border(1.5.dp, FlinsDarkBg, CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (participant.isHost) "${participant.name} (Host)" else participant.name,
            style = FlinsTheme.typography.caption.copy(fontSize = 11.sp),
            color = if (participant.isHost) FlinsCyan else FlinsTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================================
// NOW PLAYING IN PARTY CARD
// ============================================================================

@Composable
private fun NowPlayingPartyCard(
    track: Track?,
    isPlaying: Boolean,
    playbackPositionMs: Long,
    durationMs: Long,
    isHost: Boolean,
    repeatMode: String = "OFF",
    shuffleMode: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetPlaybackModes: (String, Boolean) -> Unit = { _, _ -> },
    onShareToChat: () -> Unit,
    onCardClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        backgroundColor = Color(0xFF202020),
        borderColor = Color(0xFF383838),
        onClick = onCardClick,
        testTag = "now_playing_party_card"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF282828))
                ) {
                    if (track != null) {
                        AsyncImage(
                            model = track.artwork,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.Center)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Track Metadata
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track?.title ?: "No track playing",
                            style = FlinsTheme.typography.titleMedium,
                            color = FlinsTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = if (track != null) "${track.artist} • In Sync" else "Add songs to queue",
                        style = FlinsTheme.typography.bodyMedium,
                        color = if (track != null) Color(0xFFBDBDBD) else FlinsTheme.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Share to chat quick button
                if (track != null) {
                    GlassIconButton(
                        onClick = onShareToChat,
                        size = 36.dp,
                        testTag = "share_track_to_chat_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share in chat",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Playback Position Slider
            val totalSec = if (durationMs > 0) (durationMs / 1000).toInt() else (track?.durationSeconds ?: 180)
            val currentSec = (playbackPositionMs / 1000).toInt().coerceIn(0, totalSec.coerceAtLeast(1))
            val sliderMax = (if (durationMs > 0) durationMs else (track?.durationSeconds?.toLong() ?: 180L) * 1000L).coerceAtLeast(1000L).toFloat()
            var isUserSeeking by remember { mutableStateOf(false) }
            var userSeekPosition by remember { mutableStateOf(0f) }

            Slider(
                value = if (isUserSeeking) userSeekPosition else playbackPositionMs.coerceIn(0L, sliderMax.toLong()).toFloat(),
                onValueChange = {
                    isUserSeeking = true
                    userSeekPosition = it
                },
                onValueChangeFinished = {
                    isUserSeeking = false
                    onSeekTo(userSeekPosition.toLong())
                },
                valueRange = 0f..sliderMax,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFBDBDBD),
                    activeTrackColor = Color(0xFFBDBDBD),
                    inactiveTrackColor = Color(0xFF383838)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("party_playback_slider")
            )

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Time position
                Text(
                    text = "%d:%02d / %d:%02d".format(currentSec / 60, currentSec % 60, totalSec / 60, totalSec % 60),
                    style = FlinsTheme.typography.caption,
                    color = FlinsTheme.colors.textMuted
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isHost) {
                        // Shuffle button
                        GlassIconButton(
                            onClick = {
                                onSetPlaybackModes(repeatMode, !shuffleMode)
                            },
                            size = 34.dp
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (shuffleMode) Color(0xFFF0F0F0) else Color(0xFF707070),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Repeat mode button
                        GlassIconButton(
                            onClick = {
                                val nextMode = when (repeatMode) {
                                    "OFF" -> "ALL"
                                    "ALL" -> "ONE"
                                    else -> "OFF"
                                }
                                onSetPlaybackModes(nextMode, shuffleMode)
                            },
                            size = 34.dp
                        ) {
                            Icon(
                                imageVector = if (repeatMode == "ONE") Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat",
                                tint = if (repeatMode != "OFF") Color(0xFFF0F0F0) else Color(0xFF707070),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Play/Pause button
                    GlassIconButton(
                        onClick = onTogglePlayPause,
                        size = 38.dp,
                        testTag = "party_play_pause_button"
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color(0xFFF0F0F0),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Next button
                    GlassIconButton(
                        onClick = onNextTrack,
                        size = 38.dp,
                        testTag = "party_next_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = Color(0xFFF0F0F0),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// QUICK REACTION BAR
// ============================================================================

@Composable
private fun QuickReactionBar(
    onReactionClick: (String) -> Unit
) {
    val reactions = listOf(
        "HEART" to "❤️",
        "FIRE" to "🔥",
        "LAUGH" to "😂",
        "WOW" to "😮",
        "SAD" to "😢"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF202020))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        reactions.forEach { (type, emoji) ->
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onReactionClick(type) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emoji,
                    fontSize = 22.sp
                )
            }
        }
    }
}

// ============================================================================
// FLOATING REACTION ANIMATION ITEM
// ============================================================================

@Composable
private fun FloatingReactionItem(
    bubble: AnimatedReactionBubble,
    onFinished: () -> Unit
) {
    val offsetY = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(bubble.id) {
        launch {
            offsetY.animateTo(
                targetValue = -350f,
                animationSpec = tween(durationMillis = 2000, easing = FastOutSlowInEasing)
            )
        }
        launch {
            delay(1200)
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 800, easing = LinearEasing)
            )
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = bubble.startOffsetX.roundToInt(),
                    y = offsetY.value.roundToInt()
                )
            }
            .alpha(alpha.value)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xD9101010))
            .border(1.dp, FlinsCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = bubble.emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = bubble.senderName,
                style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
        }
    }
}

// ============================================================================
// PARTY TAB BAR
// ============================================================================

@Composable
private fun PartyTabBar(
    selectedTab: PartyTab,
    queueCount: Int,
    chatCount: Int,
    requestCount: Int,
    memberCount: Int,
    onTabSelected: (PartyTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF202020))
            .border(1.dp, Color(0xFF383838), RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Tab 1: Queue
        PartyTabButton(
            title = "Queue ($queueCount)",
            isSelected = selectedTab == PartyTab.QUEUE,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PartyTab.QUEUE) }
        )
        // Tab 2: Chat
        PartyTabButton(
            title = "Chat ($chatCount)",
            isSelected = selectedTab == PartyTab.CHAT,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PartyTab.CHAT) }
        )
        // Tab 3: Requests
        PartyTabButton(
            title = "Reqs ($requestCount)",
            isSelected = selectedTab == PartyTab.REQUESTS,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PartyTab.REQUESTS) }
        )
        // Tab 4: Members
        PartyTabButton(
            title = "Users ($memberCount)",
            isSelected = selectedTab == PartyTab.MEMBERS,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PartyTab.MEMBERS) }
        )
    }
}

@Composable
private fun PartyTabButton(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) Color(0xFF383838) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = FlinsTheme.typography.button.copy(fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
            color = if (isSelected) Color(0xFFF0F0F0) else Color(0xFFA0A0A0),
            maxLines = 1
        )
    }
}

// ============================================================================
// TAB 1: SHARED QUEUE CONTENT
// ============================================================================

@Composable
private fun SharedQueueTabContent(
    room: Room,
    isHost: Boolean,
    onOpenSongPicker: () -> Unit,
    onImportPlaylistClick: () -> Unit = {},
    onSelectTrack: (Track, String?) -> Unit,
    onRemoveItem: (String) -> Unit,
    onMoveItem: (Int, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "UP NEXT IN PARTY (${room.sharedQueue.size})",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Import Playlist Button
                GlassButton(
                    onClick = onImportPlaylistClick,
                    variant = GlassButtonVariant.SECONDARY,
                    testTag = "import_playlist_party_button"
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Import Playlist",
                        tint = Color(0xFFF0F0F0),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import", style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold), color = Color(0xFFF0F0F0))
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Add Song Button
                GlassButton(
                    onClick = onOpenSongPicker,
                    variant = GlassButtonVariant.SECONDARY,
                    testTag = "add_to_party_queue_button"
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color(0xFFF0F0F0),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Song", style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold), color = Color(0xFFF0F0F0))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (room.sharedQueue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF202020))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(18.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = Color(0xFF707070),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Shared queue is empty",
                        style = FlinsTheme.typography.titleMedium,
                        color = Color(0xFFF0F0F0)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Anyone in the party can search and add songs!",
                        style = FlinsTheme.typography.bodyMedium,
                        color = Color(0xFFA0A0A0),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                room.sharedQueue.forEachIndexed { index, queueItem ->
                    SharedQueueRowItem(
                        index = index,
                        item = queueItem,
                        isHost = isHost,
                        totalItems = room.sharedQueue.size,
                        onSelect = { onSelectTrack(queueItem.track, queueItem.opId) },
                        onRemove = { onRemoveItem(queueItem.opId) },
                        onMoveUp = { if (index > 0) onMoveItem(index, index - 1) },
                        onMoveDown = { if (index < room.sharedQueue.size - 1) onMoveItem(index, index + 1) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedQueueRowItem(
    index: Int,
    item: RoomQueueItem,
    isHost: Boolean,
    totalItems: Int,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = Color(0xFF202020),
        borderColor = Color(0xFF383838),
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Position index
            Text(
                text = "#${index + 1}",
                style = FlinsTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFFF0F0F0),
                modifier = Modifier.width(28.dp)
            )

            // Artwork
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF282828))
            ) {
                AsyncImage(
                    model = item.track.artwork,
                    contentDescription = item.track.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.track.title,
                    style = FlinsTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFFF0F0F0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.track.artist} • Added by ${item.addedByUserName}",
                    style = FlinsTheme.typography.caption,
                    color = Color(0xFFA0A0A0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Quick Play Button for any member
            GlassIconButton(
                onClick = onSelect,
                size = 30.dp,
                testTag = "play_queue_item_${item.track.id}"
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Track",
                    tint = Color(0xFFF0F0F0),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Controls (Host only)
            if (isHost) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) {
                        GlassIconButton(onClick = onMoveUp, size = 30.dp) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", tint = Color(0xFFC0C0C0), modifier = Modifier.size(14.dp))
                        }
                    }
                    if (index < totalItems - 1) {
                        GlassIconButton(onClick = onMoveDown, size = 30.dp) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", tint = Color(0xFFC0C0C0), modifier = Modifier.size(14.dp))
                        }
                    }
                    GlassIconButton(onClick = onRemove, size = 30.dp) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFE57373), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 2: LIVE CHAT CONTENT
// ============================================================================

@Composable
private fun ChatTabContent(
    room: Room,
    isHost: Boolean,
    chatInputText: String,
    onChatInputChanged: (String) -> Unit,
    onSendChat: (String, Boolean) -> Unit,
    onDeleteMessage: (String) -> Unit
) {
    val isChatMutedForUser = room.isChatMuted && !isHost

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        // Chat Muted Notice
        if (room.isChatMuted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF282828))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeMute,
                        contentDescription = null,
                        tint = Color(0xFFA0A0A0),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isHost) "Chat is muted for participants (Host can still post)" else "Party chat is currently muted by the host.",
                        style = FlinsTheme.typography.caption,
                        color = Color(0xFFF0F0F0)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Messages list
        if (room.chatMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF202020))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Party chat is quiet... Say hello! 💬",
                    style = FlinsTheme.typography.bodyMedium,
                    color = Color(0xFFA0A0A0)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                room.chatMessages.forEach { msg ->
                    ChatMessageBubble(
                        msg = msg,
                        hostId = room.hostId,
                        isViewerHost = isHost,
                        onDelete = { onDeleteMessage(msg.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Chat input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (isChatMutedForUser) Color(0xFF181818) else Color(0xFF202020))
                .border(1.dp, Color(0xFF383838), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = chatInputText,
                onValueChange = onChatInputChanged,
                enabled = !isChatMutedForUser,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                textStyle = FlinsTheme.typography.body.copy(color = if (isChatMutedForUser) Color(0xFF707070) else Color(0xFFF0F0F0)),
                cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                decorationBox = { innerTextField ->
                    if (chatInputText.isEmpty()) {
                        Text(
                            text = if (isChatMutedForUser) "Chat muted by host" else "Say something in party...",
                            style = FlinsTheme.typography.body,
                            color = Color(0xFF707070)
                        )
                    }
                    innerTextField()
                }
            )

            // Share current track icon button
            GlassIconButton(
                onClick = {
                    if (!isChatMutedForUser) onSendChat(chatInputText, true)
                },
                size = 36.dp
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Share current song",
                    tint = if (isChatMutedForUser) Color(0xFF505050) else Color(0xFFC0C0C0),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Send Button
            GlassIconButton(
                onClick = {
                    if (!isChatMutedForUser) onSendChat(chatInputText, false)
                },
                size = 36.dp,
                testTag = "send_chat_button"
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (chatInputText.isNotBlank() && !isChatMutedForUser) Color(0xFFF0F0F0) else Color(0xFF505050),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    msg: ChatMessage,
    hostId: String,
    isViewerHost: Boolean,
    onDelete: () -> Unit
) {
    val isHost = (msg.senderId == hostId)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF282828))
                .border(1.dp, Color(0xFF383838), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = msg.senderName.take(1).uppercase(),
                style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFFF0F0F0)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = msg.senderName,
                        style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF0F0F0)
                    )
                    if (isHost) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF383838))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "HOST",
                                style = FlinsTheme.typography.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = Color(0xFFF0F0F0)
                            )
                        }
                    }
                }

                // Delete button for room host
                if (isViewerHost) {
                    GlassIconButton(
                        onClick = onDelete,
                        size = 24.dp
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Message",
                            tint = Color(0xFF707070),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Message text
            if (msg.text.isNotBlank()) {
                Text(
                    text = msg.text,
                    style = FlinsTheme.typography.body,
                    color = Color(0xFFE0E0E0)
                )
            }

            // Embedded track if shared
            if (msg.sharedTrack != null) {
                Spacer(modifier = Modifier.height(6.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    backgroundColor = Color(0xFF202020),
                    borderColor = Color(0xFF383838)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = msg.sharedTrack.artwork,
                            contentDescription = msg.sharedTrack.title,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = msg.sharedTrack.title,
                                style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFFF0F0F0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = msg.sharedTrack.artist,
                                style = FlinsTheme.typography.caption.copy(fontSize = 10.sp),
                                color = Color(0xFFA0A0A0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 3: SONG REQUESTS CONTENT
// ============================================================================

@Composable
private fun RequestsTabContent(
    requests: List<SongRequestItem>,
    isHost: Boolean,
    onOpenSongPicker: () -> Unit,
    onReview: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SONG REQUESTS (${requests.size})",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted
            )

            // Request Song Button
            GlassButton(
                onClick = onOpenSongPicker,
                variant = GlassButtonVariant.SECONDARY,
                testTag = "request_song_button"
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color(0xFFF0F0F0),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Request Song",
                    style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFFF0F0F0)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (requests.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF202020))
                    .border(1.dp, Color(0xFF383838), RoundedCornerShape(18.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFF707070),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No pending song requests",
                        style = FlinsTheme.typography.titleMedium,
                        color = Color(0xFFF0F0F0)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Anyone in the party can request songs from the catalog!",
                        style = FlinsTheme.typography.bodyMedium,
                        color = Color(0xFFA0A0A0),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                requests.forEach { request ->
                    SongRequestRowItem(
                        request = request,
                        isHost = isHost,
                        onApprove = { onReview(request.id, true) },
                        onReject = { onReview(request.id, false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SongRequestRowItem(
    request: SongRequestItem,
    isHost: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = Color(0xFF202020),
        borderColor = Color(0xFF383838)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF282828))
            ) {
                AsyncImage(
                    model = request.track.artwork,
                    contentDescription = request.track.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.track.title,
                    style = FlinsTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFFF0F0F0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${request.track.artist} • Requested by ${request.requestedByUserName}",
                    style = FlinsTheme.typography.caption,
                    color = Color(0xFFA0A0A0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isHost) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Approve Button
                    GlassIconButton(
                        onClick = onApprove,
                        size = 32.dp,
                        testTag = "approve_request_${request.id}"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Approve and add to queue",
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Reject Button
                    GlassIconButton(
                        onClick = onReject,
                        size = 32.dp,
                        testTag = "reject_request_${request.id}"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Reject",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF282828))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Pending",
                        style = FlinsTheme.typography.caption.copy(fontSize = 10.sp),
                        color = Color(0xFFA0A0A0)
                    )
                }
            }
        }
    }
}

// ============================================================================
// TAB 4: MEMBERS AND ROOM CONTROLS CONTENT
// ============================================================================

@Composable
private fun MembersTabContent(
    room: Room,
    isHost: Boolean,
    onTransferHost: (String) -> Unit,
    onKick: (String) -> Unit,
    onToggleLock: () -> Unit,
    onToggleMute: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        // Host Controls Card (only shown to host)
        if (isHost) {
            Text(
                text = "ROOM MODERATION CONTROLS",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                backgroundColor = Color(0xFF202020),
                borderColor = Color(0xFF383838)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Lock Room Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (room.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (room.isLocked) Color(0xFFF0F0F0) else Color(0xFFA0A0A0),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (room.isLocked) "Room is Locked" else "Room is Open",
                                    style = FlinsTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFFF0F0F0)
                                )
                                Text(
                                    text = if (room.isLocked) "New participants cannot join" else "Anyone with link/code can join",
                                    style = FlinsTheme.typography.caption,
                                    color = Color(0xFFA0A0A0)
                                )
                            }
                        }
                        Switch(
                            checked = room.isLocked,
                            onCheckedChange = { onToggleLock() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFF0F0F0),
                                checkedTrackColor = Color(0xFF454545),
                                uncheckedThumbColor = Color(0xFFA0A0A0),
                                uncheckedTrackColor = Color(0xFF282828)
                            )
                        )
                    }

                    HorizontalDivider(color = Color(0xFF303030), thickness = 1.dp)

                    // Mute Chat Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (room.isChatMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = if (room.isChatMuted) Color(0xFFF0F0F0) else Color(0xFFA0A0A0),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (room.isChatMuted) "Party Chat is Muted" else "Party Chat is Enabled",
                                    style = FlinsTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFFF0F0F0)
                                )
                                Text(
                                    text = if (room.isChatMuted) "Only the host can send messages" else "All participants can send messages",
                                    style = FlinsTheme.typography.caption,
                                    color = Color(0xFFA0A0A0)
                                )
                            }
                        }
                        Switch(
                            checked = room.isChatMuted,
                            onCheckedChange = { onToggleMute() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFF0F0F0),
                                checkedTrackColor = Color(0xFF454545),
                                uncheckedThumbColor = Color(0xFFA0A0A0),
                                uncheckedTrackColor = Color(0xFF282828)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // Participant List
        val uniqueParticipants = remember(room.participants) {
            room.participants.distinctBy { it.id.ifBlank { it.name } }
        }
        Text(
            text = "PARTICIPANTS (${uniqueParticipants.size}/${room.maxParticipants})",
            style = FlinsTheme.typography.overline,
            color = FlinsTheme.colors.textMuted
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            uniqueParticipants.forEach { participant ->
                MemberDetailRowItem(
                    participant = participant,
                    isViewerHost = isHost,
                    onTransferHost = { onTransferHost(participant.id) },
                    onKick = { onKick(participant.id) }
                )
            }
        }
    }
}

@Composable
private fun MemberDetailRowItem(
    participant: Participant,
    isViewerHost: Boolean,
    onTransferHost: () -> Unit,
    onKick: () -> Unit
) {
    val statusColor = when (participant.status) {
        MemberStatus.ONLINE -> Color(0xFFF0F0F0)
        MemberStatus.BUFFERING -> Color(0xFFBDBDBD)
        MemberStatus.RECONNECTING -> Color(0xFFA0A0A0)
        MemberStatus.DISCONNECTED -> Color(0xFF707070)
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = Color(0xFF202020),
        borderColor = Color(0xFF383838)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with status
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF282828))
                    .border(
                        width = if (participant.isHost) 1.5.dp else 1.dp,
                        color = if (participant.isHost) Color(0xFFF0F0F0) else Color(0xFF383838),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = participant.name.take(1).uppercase(),
                    style = FlinsTheme.typography.titleMedium,
                    color = Color(0xFFF0F0F0)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                        .border(1.dp, Color(0xFF101010), CircleShape)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = participant.name,
                        style = FlinsTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFF0F0F0),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (participant.isHost) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF383838))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "HOST",
                                style = FlinsTheme.typography.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = Color(0xFFF0F0F0)
                            )
                        }
                    }
                }
                Text(
                    text = when (participant.status) {
                        MemberStatus.ONLINE -> "Online • In sync"
                        MemberStatus.BUFFERING -> "Buffering stream..."
                        MemberStatus.RECONNECTING -> "Reconnecting..."
                        MemberStatus.DISCONNECTED -> "Disconnected"
                    },
                    style = FlinsTheme.typography.caption,
                    color = Color(0xFFA0A0A0)
                )
            }

            // Moderation actions if viewer is host and this participant is not host
            if (isViewerHost && !participant.isHost) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Transfer Host button
                    GlassIconButton(
                        onClick = onTransferHost,
                        size = 32.dp,
                        testTag = "transfer_host_${participant.id}"
                    ) {
                        Icon(
                            imageVector = Icons.Default.AdminPanelSettings,
                            contentDescription = "Make Host",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Kick button
                    GlassIconButton(
                        onClick = onKick,
                        size = 32.dp,
                        testTag = "kick_member_${participant.id}"
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonRemove,
                            contentDescription = "Remove participant",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// ============================================================================
// ADD SONG TO ROOM QUEUE SHEET
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSongToRoomQueueSheet(
    availableTracks: List<Track>,
    sheetTitle: String = "Add Song to Party Queue",
    onTrackSelected: (Track) -> Unit,
    onImportPlaylistClick: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredTracks = remember(searchQuery, availableTracks) {
        if (searchQuery.isBlank()) availableTracks
        else availableTracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FlinsDarkBg.copy(alpha = 0.96f),
        contentColor = FlinsTheme.colors.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sheetTitle,
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
                GlassButton(
                    onClick = {
                        onDismiss()
                        onImportPlaylistClick()
                    },
                    variant = GlassButtonVariant.SECONDARY,
                    testTag = "sheet_import_playlist_button"
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Import Playlist",
                        tint = Color(0xFFF0F0F0),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import", style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold), color = Color(0xFFF0F0F0))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x18FFFFFF))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = FlinsCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = FlinsTheme.typography.body.copy(color = Color.White),
                        cursorBrush = SolidColor(FlinsCyan),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text("Search tracks or artists...", style = FlinsTheme.typography.body, color = FlinsTheme.colors.textMuted)
                            }
                            innerTextField()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tracks list
            LazyColumn(
                modifier = Modifier.height(350.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredTracks) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTrackSelected(track) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = track.artwork,
                            contentDescription = track.title,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = FlinsTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = FlinsTheme.typography.caption,
                                color = FlinsTheme.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            tint = FlinsCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// INVITE ROOM SHEET
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteRoomSheet(
    roomCode: String,
    roomName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var copiedCode by remember { mutableStateOf(false) }
    var copiedLink by remember { mutableStateOf(false) }

    val deepLink = "anyfy://together?code=$roomCode"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FlinsDarkBg.copy(alpha = 0.98f),
        contentColor = FlinsTheme.colors.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Invite Friends to Party", style = FlinsTheme.typography.title, color = FlinsTheme.colors.textPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Friends can join via room code or instant deep link", style = FlinsTheme.typography.bodyMedium, color = FlinsTheme.colors.textSecondary)

            Spacer(modifier = Modifier.height(24.dp))

            // Room Code Display Box
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                backgroundColor = Color(0xFF202020),
                borderColor = FlinsCyan.copy(alpha = 0.4f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("PARTY CODE", style = FlinsTheme.typography.overline, color = FlinsTheme.colors.textMuted)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = roomCode,
                        style = FlinsTheme.typography.hero.copy(fontSize = 32.sp, letterSpacing = 3.sp),
                        color = FlinsCyan
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Copy Code
                        GlassButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Anyfy Code", roomCode))
                                copiedCode = true
                            },
                            variant = GlassButtonVariant.SECONDARY
                        ) {
                            Icon(if (copiedCode) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, tint = FlinsCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (copiedCode) "Copied!" else "Copy Code", style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
                        }

                        // Copy Link
                        GlassButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Anyfy Invite Link", deepLink))
                                copiedLink = true
                            },
                            variant = GlassButtonVariant.SECONDARY
                        ) {
                            Icon(if (copiedLink) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, tint = FlinsCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (copiedLink) "Copied!" else "Copy Link", style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Android Share Sheet Action
            GlassButton(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Join my Anyfy Party: $roomName")
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Join my listening party on Anyfy!\nRoom Code: $roomCode\nOr tap link: $deepLink"
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Anyfy Party Invite"))
                },
                variant = GlassButtonVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = FlinsDarkBg, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share Invite Link", style = FlinsTheme.typography.button, color = FlinsDarkBg)
            }
        }
    }
}

// ============================================================================
// OUT OF ROOM DISCOVERY & JOIN CARDS
// ============================================================================

@Composable
private fun OutOfRoomContent(
    publicRooms: List<PublicRoomSummary> = emptyList(),
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onJoinDirect: (String) -> Unit = {},
    onCreateRoomClick: () -> Unit,
    onJoinRoomClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredRooms = remember(searchQuery, publicRooms) {
        if (searchQuery.isBlank()) publicRooms
        else publicRooms.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true) ||
            it.hostName.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "SHARED FREQUENCIES",
                    style = FlinsTheme.typography.overline,
                    color = FlinsTheme.colors.textMuted
                )
                Text(
                    text = "Listen Together",
                    style = FlinsTheme.typography.hero,
                    color = FlinsTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Experience synchronized sonic streams in real-time with friends and collaborators.",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Card 1: CREATE ROOM
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    backgroundColor = Color(0xFF202020),
                    borderColor = Color(0xFF383838),
                    onClick = onCreateRoomClick,
                    testTag = "create_room_card"
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF282828))
                                .border(1.dp, Color(0xFF383838), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GroupAdd,
                                contentDescription = null,
                                tint = Color(0xFFF0F0F0),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Create Room",
                                style = FlinsTheme.typography.title,
                                color = FlinsTheme.colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Host a session, invite friends, and guide the musical journey.",
                                style = FlinsTheme.typography.bodyMedium,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Card 2: JOIN ROOM
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    backgroundColor = Color(0xFF202020),
                    borderColor = Color(0xFF383838),
                    onClick = onJoinRoomClick,
                    testTag = "join_room_card"
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF282828))
                                .border(1.dp, Color(0xFF383838), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MeetingRoom,
                                contentDescription = null,
                                tint = Color(0xFFF0F0F0),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Join with Code",
                                style = FlinsTheme.typography.title,
                                color = FlinsTheme.colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Enter a 6-digit room code to tune into a live party stream.",
                                style = FlinsTheme.typography.bodyMedium,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Public Party Rooms Discovery Header with Refresh
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE PUBLIC ROOMS (${filteredRooms.size})",
                        style = FlinsTheme.typography.overline,
                        color = FlinsTheme.colors.textMuted
                    )
                    GlassIconButton(
                        onClick = onRefresh,
                        size = 32.dp
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh public rooms",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search Bar for Public Rooms
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF202020))
                        .border(1.dp, Color(0xFF383838), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFFA0A0A0),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = FlinsTheme.typography.body.copy(color = Color(0xFFF0F0F0)),
                            cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search public party rooms...",
                                        style = FlinsTheme.typography.body,
                                        color = Color(0xFF707070)
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (filteredRooms.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF202020))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(16.dp))
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = null,
                                tint = Color(0xFF707070),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "No matching rooms" else "No public rooms active",
                                style = FlinsTheme.typography.titleMedium,
                                color = Color(0xFFF0F0F0)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Create a public room and invite friends to join!",
                                style = FlinsTheme.typography.bodyMedium,
                                color = Color(0xFFA0A0A0),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        filteredRooms.forEach { roomSummary ->
                            PublicRoomCard(
                                summary = roomSummary,
                                onJoin = { onJoinDirect(roomSummary.code) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicRoomCard(
    summary: PublicRoomSummary,
    onJoin: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        backgroundColor = Color(0xFF202020),
        borderColor = Color(0xFF383838),
        onClick = onJoin
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summary.name,
                        style = FlinsTheme.typography.titleMedium,
                        color = Color(0xFFF0F0F0),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (summary.isLocked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = Color(0xFFA0A0A0),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                if (summary.description.isNotBlank()) {
                    Text(
                        text = summary.description,
                        style = FlinsTheme.typography.caption,
                        color = Color(0xFFA0A0A0),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                val trackTitle = summary.currentTrack?.title
                Text(
                    text = if (trackTitle != null) "Playing: $trackTitle • Host: ${summary.hostName}" else "Host: ${summary.hostName}",
                    style = FlinsTheme.typography.caption,
                    color = Color(0xFF707070),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF282828))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${summary.participantCount}/${summary.maxParticipants}",
                        style = FlinsTheme.typography.metadata,
                        color = Color(0xFFF0F0F0)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                GlassButton(
                    onClick = onJoin,
                    variant = GlassButtonVariant.SECONDARY
                ) {
                    Text("Join", style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold), color = Color(0xFFF0F0F0))
                }
            }
        }
    }
}

// ============================================================================
// CREATE & JOIN BOTTOM SHEETS
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRoomBottomSheet(
    defaultHostName: String,
    onCreateRoom: (name: String, isPublic: Boolean) -> Unit = { _, _ -> },
    onCreateAdvancedRoom: (name: String, description: String, isPublic: Boolean, maxParticipants: Int) -> Unit = { name, _, isPub, _ -> onCreateRoom(name, isPub) },
    onDismiss: () -> Unit
) {
    var roomName by remember { mutableStateOf("$defaultHostName's Room") }
    var roomDescription by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(true) }
    var maxParticipants by remember { mutableStateOf(20) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF181818),
        contentColor = Color(0xFFF0F0F0),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF383838))
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text("Create a Room", style = FlinsTheme.typography.title, color = Color(0xFFF0F0F0))
            Spacer(modifier = Modifier.height(4.dp))
            Text("Host a synchronized live listening space", style = FlinsTheme.typography.bodyMedium, color = Color(0xFFA0A0A0))
            Spacer(modifier = Modifier.height(20.dp))

            // Room Name Input
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Room Name", style = FlinsTheme.typography.label, color = Color(0xFFA0A0A0))
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF202020))
                        .border(1.dp, Color(0xFF383838), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = roomName,
                        onValueChange = { roomName = it },
                        modifier = Modifier.fillMaxWidth().testTag("room_name_input"),
                        textStyle = FlinsTheme.typography.body.copy(color = Color(0xFFF0F0F0)),
                        cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Room Description Input
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Description (Optional)", style = FlinsTheme.typography.label, color = Color(0xFFA0A0A0))
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF202020))
                        .border(1.dp, Color(0xFF383838), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = roomDescription,
                        onValueChange = { roomDescription = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = FlinsTheme.typography.body.copy(color = Color(0xFFF0F0F0)),
                        cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (roomDescription.isEmpty()) {
                                Text("e.g. Chill electronic vibes & study session", style = FlinsTheme.typography.body, color = Color(0xFF707070))
                            }
                            innerTextField()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Max Participants Selector
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Max Participants", style = FlinsTheme.typography.label, color = Color(0xFFA0A0A0))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(5, 10, 20, 50).forEach { limit ->
                        val isSelected = (maxParticipants == limit)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFF383838) else Color(0xFF202020))
                                .border(1.dp, if (isSelected) Color(0xFF707070) else Color(0xFF303030), RoundedCornerShape(10.dp))
                                .clickable { maxParticipants = limit }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$limit",
                                style = FlinsTheme.typography.button.copy(fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                color = if (isSelected) Color(0xFFF0F0F0) else Color(0xFFA0A0A0)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy selection
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Privacy", style = FlinsTheme.typography.label, color = Color(0xFFA0A0A0))
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { isPublic = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isPublic,
                        onClick = { isPublic = true },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFF0F0F0), unselectedColor = Color(0xFF707070))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Public (Discoverable by nearby listeners)", style = FlinsTheme.typography.body, color = Color(0xFFF0F0F0))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { isPublic = false }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !isPublic,
                        onClick = { isPublic = false },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFF0F0F0), unselectedColor = Color(0xFF707070))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Private (Invite code only)", style = FlinsTheme.typography.body, color = Color(0xFFF0F0F0))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            GlassButton(
                onClick = {
                    onCreateAdvancedRoom(roomName.trim(), roomDescription.trim(), isPublic, maxParticipants)
                },
                variant = GlassButtonVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                testTag = "confirm_create_room_button"
            ) {
                Text("Create Room", style = FlinsTheme.typography.button, color = Color(0xFF101010))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinRoomBottomSheet(
    errorMessage: String?,
    onJoinRoom: (code: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF181818),
        contentColor = Color(0xFFF0F0F0),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF383838))
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text("Join a Room", style = FlinsTheme.typography.title, color = Color(0xFFF0F0F0))
            Spacer(modifier = Modifier.height(4.dp))
            Text("Enter room code to join synchronized audio session", style = FlinsTheme.typography.bodyMedium, color = Color(0xFFA0A0A0))
            Spacer(modifier = Modifier.height(24.dp))

            // Code input with Paste action
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(FlinsTheme.radii.md))
                    .background(Color(0xFF202020))
                    .border(
                        1.dp,
                        if (errorMessage != null) Color(0xFFE57373) else Color(0xFF383838),
                        RoundedCornerShape(FlinsTheme.radii.md)
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.uppercase() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("room_code_input"),
                        textStyle = FlinsTheme.typography.titleMedium.copy(
                            color = Color(0xFFF0F0F0),
                            letterSpacing = 2.sp
                        ),
                        cursorBrush = SolidColor(Color(0xFFBDBDBD)),
                        singleLine = true
                    )
                    GlassIconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                codeInput = clip.getItemAt(0).text.toString().trim().uppercase()
                            }
                        },
                        size = 36.dp
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste code",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    style = FlinsTheme.typography.bodyMedium,
                    color = Color(0xFFE57373)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            GlassButton(
                onClick = {
                    if (codeInput.isNotBlank()) {
                        val normalized = com.example.service.integrations.SocketIoRealtimeService.normalizeRoomCode(codeInput)
                        onJoinRoom(normalized)
                    }
                },
                variant = GlassButtonVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                testTag = "confirm_join_room_button"
            ) {
                Text("Join Room", style = FlinsTheme.typography.button, color = Color(0xFF101010))
            }
        }
    }
}

// ============================================================================
// TOGETHER MINI SESSION BAR (PERSISTENT ACROSS OTHER TABS)
// ============================================================================

@Composable
fun TogetherMiniSessionBar(
    room: Room,
    onReturnToRoom: () -> Unit,
    onLeaveRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = Color(0xFF181818),
        borderColor = Color(0xFF383838),
        onClick = onReturnToRoom
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pulsing dot / indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF0F0F0))
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "TOGETHER",
                            style = FlinsTheme.typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFFBDBDBD)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• ${room.participants.size} listening",
                            style = FlinsTheme.typography.caption.copy(fontSize = 10.sp),
                            color = Color(0xFFA0A0A0)
                        )
                    }
                    Text(
                        text = room.name,
                        style = FlinsTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFF0F0F0),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Return to party pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF282828))
                        .clickable(onClick = onReturnToRoom)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Open Party",
                        style = FlinsTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF0F0F0)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Leave session
                GlassIconButton(
                    onClick = onLeaveRoom,
                    size = 32.dp
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Leave party",
                        tint = Color(0xFFE57373),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
