package com.riffle.app.feature.settings.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior

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
    val overflowEnabled = prefs.panelViewOn
    val options = listOf(
        Triple(PanelOverflowBehavior.SPLIT, "Split", "Cuts oversized panels in half and shows each half as its own page"),
        Triple(PanelOverflowBehavior.SMART_SPLIT, "Smart split", "Like Split, but finds a natural seam (gutter or whitespace) to cut at a cleaner boundary"),
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (behavior, label, description) ->
            ListItem(
                headlineContent = {
                    Text(
                        label,
                        color = if (overflowEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                },
                supportingContent = {
                    Text(
                        description,
                        color = if (overflowEnabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                    )
                },
                leadingContent = {
                    RadioButton(
                        selected = prefs.panelOverflow == behavior,
                        onClick = null,
                        enabled = overflowEnabled,
                    )
                },
                modifier = if (overflowEnabled) {
                    Modifier.selectable(
                        selected = prefs.panelOverflow == behavior,
                        onClick = { onPrefsChange(prefs.copy(panelOverflow = behavior)) },
                        role = Role.RadioButton,
                    )
                } else Modifier,
            )
        }
    }
}
