package com.example.ui.screens.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.UserEntity
import com.example.model.VisualQuality
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassButtonVariant
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconButton
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsEmerald
import com.example.ui.theme.FlinsError
import com.example.ui.theme.FlinsTheme
import com.example.ui.theme.FlinsViolet

@Composable
fun SettingsScreen(
    userProfile: UserEntity,
    userEmail: String? = null,
    isAdmin: Boolean = false,
    onBack: () -> Unit,
    onEditNameClick: () -> Unit,
    onVisualQualityChange: (VisualQuality) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onClearCacheClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onOpenAdminDashboard: () -> Unit = {},
    ytAccounts: List<com.example.service.integrations.ytmusic.YtAccount> = emptyList(),
    activeYtAccount: com.example.service.integrations.ytmusic.YtAccount? = null,
    isSyncingYtLibrary: Boolean = false,
    onAddYouTubeAccount: ((cookie: String, onFinished: (Boolean, String) -> Unit) -> Unit)? = null,
    onSwitchYouTubeAccount: ((String) -> Unit)? = null,
    onRemoveYouTubeAccount: ((String) -> Unit)? = null,
    onSyncYouTubeLibrary: (() -> Unit)? = null,
    isSponsorBlockEnabled: Boolean = true,
    onToggleSponsorBlock: ((Boolean) -> Unit)? = null,
    onOpenEqualizer: (() -> Unit)? = null,
    onCheckForUpdates: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showYouTubeLoginSheet by remember { mutableStateOf(false) }
    var showAccountSwitchDialog by remember { mutableStateOf(false) }
    var cacheClearedBanner by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val currentQuality = remember(userProfile.visualQuality) {
        try {
            VisualQuality.valueOf(userProfile.visualQuality)
        } catch (_: Exception) {
            VisualQuality.BALANCED
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = {
                Text(
                    text = "Sign Out",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to sign out of Anyfy?",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        onSignOutClick()
                    }
                ) {
                    Text("Sign Out", color = FlinsError, style = FlinsTheme.typography.button)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
                }
            },
            containerColor = FlinsDarkBg,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showYouTubeLoginSheet) {
        com.example.ui.components.YouTubeLoginSheet(
            onDismiss = { showYouTubeLoginSheet = false },
            onCookieCaptured = { cookie, onFinished ->
                onAddYouTubeAccount?.invoke(cookie, onFinished)
            }
        )
    }

    if (showAccountSwitchDialog && ytAccounts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showAccountSwitchDialog = false },
            title = {
                Text("Switch YouTube Music Account", style = FlinsTheme.typography.title, color = Color(0xFFF0F0F0))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ytAccounts.forEach { acc ->
                        val isSelected = acc.id == activeYtAccount?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF333333) else Color(0xFF1E1E22))
                                .clickable {
                                    onSwitchYouTubeAccount?.invoke(acc.id)
                                    showAccountSwitchDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(acc.name, style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFF0F0F0))
                                Text(acc.email.ifBlank { "Channel" }, style = FlinsTheme.typography.metadata, color = Color(0xFF9E9E9E))
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = FlinsCyan, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccountSwitchDialog = false
                    showYouTubeLoginSheet = true
                }) {
                    Text("Add Account", color = Color(0xFFE0E0E0), style = FlinsTheme.typography.button)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccountSwitchDialog = false }) {
                    Text("Cancel", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
                }
            },
            containerColor = Color(0xFF141416),
            shape = RoundedCornerShape(24.dp)
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(
                    onClick = onBack,
                    size = 44.dp,
                    testTag = "settings_back_button"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = FlinsTheme.colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "PREFERENCES",
                        style = FlinsTheme.typography.overline,
                        color = FlinsCyan
                    )
                    Text(
                        text = "Settings",
                        style = FlinsTheme.typography.hero,
                        color = FlinsTheme.colors.textPrimary
                    )
                }
            }
        }

        // Cache Cleared Feedback Banner
        item {
            AnimatedVisibility(
                visible = cacheClearedBanner,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(FlinsEmerald.copy(alpha = 0.16f))
                        .border(1.dp, FlinsEmerald.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = FlinsEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Stream cache & temporary memory cleared.",
                            style = FlinsTheme.typography.bodyMedium,
                            color = FlinsEmerald
                        )
                    }
                }
            }
        }

        // 1. Account Section
        item {
            Text(
                text = "ACCOUNT",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // User Avatar
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF454545), Color(0xFF282828)))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = userProfile.name.take(1).uppercase(),
                                style = FlinsTheme.typography.title.copy(fontSize = 22.sp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userProfile.name,
                                style = FlinsTheme.typography.title,
                                color = FlinsTheme.colors.textPrimary
                            )
                            Text(
                                text = userEmail ?: "Google Account",
                                style = FlinsTheme.typography.bodyMedium,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                        GlassIconButton(
                            onClick = onEditNameClick,
                            size = 38.dp,
                            testTag = "settings_edit_name_button"
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit name",
                                tint = Color(0xFFC0C0C0),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sign Out Button
                    GlassButton(
                        onClick = { showSignOutDialog = true },
                        variant = GlassButtonVariant.SECONDARY,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "settings_sign_out_button"
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = FlinsError,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sign Out",
                            style = FlinsTheme.typography.button,
                            color = FlinsError
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 1.5. YouTube Music Integration (SimpMusic Engine)
        item(key = "settings_youtube_music_integration") {
            Text(
                text = "YOUTUBE MUSIC INTEGRATION",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF282828)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!activeYtAccount?.avatarUrl.isNullOrBlank()) {
                                coil.compose.AsyncImage(
                                    model = activeYtAccount?.avatarUrl,
                                    contentDescription = "Avatar",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (activeYtAccount != null) FlinsCyan else Color(0xFF888888),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeYtAccount?.name ?: "Not Connected",
                                style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = FlinsTheme.colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (activeYtAccount != null) {
                                    activeYtAccount.email.ifBlank { "YouTube Music Session Active" }
                                } else {
                                    "Connect to stream private tracks & sync your library"
                                },
                                style = FlinsTheme.typography.metadata,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }

                        if (activeYtAccount != null) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (activeYtAccount != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassButton(
                                onClick = { onSyncYouTubeLibrary?.invoke() },
                                modifier = Modifier.weight(1f),
                                variant = GlassButtonVariant.PRIMARY
                            ) {
                                if (isSyncingYtLibrary) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        color = FlinsDarkBg,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Syncing...", style = FlinsTheme.typography.button, color = FlinsDarkBg)
                                } else {
                                    Text("Sync Library", style = FlinsTheme.typography.button, color = FlinsDarkBg)
                                }
                            }

                            if (ytAccounts.size > 1) {
                                GlassButton(
                                    onClick = { showAccountSwitchDialog = true },
                                    modifier = Modifier.weight(1f),
                                    variant = GlassButtonVariant.SECONDARY
                                ) {
                                    Text("Switch (${ytAccounts.size})", style = FlinsTheme.typography.button, color = FlinsTheme.colors.textPrimary)
                                }
                            }

                            GlassButton(
                                onClick = { onRemoveYouTubeAccount?.invoke(activeYtAccount.id) },
                                modifier = Modifier.weight(0.8f),
                                variant = GlassButtonVariant.SECONDARY
                            ) {
                                Text("Disconnect", style = FlinsTheme.typography.button, color = FlinsError)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassButton(
                                onClick = { showYouTubeLoginSheet = true },
                                modifier = Modifier.fillMaxWidth(),
                                variant = GlassButtonVariant.PRIMARY
                            ) {
                                Text("Sign In (Web)", style = FlinsTheme.typography.button, color = FlinsDarkBg)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 2. Together Listening Server Admin Console (Admin Only)
        if (isAdmin) {
            item {
                Text(
                    text = "ADMINISTRATION",
                    style = FlinsTheme.typography.overline,
                    color = Color(0xFFF0F0F0),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    backgroundColor = Color(0xFF202020),
                    borderColor = Color(0xFF383838)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = Color(0xFFC0C0C0),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Together Servers Console",
                                    style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = FlinsTheme.colors.textPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Manage live Socket.IO cluster endpoints, failovers & active routing without APK updates",
                                style = FlinsTheme.typography.metadata,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        GlassButton(
                            onClick = onOpenAdminDashboard,
                            variant = GlassButtonVariant.PRIMARY,
                            testTag = "settings_admin_dashboard_button"
                        ) {
                            Text(
                                text = "Manage",
                                style = FlinsTheme.typography.button,
                                color = FlinsDarkBg
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // 3. Liquid Glass Environment & Visuals
        item {
            Text(
                text = "VISUAL ENVIRONMENT",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = FlinsCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Dynamic Backdrop Quality",
                                style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = FlinsTheme.colors.textPrimary
                            )
                            Text(
                                text = "Controls GPU fluid canvas complexity & blur quality",
                                style = FlinsTheme.typography.metadata,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VisualQuality.entries.forEach { quality ->
                            val isSelected = quality == currentQuality
                            val cardBg = if (isSelected) Color(0xFF454545) else Color(0xFF202020)
                            val borderColor = if (isSelected) Color(0xFFBDBDBD) else Color(0xFF383838)
                            val textColor = if (isSelected) Color(0xFFF0F0F0) else FlinsTheme.colors.textSecondary

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quality_option_${quality.name.lowercase()}")
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(cardBg)
                                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                                    .clickable { onVisualQualityChange(quality) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = quality.label,
                                    style = FlinsTheme.typography.label,
                                    color = textColor,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 3. Audio & Streaming Pipeline
        item {
            Text(
                text = "AUDIO & STREAMING ENGINE",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = FlinsCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Stream Quality",
                                style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = FlinsTheme.colors.textPrimary
                            )
                            Text(
                                text = "Up to 320 kbps High Fidelity Audio",
                                style = FlinsTheme.typography.metadata,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x14FFFFFF))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "Extractor Pipeline",
                                style = FlinsTheme.typography.label,
                                color = FlinsTheme.colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "On-device embedded yt-dlp Python runtime + native Android MediaPlayer NuPlayer engine.",
                                style = FlinsTheme.typography.metadata,
                                color = FlinsTheme.colors.textMuted
                            )
                        }
                    }

                    if (onOpenEqualizer != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x14FFFFFF))
                                .clickable { onOpenEqualizer() }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color(0xFFC0C0C0),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "System Equalizer",
                                    style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = FlinsTheme.colors.textPrimary
                                )
                                Text(
                                    text = "Android AudioFX equalizer & sound tuning",
                                    style = FlinsTheme.typography.metadata,
                                    color = FlinsTheme.colors.textMuted
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = FlinsTheme.colors.textMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    if (onToggleSponsorBlock != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x14FFFFFF))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "SponsorBlock Integration",
                                    style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = FlinsTheme.colors.textPrimary
                                )
                                Text(
                                    text = "Auto-skip sponsor, filler, intro & outro segments",
                                    style = FlinsTheme.typography.metadata,
                                    color = FlinsTheme.colors.textMuted
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = isSponsorBlockEnabled,
                                onCheckedChange = onToggleSponsorBlock,
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    checkedThumbColor = FlinsDarkBg,
                                    checkedTrackColor = Color(0xFFF0F0F0),
                                    uncheckedThumbColor = Color(0xFF888888),
                                    uncheckedTrackColor = Color(0xFF282828)
                                )
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }


        // 4. Storage & Cache
        item {
            Text(
                text = "STORAGE & CACHE",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Stream Cache",
                            style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = FlinsTheme.colors.textPrimary
                        )
                        Text(
                            text = "In-memory resolved stream URLs & artwork cache",
                            style = FlinsTheme.typography.metadata,
                            color = FlinsTheme.colors.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    GlassButton(
                        onClick = {
                            onClearCacheClick()
                            cacheClearedBanner = true
                        },
                        variant = GlassButtonVariant.SECONDARY,
                        testTag = "clear_cache_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = FlinsCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clear",
                            style = FlinsTheme.typography.button,
                            color = FlinsCyan
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }


        // 5. About FlinsMusic
        item {
            Text(
                text = "ABOUT",
                style = FlinsTheme.typography.overline,
                color = FlinsTheme.colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = FlinsCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Anyfy v${com.example.BuildConfig.VERSION_NAME} (Build ${com.example.BuildConfig.VERSION_CODE})",
                            style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = FlinsTheme.colors.textPrimary
                        )
                    }
                    if (onCheckForUpdates != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onCheckForUpdates() }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Check for Updates",
                                tint = FlinsCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Check for Updates",
                                    style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = FlinsTheme.colors.textPrimary
                                )
                                Text(
                                    text = "Verify latest GitHub Releases version",
                                    style = FlinsTheme.typography.metadata,
                                    color = Color(0xFFBDBDBD)
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color(0xFFBDBDBD),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "A fluid, living audio environment with real-time listening rooms, reactive liquid glass visuals, and on-device streaming.",
                        style = FlinsTheme.typography.metadata,
                        color = FlinsTheme.colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                com.example.service.diagnostics.PlaybackDiagnosticLogger.exportDiagnostics(context)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Export Diagnostics",
                            tint = Color(0xFFF0F0F0),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Export Playback Diagnostics",
                                style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = FlinsTheme.colors.textPrimary
                            )
                            Text(
                                text = "Share safe, sanitized streaming logs via Share Sheet",
                                style = FlinsTheme.typography.metadata,
                                color = Color(0xFFBDBDBD)
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFFBDBDBD),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(120.dp)) // Clearance for bottom navigation/player
        }
    }
}
