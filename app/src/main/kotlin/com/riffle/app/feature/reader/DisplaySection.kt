package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.formatting.RenderCapabilities
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme

/**
 * Theme + view + on-screen-info controls. Reused by the in-reader settings sheet (Display tab)
 * and the global Settings → Display screen.
 *
 * @param scheduleEditable when the theme is Auto: `true` shows the full day/night schedule editor
 *   (Settings host), `false` shows a read-only summary card (reader host, which can't edit times).
 * @param capabilities hides rows the current renderer can't apply (e.g. reading-mode switching
 *   and double-page spread on PDF, see [RenderCapabilities.PDF]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySection(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    scheduleEditable: Boolean,
    capabilities: RenderCapabilities = RenderCapabilities.EPUB,
) {
    Column {
        // Theme
        Text("Theme", style = MaterialTheme.typography.labelMedium)
        val concreteThemes = listOf(ReaderTheme.Light, ReaderTheme.Dark, ReaderTheme.DarkDim, ReaderTheme.Sepia)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            concreteThemes.forEach { theme ->
                FilterChip(
                    selected = prefs.theme == theme,
                    onClick = { onPrefsChange(prefs.copy(theme = theme)) },
                    label = { Text(theme.label()) },
                    leadingIcon = { ThemeSwatch(theme, prefs.themeSchedule) },
                    modifier = Modifier.semantics { contentDescription = "${theme.label()} theme" },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = prefs.theme == ReaderTheme.Auto,
                onClick = { onPrefsChange(prefs.copy(theme = ReaderTheme.Auto)) },
                label = { Text(ReaderTheme.Auto.label()) },
                leadingIcon = { ThemeSwatch(ReaderTheme.Auto, prefs.themeSchedule) },
                modifier = Modifier.semantics { contentDescription = "${ReaderTheme.Auto.label()} theme" },
            )
        }
        if (prefs.theme == ReaderTheme.Auto) {
            Spacer(Modifier.height(12.dp))
            if (scheduleEditable) {
                AutoScheduleControls(
                    schedule = prefs.themeSchedule,
                    onScheduleChange = { onPrefsChange(prefs.copy(themeSchedule = it)) },
                )
            } else {
                AutoScheduleSummaryCard(prefs.themeSchedule)
            }
        }
        Spacer(Modifier.height(20.dp))

        // View
        Text("View", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        if (capabilities.supportsReadingModeSwitch) {
            Text("Reading mode", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderOrientation.entries.forEach { orientation ->
                    val label = when (orientation) {
                        ReaderOrientation.Horizontal -> "Paginated"
                        ReaderOrientation.Vertical -> "Scroll"
                        ReaderOrientation.Continuous -> "Continuous"
                    }
                    FilterChip(
                        selected = prefs.orientation == orientation,
                        onClick = { onPrefsChange(prefs.copy(orientation = orientation)) },
                        label = { Text(label) },
                        leadingIcon = { OrientationIcon(orientation) },
                        modifier = Modifier.semantics { contentDescription = "$label reading orientation" },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (capabilities.supportsDoublePage) {
            val doublePageEnabled = prefs.orientation == ReaderOrientation.Horizontal
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().alpha(if (doublePageEnabled) 1f else 0.38f),
            ) {
                Text("Double page in landscape", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = prefs.doublePageSpread,
                    onCheckedChange = { onPrefsChange(prefs.copy(doublePageSpread = it)) },
                    enabled = doublePageEnabled,
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // On-screen info
        Text("On-screen info", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        ToggleRow("Chapter map", prefs.showChapterMap) { onPrefsChange(prefs.copy(showChapterMap = it)) }
        ToggleRow("Current chapter label", prefs.showCurrentChapterLabel) { onPrefsChange(prefs.copy(showCurrentChapterLabel = it)) }
        ToggleRow("Reading progress labels", prefs.showReadingProgressLabels) { onPrefsChange(prefs.copy(showReadingProgressLabels = it)) }
        ToggleRow("Time remaining", prefs.showReadingTimeEstimate) { onPrefsChange(prefs.copy(showReadingTimeEstimate = it)) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
