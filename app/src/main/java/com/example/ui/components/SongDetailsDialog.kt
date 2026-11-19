package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.Track
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsError
import com.example.ui.theme.FlinsTheme

@Composable
fun SongDetailsDialog(
    track: Track,
    onDismiss: () -> Unit
) {
    val isAvailable = !track.audioUrl.isNullOrBlank() || !track.id.startsWith("unavail")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(FlinsTheme.radii.sm))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(FlinsTheme.radii.sm))
                ) {
                    AsyncImage(
                        model = track.artwork,
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Song Details",
                        style = FlinsTheme.typography.overline,
                        color = Color(0xFFF0F0F0)
                    )
                    Text(
                        text = track.title,
                        style = FlinsTheme.typography.titleMedium,
                        color = FlinsTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow(label = "Artist", value = track.artist)
                DetailRow(label = "Album", value = track.album)
                DetailRow(label = "Duration", value = track.durationFormatted)
                DetailRow(label = "Genre", value = track.genre)
                if (track.bpm > 0) {
                    DetailRow(label = "BPM", value = "${track.bpm} BPM")
                }
                if (track.energy > 0f) {
                    DetailRow(label = "Energy", value = "${(track.energy * 100).toInt()}%")
                }
                if (track.mood.isNotBlank()) {
                    DetailRow(label = "Mood", value = track.mood)
                }
                DetailRow(
                    label = "Status",
                    value = if (isAvailable) "Available for playback" else "Currently unavailable",
                    valueColor = if (isAvailable) Color(0xFFBDBDBD) else Color(0xFF707070)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFFF0F0F0), style = FlinsTheme.typography.button)
            }
        },
        dismissButton = {
            val context = androidx.compose.ui.platform.LocalContext.current
            TextButton(
                onClick = {
                    com.example.service.diagnostics.PlaybackDiagnosticLogger.exportDiagnostics(context)
                }
            ) {
                Text("Share Diagnostics", color = Color(0xFFBDBDBD), style = FlinsTheme.typography.button)
            }
        },
        containerColor = FlinsDarkBg,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = FlinsTheme.colors.textPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = FlinsTheme.typography.metadata,
            color = FlinsTheme.colors.textMuted
        )
        Text(
            text = value,
            style = FlinsTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
