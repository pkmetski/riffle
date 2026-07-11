package com.riffle.app.feature.settings.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.autoscroll.AutoScrollToggleIcon
import com.riffle.core.domain.FormattingPreferences

/**
 * Auto-Scroll drill-in — the reader's "creep the page upward at a set pace" feature. Rendered
 * inside the top-level Pacing section on the main Settings list. Hero glyph mirrors the reader
 * top-bar toggle so the icon is recognisable at both altitudes.
 */
@Composable
fun AutoScrollSettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Auto-Scroll", onDismiss) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        // AutoScrollToggleIcon is an IconButton (touch-target 48dp) but we reuse it for
        // visual consistency — the hero position is decorative; disabled onClick.
        AutoScrollToggleIcon(isRunning = false, onClick = {})
    }
    Text(
        text = "Auto-Scroll creeps the page upward at a set pace so you can read hands-free. " +
            "Available in scrolling reading modes. Start and stop from the reader top bar; " +
            "nudge speed with the volume keys while running.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Text(
        text = "This icon appears in the reader top bar to start and stop Auto-Scroll.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp),
    )
    ListItem(
        modifier = Modifier.toggleable(
            value = prefs.showAutoScroll,
            onValueChange = { onPrefsChange(prefs.copy(showAutoScroll = it)) },
        ),
        headlineContent = { Text("Show Auto-Scroll") },
        supportingContent = { Text("Adds the toggle to the reader top bar (Vertical & Continuous only)") },
        trailingContent = {
            Switch(checked = prefs.showAutoScroll, onCheckedChange = null)
        },
    )
    WpmSliderRow(
        label = "Default speed",
        helper = "Per-book override in the reader's Formatting panel. Adjust live with the volume keys while running.",
        wpm = prefs.autoScrollWpm,
        onWpmChange = { onPrefsChange(prefs.copy(autoScrollWpm = it)) },
    )
}
