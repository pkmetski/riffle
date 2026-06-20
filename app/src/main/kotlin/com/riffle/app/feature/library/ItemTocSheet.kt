package com.riffle.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.TocEntry

private data class FlatTocEntry(val entry: TocEntry, val depth: Int)

private fun flattenToc(entries: List<TocEntry>, depth: Int = 0): List<FlatTocEntry> =
    entries.flatMap { listOf(FlatTocEntry(it, depth)) + flattenToc(it.children, depth + 1) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemTocSheet(
    entries: List<TocEntry>,
    onEntryClick: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = "Table of Contents",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        val flat = flattenToc(entries)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(flat, key = { index, item -> "${index}_${item.depth}_${item.entry.href}" }) { _, (entry, depth) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onEntryClick(entry)
                            onDismiss()
                        }
                        .padding(
                            start = (16 + depth * 16).dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (depth == 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
