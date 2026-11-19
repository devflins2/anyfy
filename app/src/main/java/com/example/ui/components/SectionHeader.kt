package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FlinsTheme

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            if (overline != null) {
                Text(
                    text = overline.uppercase(),
                    style = FlinsTheme.typography.overline,
                    color = FlinsTheme.colors.textMuted
                )
            }
            Text(
                text = title,
                style = FlinsTheme.typography.title,
                color = FlinsTheme.colors.textPrimary
            )
        }
        if (actionText != null && onActionClick != null) {
            Text(
                text = actionText,
                style = FlinsTheme.typography.label,
                color = Color(0xFFF0F0F0),
                modifier = Modifier
                    .clickable(onClick = onActionClick)
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            )
        }
    }
}
