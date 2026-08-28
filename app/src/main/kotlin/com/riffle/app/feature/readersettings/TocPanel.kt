package com.riffle.app.feature.readersettings

import com.riffle.core.models.TocEntry
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.app.ui.fadingScrollbar
import com.riffle.app.feature.reader.findActiveEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocPanel(
    entries: List<TocEntry>,
    activeHref: String?,
    onEntryClick: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val flat = remember(entries) { flattenToc(entries) }
    // Resolve the active entry once so per-row highlight and the scroll target use the
    // same fallback logic (see findActiveEntry — path-only match when the locator has no
    // fragment). Rows compare by reference so multiple entries sharing a resource path
    // don't all light up.
    val activeEntry = remember(entries, activeHref) {
        activeHref?.let { findActiveEntry(entries, it) }
    }
    LaunchedEffect(entries, activeHref, flat) {
        val index = findActiveFlatIndex(entries, flat, activeHref)
        if (index != null) {
            listState.scrollToItem(index)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fadingScrollbar(listState)
                .testTag("toc_panel"),
        ) {
            items(
                items = flat,
                key = { row -> "${row.depth} ${row.entry.href} ${row.orderIndex}" },
            ) { row ->
                TocEntryRow(
                    entry = row.entry,
                    depth = row.depth,
                    isActive = row.entry === activeEntry,
                    onEntryClick = onEntryClick,
                )
            }
        }
    }
}

internal data class TocRow(val entry: TocEntry, val depth: Int, val orderIndex: Int)

internal fun flattenToc(entries: List<TocEntry>): List<TocRow> {
    val out = ArrayList<TocRow>()
    fun walk(list: List<TocEntry>, depth: Int) {
        for (e in list) {
            if (e.title.isNotBlank()) {
                out.add(TocRow(e, depth, out.size))
                walk(e.children, depth + 1)
            } else {
                // Preserve legacy behaviour: skip blank-title container, descend at same depth.
                walk(e.children, depth)
            }
        }
    }
    walk(entries, 0)
    return out
}

internal fun findActiveFlatIndex(
    entries: List<TocEntry>,
    flat: List<TocRow>,
    activeHref: String?,
): Int? {
    if (activeHref == null) return null
    // Match the tree-walking rules of findActiveEntry, then locate the resulting entry in flat.
    // This handles blank-title containers (skipped in flat) by promoting to their first
    // descendant that matches, and keeps the exact-href-first / subtree-fallback behaviour.
    val entry = findActiveEntry(entries, activeHref) ?: return null
    val normalizedEntryHref = entry.href.trimStart('/')
    val exact = flat.indexOfFirst { it.entry.href.trimStart('/') == normalizedEntryHref }
    if (exact >= 0) return exact
    // The matched entry itself was skipped (blank title). Fall through to its first descendant
    // that survived flattening.
    val descendantHrefs = collectHrefs(entry.children).mapTo(HashSet()) { it.trimStart('/') }
    val idx = flat.indexOfFirst { it.entry.href.trimStart('/') in descendantHrefs }
    return if (idx >= 0) idx else null
}

private fun collectHrefs(entries: List<TocEntry>): List<String> {
    val out = ArrayList<String>()
    fun walk(list: List<TocEntry>) {
        for (e in list) {
            out.add(e.href)
            walk(e.children)
        }
    }
    walk(entries)
    return out
}

@Composable
private fun TocEntryRow(
    entry: TocEntry,
    depth: Int,
    isActive: Boolean,
    onEntryClick: (TocEntry) -> Unit,
) {
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
}
