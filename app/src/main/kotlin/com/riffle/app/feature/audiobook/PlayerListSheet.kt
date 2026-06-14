package com.riffle.app.feature.audiobook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.AudiobookBookmark
import com.riffle.core.domain.AudiobookChapter

/**
 * What a [PlayerListSheet] renders. The sheet is opened parameterized to exactly ONE kind — there are
 * no tabs; the caller picks chapters OR bookmarks. Both share the same row markup (see [PlayerListRow])
 * and differ only in the lead marker and trailing element.
 */
sealed interface PlayerListContent {
    data class Chapters(
        val items: List<AudiobookChapter>,
        val currentIndex: Int,
        val onSeek: (AudiobookChapter) -> Unit,
    ) : PlayerListContent

    data class Bookmarks(
        val items: List<AudiobookBookmark>,
        val onSeek: (AudiobookBookmark) -> Unit,
        val onRename: (AudiobookBookmark) -> Unit,
        val onDelete: (AudiobookBookmark) -> Unit,
        // Slice 2 surfaces this; pass false for now.
        val offlineNote: Boolean = false,
    ) : PlayerListContent
}

/**
 * One reusable [Audiobook Player] bottom sheet that shows either the chapters list or the bookmarks
 * list, depending on [content]. Tapping a row seeks and dismisses; bookmark rows carry a ⋮ overflow
 * for rename/delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerListSheet(
    content: PlayerListContent,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val title = when (content) {
            is PlayerListContent.Chapters -> "Chapters"
            is PlayerListContent.Bookmarks -> "Bookmarks"
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )

        when (content) {
            is PlayerListContent.Chapters -> ChaptersList(content, onDismiss)
            is PlayerListContent.Bookmarks -> BookmarksList(content, onDismiss)
        }
    }
}

@Composable
private fun ChaptersList(content: PlayerListContent.Chapters, onDismiss: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(content.items) { chapter ->
            val isCurrent = chapter.index == content.currentIndex
            val displayTitle = chapter.title.ifBlank { "Chapter ${chapter.index + 1}" }
            PlayerListRow(
                lead = {
                    if (isCurrent) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "Now playing",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = "${chapter.index + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                title = displayTitle,
                subtitle = if (isCurrent) "Now playing" else null,
                trailing = {
                    Text(
                        text = formatClock(chapter.endSec - chapter.startSec),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                highlighted = isCurrent,
                onClick = {
                    content.onSeek(chapter)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun BookmarksList(content: PlayerListContent.Bookmarks, onDismiss: () -> Unit) {
    if (content.offlineNote) {
        Text(
            text = "Offline — bookmarks will sync",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
    if (content.items.isEmpty()) {
        Text(
            text = "No bookmarks yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(content.items, key = { it.id }) { bookmark ->
            PlayerListRow(
                lead = {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                title = bookmark.title,
                subtitle = null,
                trailing = { BookmarkOverflow(bookmark, content) },
                highlighted = false,
                onClick = {
                    content.onSeek(bookmark)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun BookmarkOverflow(bookmark: AudiobookBookmark, content: PlayerListContent.Bookmarks) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Bookmark options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    content.onRename(bookmark)
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    content.onDelete(bookmark)
                },
            )
        }
    }
}

/**
 * The shared row markup for both chapters and bookmarks: a [lead] marker, a [title] with an optional
 * [subtitle], and a [trailing] slot. [highlighted] tints the title (used for the current chapter).
 */
@Composable
private fun PlayerListRow(
    lead: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            lead()
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

/** mm:ss under an hour, h:mm:ss otherwise. */
private fun formatClock(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val secs = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, secs) else "%d:%02d".format(m, secs)
}
