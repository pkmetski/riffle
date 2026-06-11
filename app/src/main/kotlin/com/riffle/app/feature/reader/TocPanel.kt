package com.riffle.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocPanel(
    entries: List<TocEntry>,
    activeHref: String?,
    onEntryClick: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries, activeHref) {
        val index = findActiveTopLevelIndex(entries, activeHref)
        if (index != null) {
            listState.scrollToItem(index)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().testTag("toc_panel")) {
            items(entries) { entry ->
                TocEntryRow(entry = entry, depth = 0, activeHref = activeHref, onEntryClick = onEntryClick)
            }
        }
    }
}

@Composable
private fun TocEntryRow(
    entry: TocEntry,
    depth: Int,
    activeHref: String?,
    onEntryClick: (TocEntry) -> Unit,
) {
    if (entry.title.isBlank()) {
        entry.children.forEach { child ->
            TocEntryRow(entry = child, depth = depth, activeHref = activeHref, onEntryClick = onEntryClick)
        }
        return
    }
    val isActive = entry.href == activeHref
    Column {
        Text(
            text = entry.title,
            style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEntryClick(entry) }
                .padding(
                    start = (16 + depth * 16).dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
        )
        entry.children.forEach { child ->
            TocEntryRow(entry = child, depth = depth + 1, activeHref = activeHref, onEntryClick = onEntryClick)
        }
    }
}
