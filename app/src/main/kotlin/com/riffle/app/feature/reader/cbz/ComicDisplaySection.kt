package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.ThemeChipRows
import com.riffle.app.feature.reader.ThemeSwatchStyle
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicBackgroundThemeChoices
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.asComicBackgroundTheme

/**
 * Background, Panel View, Panel Overflow, and on-screen-info controls. Reused by the in-reader
 * formatting sheet and the global Settings → Display screen.
 */
@Composable
internal fun ComicDisplaySection(
    prefs: ComicFormattingPreferences,
    backgroundHorizontalPadding: Dp = 0.dp,
    onBackgroundThemeChange: (ReaderTheme) -> Unit,
    onPanelViewChange: (Boolean) -> Unit,
    onPanelOverflowChange: (PanelOverflowBehavior) -> Unit,
    onPanelAnimationSpeedChange: (Int) -> Unit,
    onShowReadingProgressChange: (Boolean) -> Unit,
    onShowPageNumbersChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = backgroundHorizontalPadding)) {
        Text(
            text = "Background",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        ThemeChipRows(
            selected = prefs.backgroundTheme.asComicBackgroundTheme(),
            onSelect = { onBackgroundThemeChange(it.asComicBackgroundTheme()) },
            includeAuto = true,
            swatchStyle = ThemeSwatchStyle.BackgroundOnly,
            concreteThemes = ComicBackgroundThemeChoices,
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    ListItem(
        headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_panel_view)) },
        supportingContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_frame_one_panel_at_a_time_in_reading_order)) },
        trailingContent = {
            Switch(
                checked = prefs.panelViewOn,
                onCheckedChange = onPanelViewChange,
            )
        },
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Text(
        text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_panel_overflow),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    Text(
        text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_how_to_handle_panels_that_are_too_wide_or_tall_to_zoom_into_usefully),
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
    PanelAnimationSpeedSlider(
        speedMs = prefs.panelAnimationSpeedMs,
        onSpeedChange = onPanelAnimationSpeedChange,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        enabled = prefs.panelViewOn,
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Text(
        text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_on_screen_info),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    ListItem(
        headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_reading_progress)) },
        supportingContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_progress_bar_at_the_bottom_of_the_page)) },
        trailingContent = {
            Switch(
                checked = prefs.showChapterMap,
                onCheckedChange = onShowReadingProgressChange,
            )
        },
    )
    ListItem(
        headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_page_numbers)) },
        supportingContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_current_page_and_remaining_pages)) },
        trailingContent = {
            Switch(
                checked = prefs.showPageProgress,
                enabled = prefs.showChapterMap,
                onCheckedChange = onShowPageNumbersChange,
            )
        },
    )
}
