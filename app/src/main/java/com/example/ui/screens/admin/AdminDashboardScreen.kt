package com.example.ui.screens.admin

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ServerConnectionStatus
import com.example.model.SocketServerConfig
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
import com.example.ui.theme.FlinsWarning
import com.example.viewmodel.AdminDashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    viewModel: AdminDashboardViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val servers by viewModel.servers.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val testingServerId by viewModel.testingServerId.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<SocketServerConfig?>(null) }
    var serverToDelete by remember { mutableStateOf<SocketServerConfig?>(null) }

    // Access Denied Screen: If user is not authorized, block dashboard access completely
    if (!isAdmin) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(FlinsDarkBg)
                .statusBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(FlinsError.copy(alpha = 0.15f))
                        .border(1.dp, FlinsError.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Access Denied",
                        tint = FlinsError,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Access Restricted",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "This console is reserved for verified Anyfy administrators. Please sign in with an authorized Google administrator account to manage Together Listening servers.",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                GlassButton(
                    onClick = onBack,
                    variant = GlassButtonVariant.SECONDARY,
                    testTag = "admin_access_denied_back_button"
                ) {
                    Text(
                        text = "Return to Settings",
                        style = FlinsTheme.typography.button,
                        color = FlinsCyan
                    )
                }
            }
        }
        return
    }

    // Delete Confirmation Dialog
    if (serverToDelete != null) {
        val srv = serverToDelete!!
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            containerColor = FlinsDarkBg.copy(alpha = 0.98f),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Remove Server?",
                    style = FlinsTheme.typography.title,
                    color = FlinsTheme.colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove '${srv.name}' (${srv.url})? This cannot be undone.",
                    style = FlinsTheme.typography.bodyMedium,
                    color = FlinsTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteServer(srv.id)
                        serverToDelete = null
                    }
                ) {
                    Text("Delete", color = FlinsError, style = FlinsTheme.typography.button)
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text("Cancel", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
                }
            }
        )
    }

    // Add Server Dialog
    if (showAddDialog) {
        ServerFormDialog(
            title = "Add Together Listening Server",
            initialName = "",
            initialUrl = "https://",
            initialEnabled = true,
            initialActive = false,
            onDismiss = { showAddDialog = false },
            onSubmit = { name, url, isEnabled, isActive ->
                viewModel.addServer(name, url, isEnabled, isActive)
                showAddDialog = false
            }
        )
    }

    // Edit Server Dialog
    if (editingServer != null) {
        val srv = editingServer!!
        ServerFormDialog(
            title = "Edit Server Endpoint",
            initialName = srv.name,
            initialUrl = srv.url,
            initialEnabled = srv.isEnabled,
            initialActive = srv.isActive,
            isEditing = true,
            onDismiss = { editingServer = null },
            onSubmit = { name, url, isEnabled, _ ->
                viewModel.updateServer(srv.id, name, url, isEnabled)
                editingServer = null
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlinsDarkBg)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Top Bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassIconButton(
                        onClick = onBack,
                        size = 44.dp,
                        testTag = "admin_back_button"
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = FlinsTheme.colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = FlinsCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ADMINISTRATION CONSOLE",
                                style = FlinsTheme.typography.overline,
                                color = FlinsCyan
                            )
                        }
                        Text(
                            text = "Socket.IO Clusters",
                            style = FlinsTheme.typography.hero,
                            color = FlinsTheme.colors.textPrimary
                        )
                    }
                    GlassIconButton(
                        onClick = { viewModel.refreshConfig() },
                        size = 44.dp,
                        testTag = "admin_refresh_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = FlinsCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Notification / Feedback Banner
            item {
                AnimatedVisibility(
                    visible = userMessage != null || errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val isErr = errorMessage != null
                    val msg = errorMessage ?: userMessage ?: ""
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isErr) FlinsError.copy(alpha = 0.15f) else FlinsEmerald.copy(alpha = 0.15f))
                            .border(1.dp, if (isErr) FlinsError.copy(alpha = 0.4f) else FlinsEmerald.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isErr) Icons.Default.ErrorOutline else Icons.Default.Check,
                                contentDescription = null,
                                tint = if (isErr) FlinsError else FlinsEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = msg,
                                style = FlinsTheme.typography.bodyMedium,
                                color = if (isErr) FlinsError else FlinsEmerald
                            )
                        }
                    }
                }
            }

            // Active Server Highlight Card
            item {
                Text(
                    text = "ACTIVE PRODUCTION CLUSTER",
                    style = FlinsTheme.typography.overline,
                    color = FlinsTheme.colors.textMuted,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    backgroundColor = Color(0xFF202020),
                    borderColor = Color(0xFF383838)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(FlinsEmerald)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ACTIVE IN APP",
                                    style = FlinsTheme.typography.overline.copy(fontWeight = FontWeight.Bold),
                                    color = FlinsEmerald
                                )
                            }
                            StatusBadge(status = activeServer.status, latencyMs = activeServer.latencyMs)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = activeServer.name,
                            style = FlinsTheme.typography.title,
                            color = FlinsTheme.colors.textPrimary
                        )
                        Text(
                            text = activeServer.url,
                            style = FlinsTheme.typography.bodyMedium,
                            color = FlinsCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            GlassButton(
                                onClick = { viewModel.testConnection(activeServer) },
                                variant = GlassButtonVariant.SECONDARY,
                                testTag = "test_active_server_button"
                            ) {
                                if (testingServerId == activeServer.id) {
                                    CircularProgressIndicator(
                                        color = FlinsCyan,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = FlinsCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Connection", style = FlinsTheme.typography.button, color = FlinsCyan)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // All Configured Servers Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "CONFIGURED ENDPOINTS (${servers.size})",
                            style = FlinsTheme.typography.overline,
                            color = FlinsTheme.colors.textMuted
                        )
                        Text(
                            text = "Together Listening Servers",
                            style = FlinsTheme.typography.title,
                            color = FlinsTheme.colors.textPrimary
                        )
                    }
                    GlassButton(
                        onClick = { showAddDialog = true },
                        variant = GlassButtonVariant.PRIMARY,
                        testTag = "add_server_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = FlinsDarkBg,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Server", style = FlinsTheme.typography.button, color = FlinsDarkBg)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Server Items
            items(servers, key = { it.id }) { srv ->
                ServerItemCard(
                    server = srv,
                    isTesting = testingServerId == srv.id,
                    onTestClick = { viewModel.testConnection(srv) },
                    onActivateClick = { viewModel.activateServer(srv.id) },
                    onEditClick = { editingServer = srv },
                    onDeleteClick = { serverToDelete = srv },
                    onToggleEnabled = { enabled ->
                        viewModel.updateServer(srv.id, srv.name, srv.url, enabled)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun ServerItemCard(
    server: SocketServerConfig,
    isTesting: Boolean,
    onTestClick: () -> Unit,
    onActivateClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    val borderColor = if (server.isActive) Color(0xFFBDBDBD) else Color(0xFF383838)
    val bgColor = if (server.isActive) Color(0xFF282828) else Color(0xFF202020)

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        backgroundColor = bgColor,
        borderColor = borderColor
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = if (server.isActive) FlinsCyan else FlinsTheme.colors.textMuted,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.name,
                            style = FlinsTheme.typography.titleMedium,
                            color = FlinsTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (server.isActive) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                    .background(FlinsCyan.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    style = FlinsTheme.typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = FlinsCyan
                                )
                            }
                        }
                    }
                    Text(
                        text = server.url,
                        style = FlinsTheme.typography.caption,
                        color = FlinsTheme.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                StatusBadge(status = server.status, latencyMs = server.latencyMs)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Enabled switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (server.isEnabled) "Enabled" else "Disabled",
                        style = FlinsTheme.typography.caption,
                        color = if (server.isEnabled) FlinsTheme.colors.textPrimary else FlinsTheme.colors.textMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = server.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FlinsDarkBg,
                            checkedTrackColor = FlinsCyan,
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color.DarkGray
                        )
                    )
                }

                // Control buttons: Test, Activate, Edit, Delete
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassIconButton(
                        onClick = onTestClick,
                        size = 36.dp,
                        testTag = "test_server_${server.id}"
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                color = FlinsCyan,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = "Test connection",
                                tint = FlinsCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (!server.isActive) {
                        GlassIconButton(
                            onClick = onActivateClick,
                            size = 36.dp,
                            testTag = "activate_server_${server.id}"
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Set Active",
                                tint = if (server.isEnabled) FlinsEmerald else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    GlassIconButton(
                        onClick = onEditClick,
                        size = 36.dp,
                        testTag = "edit_server_${server.id}"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = FlinsTheme.colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (!server.isActive) {
                        GlassIconButton(
                            onClick = onDeleteClick,
                            size = 36.dp,
                            testTag = "delete_server_${server.id}"
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = FlinsError,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    status: ServerConnectionStatus,
    latencyMs: Long?
) {
    val (label, bg, color) = when (status) {
        ServerConnectionStatus.CONNECTED -> {
            val text = if (latencyMs != null) "Connected • ${latencyMs}ms" else "Connected"
            Triple(text, FlinsEmerald.copy(alpha = 0.18f), FlinsEmerald)
        }
        ServerConnectionStatus.DISCONNECTED -> Triple("Disconnected", Color.White.copy(alpha = 0.12f), Color.LightGray)
        ServerConnectionStatus.UNREACHABLE -> Triple("Unreachable", FlinsError.copy(alpha = 0.18f), FlinsError)
        ServerConnectionStatus.NOT_TESTED -> Triple("Not Tested", Color.White.copy(alpha = 0.08f), FlinsTheme.colors.textMuted)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FlinsTheme.radii.pill))
            .background(bg)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(FlinsTheme.radii.pill))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = FlinsTheme.typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = color
        )
    }
}

@Composable
private fun ServerFormDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    initialEnabled: Boolean,
    initialActive: Boolean,
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (name: String, url: String, isEnabled: Boolean, isActive: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var isEnabled by remember { mutableStateOf(initialEnabled) }
    var isActive by remember { mutableStateOf(initialActive) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun validateAndSubmit() {
        if (name.isBlank()) {
            validationError = "Server name is required"
            return
        }
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("https://") && !trimmedUrl.startsWith("wss://")) {
            validationError = "URL must start with https:// or wss://"
            return
        }
        if (trimmedUrl.contains("localhost") || trimmedUrl.contains("127.0.0.1")) {
            validationError = "Localhost and internal addresses are blocked"
            return
        }
        validationError = null
        onSubmit(name.trim(), trimmedUrl, isEnabled, isActive)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FlinsDarkBg.copy(alpha = 0.98f),
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(text = title, style = FlinsTheme.typography.title, color = FlinsTheme.colors.textPrimary)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FlinsCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = FlinsTheme.colors.textPrimary,
                        unfocusedTextColor = FlinsTheme.colors.textPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Endpoint URL (HTTPS / WSS)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FlinsCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = FlinsTheme.colors.textPrimary,
                        unfocusedTextColor = FlinsTheme.colors.textPrimary
                    )
                )

                if (validationError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = validationError!!,
                        style = FlinsTheme.typography.caption,
                        color = FlinsError
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Server",
                        style = FlinsTheme.typography.bodyMedium,
                        color = FlinsTheme.colors.textPrimary
                    )
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FlinsDarkBg,
                            checkedTrackColor = FlinsCyan
                        )
                    )
                }

                if (!isEditing) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Set as Active Now",
                            style = FlinsTheme.typography.bodyMedium,
                            color = FlinsTheme.colors.textPrimary
                        )
                        Switch(
                            checked = isActive,
                            onCheckedChange = { isActive = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = FlinsDarkBg,
                                checkedTrackColor = FlinsCyan
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { validateAndSubmit() }) {
                Text(if (isEditing) "Save Changes" else "Add Server", color = FlinsCyan, style = FlinsTheme.typography.button)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = FlinsTheme.colors.textSecondary, style = FlinsTheme.typography.button)
            }
        }
    )
}
