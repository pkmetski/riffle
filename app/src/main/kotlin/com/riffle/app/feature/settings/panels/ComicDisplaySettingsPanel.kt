package com.riffle.app.feature.settings.panels

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.cbz.PanelOverflowRadioGroup
import com.riffle.core.domain.comic.ComicFormattingPreferences

@Composable
internal fun ComicDisplaySettingsPanel(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Display", onDismiss) {
    // Panel View toggle
    ListItem(
        headlineContent = { Text("Panel View") },
        supportingContent = { Text("Frame one panel at a time in reading order") },
        trailingContent = {
            Switch(
                checked = prefs.panelViewOn,
                onCheckedChange = { on -> onPrefsChange(prefs.copy(panelViewOn = on)) },
            )
        },
    )

    HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))

    // Panel Overflow — nested under Panel View, disabled when off
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
        onSelect = { onPrefsChange(prefs.copy(panelOverflow = it)) },
    )
}
