package com.riffle.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun ToReadToggleButton(
    isInToRead: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (isInToRead) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isInToRead) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = if (isInToRead) "Remove from To Read" else "Add to To Read",
            tint = if (isInToRead) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}
