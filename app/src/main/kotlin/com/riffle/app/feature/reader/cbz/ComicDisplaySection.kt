package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior

/**
 * Panel View, Panel Overflow, and on-screen-info controls. Reused by the in-reader formatting
 * sheet and the global Settings → Display screen.
 */
@Composable
internal fun ComicDisplaySection(
    prefs: ComicFormattingPreferences,
    onPanelViewChange: (Boolean) -> Unit,
    onPanelOverflowChange: (PanelOverflowBehavior) -> Unit,
    onShowReadingProgressChange: (Boolean) -> Unit,
    onShowPageNumbersChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text("Panel View") },
        supportingContent = { Text("Frame one panel at a time in reading order") },
        trailingContent = {
            Switch(
                checked = prefs.panelViewOn,
                onCheckedChange = onPanelViewChange,
            )
        },
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Text(
        text = "Panel Overflow",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    Text(
        text = "How to handle panels that are too wide or tall to zoom into usefully.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
    PanelOverflowRadioGroup(
        selected = prefs.panelOverflow,
        enabled = prefs.panelViewOn,
        onSelect = onPanelOverflowChange,
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Text(
        text = "On-screen info",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    ListItem(
        headlineContent = { Text("Reading progress") },
        supportingContent = { Text("Progress bar at the bottom of the page") },
        trailingContent = {
            Switch(
                checked = prefs.showChapterMap,
                onCheckedChange = onShowReadingProgressChange,
            )
        },
    )
    ListItem(
        headlineContent = { Text("Page numbers") },
        supportingContent = { Text("Current page and remaining pages") },
        trailingContent = {
            Switch(
                checked = prefs.showPageProgress,
                enabled = prefs.showChapterMap,
                onCheckedChange = onShowPageNumbersChange,
            )
        },
    )
}
