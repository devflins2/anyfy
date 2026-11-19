package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.update.UpdateStatus
import com.example.service.update.VersionManifest
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme

@Composable
fun UpdateDialog(
    status: UpdateStatus,
    onUpdateNow: (VersionManifest) -> Unit,
    onInstallNow: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manifest = when (status) {
        is UpdateStatus.UpdateAvailable -> status.manifest
        is UpdateStatus.Downloading -> status.manifest
        is UpdateStatus.Verifying -> status.manifest
        is UpdateStatus.ReadyToInstall -> status.manifest
        is UpdateStatus.PermissionRequired -> status.manifest
        else -> null
    } ?: return

    val isMandatory = when (status) {
        is UpdateStatus.UpdateAvailable -> status.isMandatory
        is UpdateStatus.Downloading -> status.manifest.mandatory
        is UpdateStatus.Verifying -> status.manifest.mandatory
        is UpdateStatus.ReadyToInstall -> status.manifest.mandatory
        is UpdateStatus.PermissionRequired -> status.manifest.mandatory
        else -> false
    }

    Dialog(
        onDismissRequest = {
            if (!isMandatory && status !is UpdateStatus.Downloading && status !is UpdateStatus.Verifying) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isMandatory && status !is UpdateStatus.Downloading && status !is UpdateStatus.Verifying,
            dismissOnClickOutside = !isMandatory && status !is UpdateStatus.Downloading && status !is UpdateStatus.Verifying
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(26.dp))
                .border(1.dp, Color(0xFF383838), RoundedCornerShape(26.dp))
                .testTag("update_dialog"),
            color = Color(0xFF181818)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App Logo / Update Icon Header
                Box(
                    modifier = Modifier.size(68.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF454545), Color(0xFF202020))
                                )
                            )
                    )
                    Image(
                        painter = painterResource(id = com.example.R.drawable.anyfy_logo),
                        contentDescription = "Anyfy Logo",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = if (isMandatory) "Mandatory Update Available" else "New Version Available",
                    style = FlinsTheme.typography.title.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = FlinsTheme.colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Version Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF282828))
                            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "v${manifest.versionName} (${manifest.versionCode})",
                            style = FlinsTheme.typography.caption.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = FlinsCyan
                            )
                        )
                    }

                    if (isMandatory) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF4A1818))
                                .border(1.dp, Color(0xFF7A2424), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "REQUIRED",
                                style = FlinsTheme.typography.caption.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = Color(0xFFFF8A80)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mandatory notice or release notes container
                if (isMandatory) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF221616))
                            .border(1.dp, Color(0xFF4A2626), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFF8A80),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "This update is required to maintain audio playback stability & security.",
                                style = FlinsTheme.typography.caption.copy(fontSize = 11.sp),
                                color = Color(0xFFFFCDD2)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Release Notes Box
                if (manifest.releaseNotes.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF202020))
                            .border(1.dp, Color(0xFF303030), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = "WHAT'S NEW",
                                style = FlinsTheme.typography.overline,
                                color = Color(0xFF888888)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            manifest.releaseNotes.forEach { note ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "•",
                                        color = FlinsCyan,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = note,
                                        style = FlinsTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                        color = Color(0xFFE0E0E0)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Progress / Verifying / Failed State Views
                when (status) {
                    is UpdateStatus.Downloading -> {
                        val animatedProgress by animateFloatAsState(
                            targetValue = if (status.totalBytes > 0) status.progressPercent / 100f else 0f,
                            label = "download_progress"
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .testTag("update_progress_bar"),
                                color = FlinsCyan,
                                trackColor = Color(0xFF333333)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val downloadedMb = status.bytesDownloaded / (1024f * 1024f)
                                val totalMb = status.totalBytes / (1024f * 1024f)
                                val textProgress = if (status.totalBytes > 0) {
                                    String.format("%.1f MB / %.1f MB (%d%%)", downloadedMb, totalMb, status.progressPercent)
                                } else {
                                    String.format("%.1f MB downloaded", downloadedMb)
                                }

                                Text(
                                    text = textProgress,
                                    style = FlinsTheme.typography.caption,
                                    color = Color(0xFF9E9E9E)
                                )

                                if (status.speedBytesPerSec > 0) {
                                    val speedKb = status.speedBytesPerSec / 1024f
                                    val speedText = if (speedKb >= 1024f) {
                                        String.format("%.1f MB/s", speedKb / 1024f)
                                    } else {
                                        String.format("%.0f KB/s", speedKb)
                                    }
                                    Text(
                                        text = speedText,
                                        style = FlinsTheme.typography.caption,
                                        color = FlinsCyan
                                    )
                                }
                            }
                        }
                    }

                    is UpdateStatus.Verifying -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = FlinsCyan,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Verifying package signature & integrity...",
                                style = FlinsTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = Color(0xFFCCCCCC)
                            )
                        }
                    }

                    is UpdateStatus.ReadyToInstall, is UpdateStatus.PermissionRequired -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1A261E))
                                .border(1.dp, Color(0xFF2C4C34), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF81C784),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Package verified. Ready to install.",
                                    style = FlinsTheme.typography.bodyMedium.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color(0xFFC8E6C9)
                                )
                            }
                        }
                    }

                    is UpdateStatus.Failed -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF261A1A))
                                .border(1.dp, Color(0xFF4C2A2A), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFE57373),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = status.message,
                                    style = FlinsTheme.typography.caption.copy(fontSize = 11.sp),
                                    color = Color(0xFFFFCDD2)
                                )
                            }
                        }
                    }

                    else -> {}
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Actions (Update Now / Later / Install Now / Retry)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // "Later" action: only if NOT mandatory and NOT busy downloading/verifying
                    if (!isMandatory && status !is UpdateStatus.Downloading && status !is UpdateStatus.Verifying) {
                        GlassButton(
                            onClick = onDismiss,
                            variant = GlassButtonVariant.SECONDARY,
                            modifier = Modifier.weight(1f),
                            testTag = "update_later_button"
                        ) {
                            Text(
                                text = "Later",
                                style = FlinsTheme.typography.button,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                    }

                    // Primary Action Button
                    when (status) {
                        is UpdateStatus.ReadyToInstall, is UpdateStatus.PermissionRequired -> {
                            GlassButton(
                                onClick = onInstallNow,
                                variant = GlassButtonVariant.PRIMARY,
                                modifier = Modifier.weight(1f),
                                testTag = "install_now_button"
                            ) {
                                Text(
                                    text = "Install Now",
                                    style = FlinsTheme.typography.button,
                                    color = Color.White
                                )
                            }
                        }

                        is UpdateStatus.Failed -> {
                            if (status.canRetry) {
                                GlassButton(
                                    onClick = onRetry,
                                    variant = GlassButtonVariant.PRIMARY,
                                    modifier = Modifier.weight(1f),
                                    testTag = "retry_update_button"
                                ) {
                                    Text(
                                        text = "Retry",
                                        style = FlinsTheme.typography.button,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        is UpdateStatus.Downloading, is UpdateStatus.Verifying -> {
                            // Disabled button while busy
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF282828)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (status is UpdateStatus.Downloading) "Downloading..." else "Verifying...",
                                    style = FlinsTheme.typography.button,
                                    color = Color(0xFF888888)
                                )
                            }
                        }

                        else -> {
                            GlassButton(
                                onClick = { onUpdateNow(manifest) },
                                variant = GlassButtonVariant.PRIMARY,
                                modifier = Modifier.weight(1f),
                                testTag = "update_now_button"
                            ) {
                                Text(
                                    text = "Update Now",
                                    style = FlinsTheme.typography.button,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
