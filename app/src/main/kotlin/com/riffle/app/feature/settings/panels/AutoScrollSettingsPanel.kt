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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.app.feature.readersettings.AutoScrollToggleIcon
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
) = DetailScaffold(stringResource(R.string.ui_auto_scroll), onDismiss) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        // AutoScrollToggleIcon is an IconButton (touch-target 48dp) but we reuse it for
        // visual consistency — the hero position is decorative; disabled onClick.
        AutoScrollToggleIcon(isRunning = false, onClick = {})
    }
    Text(
        text = stringResource(R.string.ui_auto_scroll_description),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Text(
        text = stringResource(R.string.ui_this_icon_appears_in_the_reader_top_bar_to_start_and_stop_auto_scroll),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp),
    )
    ListItem(
        modifier = Modifier.toggleable(
            value = prefs.showAutoScroll,
            onValueChange = { onPrefsChange(prefs.copy(showAutoScroll = it)) },
        ),
        headlineContent = { Text(stringResource(R.string.ui_show_auto_scroll)) },
        supportingContent = { Text(stringResource(R.string.ui_adds_the_toggle_to_the_reader_top_bar_vertical_and_continuous_only)) },
        trailingContent = {
            Switch(checked = prefs.showAutoScroll, onCheckedChange = null)
        },
    )
    WpmSliderRow(
        label = stringResource(R.string.ui_default_speed),
        helper = stringResource(R.string.ui_per_book_override_formatting_panel_volume_keys),
        wpm = prefs.autoScrollWpm,
        onWpmChange = { onPrefsChange(prefs.copy(autoScrollWpm = it)) },
    )
}
