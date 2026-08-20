package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.app.R
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
        if (capabilities.supportsTheme) {
            Text(stringResource(R.string.ui_theme), style = MaterialTheme.typography.labelMedium)
            ThemeChipRows(
                selected = prefs.theme,
                onSelect = { onPrefsChange(prefs.copy(theme = it)) },
                schedule = prefs.themeSchedule,
                autoMode = prefs.autoReaderThemeMode,
                appThemeReaderThemes = prefs.appThemeReaderThemes,
                includeAuto = true,
                labelForTheme = { it.localizedLabel() },
                contentDescriptionForTheme = { _, label -> stringResource(R.string.ui_theme_named, label) },
            )
            if (prefs.theme == ReaderTheme.Auto) {
                Spacer(Modifier.height(12.dp))
                if (scheduleEditable) {
                    AutoThemeControls(
                        schedule = prefs.themeSchedule,
                        autoMode = prefs.autoReaderThemeMode,
                        appThemeReaderThemes = prefs.appThemeReaderThemes,
                        onAutoModeChange = { onPrefsChange(prefs.copy(autoReaderThemeMode = it)) },
                        onAppThemeReaderThemesChange = {
                            onPrefsChange(prefs.copy(appThemeReaderThemes = it))
                        },
                        onScheduleChange = { onPrefsChange(prefs.copy(themeSchedule = it)) },
                    )
                } else {
                    AutoThemeSummaryCard(
                        prefs.themeSchedule,
                        prefs.autoReaderThemeMode,
                        prefs.appThemeReaderThemes,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // View — only render the header if any of its sub-controls are visible.
        if (capabilities.supportsReadingModeSwitch || capabilities.supportsDoublePage) {
            Text(stringResource(R.string.ui_view), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
        }
        if (capabilities.supportsReadingModeSwitch) {
            Text(stringResource(R.string.ui_reading_mode), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderOrientation.entries.forEach { orientation ->
                    val label = orientation.localizedLabel()
                    val orientationContentDescription = stringResource(R.string.ui_reading_orientation_named, label)
                    FilterChip(
                        selected = prefs.orientation == orientation,
                        onClick = { onPrefsChange(prefs.copy(orientation = orientation)) },
                        label = { Text(label) },
                        leadingIcon = { OrientationIcon(orientation) },
                        modifier = Modifier.semantics { contentDescription = orientationContentDescription },
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
                Text(stringResource(R.string.ui_double_page_in_landscape), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = prefs.doublePageSpread,
                    onCheckedChange = { onPrefsChange(prefs.copy(doublePageSpread = it)) },
                    enabled = doublePageEnabled,
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // On-screen info
        Text(stringResource(R.string.ui_on_screen_info), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        ToggleRow(stringResource(R.string.ui_chapter_map), prefs.showChapterMap) { onPrefsChange(prefs.copy(showChapterMap = it)) }
        ToggleRow(
            label = stringResource(R.string.ui_colored_chapter_map),
            checked = prefs.coloredChapterMap,
            enabled = prefs.showChapterMap,
            modifier = Modifier.padding(start = 16.dp),
            testTag = "colored_chapter_map_toggle",
            onChange = { onPrefsChange(prefs.copy(coloredChapterMap = it)) },
        )
        if (capabilities.supportsPositionOverlays) {
            ToggleRow(stringResource(R.string.ui_current_chapter_label), prefs.showCurrentChapterLabel) { onPrefsChange(prefs.copy(showCurrentChapterLabel = it)) }
            ToggleRow(stringResource(R.string.ui_reading_progress_labels), prefs.showReadingProgressLabels) { onPrefsChange(prefs.copy(showReadingProgressLabels = it)) }
            ToggleRow(stringResource(R.string.ui_time_remaining), prefs.showReadingTimeEstimate) { onPrefsChange(prefs.copy(showReadingTimeEstimate = it)) }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier,
        )
    }
}
