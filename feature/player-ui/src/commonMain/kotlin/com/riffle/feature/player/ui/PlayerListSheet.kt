package com.riffle.feature.player.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.models.AudiobookBookmark
import com.riffle.feature.player.formatHms

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
    labels: PlayerChromeLabels,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val title = when (content) {
            is PlayerListContent.Chapters -> labels.chapters
            is PlayerListContent.Bookmarks -> labels.bookmarks
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .testTag("player_list_sheet_title"),
        )

        when (content) {
            is PlayerListContent.Chapters -> ChaptersList(content, labels, onDismiss)
            is PlayerListContent.Bookmarks -> BookmarksList(content, labels, onDismiss)
        }
    }
}

@Composable
private fun ChaptersList(
    content: PlayerListContent.Chapters,
    labels: PlayerChromeLabels,
    onDismiss: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(content.items) { chapter ->
            val isCurrent = chapter.index == content.currentIndex
            PlayerListRow(
                lead = {
                    if (isCurrent) {
                        Icon(
                            PlayerGlyphs.GraphicEq,
                            contentDescription = labels.nowPlaying,
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
                title = chapterDisplayTitle(chapter.title, chapter.index, labels),
                subtitle = if (isCurrent) labels.nowPlaying else null,
                trailing = {
                    Text(
                        text = formatHms(chapter.endSec - chapter.startSec),
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
private fun BookmarksList(
    content: PlayerListContent.Bookmarks,
    labels: PlayerChromeLabels,
    onDismiss: () -> Unit,
) {
    if (content.offlineNote) {
        Text(
            text = labels.offlineBookmarksWillSync,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
    if (content.items.isEmpty()) {
        Text(
            text = labels.noBookmarksYet,
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
                        PlayerGlyphs.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                title = bookmark.title,
                subtitle = null,
                trailing = { BookmarkOverflow(bookmark, content, labels) },
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
private fun BookmarkOverflow(
    bookmark: AudiobookBookmark,
    content: PlayerListContent.Bookmarks,
    labels: PlayerChromeLabels,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(PlayerGlyphs.MoreVert, contentDescription = labels.bookmarkOptions)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(labels.rename) },
                leadingIcon = { Icon(PlayerGlyphs.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    content.onRename(bookmark)
                },
            )
            DropdownMenuItem(
                text = { Text(labels.delete) },
                leadingIcon = { Icon(PlayerGlyphs.Delete, contentDescription = null) },
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
