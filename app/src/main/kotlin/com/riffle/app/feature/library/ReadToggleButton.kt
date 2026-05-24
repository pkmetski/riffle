package com.riffle.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

internal const val READ_PROGRESS_THRESHOLD = 0.99f

@Composable
fun ReadToggleButton(
    isRead: Boolean,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (isRead) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            .clickable(onClick = if (isRead) onMarkAsUnread else onMarkAsRead),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = if (isRead) "Mark as unread" else "Mark as read",
            tint = if (isRead) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}
