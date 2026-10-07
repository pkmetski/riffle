package com.riffle.feature.settings.ui.panels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.ComicBackgroundThemeChoices
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.asComicBackgroundTheme
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_background
import com.riffle.feature.settings.ui.generated.resources.ui_current_page_and_remaining_pages
import com.riffle.feature.settings.ui.generated.resources.ui_display
import com.riffle.feature.settings.ui.generated.resources.ui_frame_one_panel_at_a_time_in_reading_order
import com.riffle.feature.settings.ui.generated.resources.ui_how_to_handle_panels_that_are_too_wide_or_tall_to_zoom_into_usefully
import com.riffle.feature.settings.ui.generated.resources.ui_on_screen_info
import com.riffle.feature.settings.ui.generated.resources.ui_page_numbers
import com.riffle.feature.settings.ui.generated.resources.ui_panel_overflow
import com.riffle.feature.settings.ui.generated.resources.ui_panel_view
import com.riffle.feature.settings.ui.generated.resources.ui_progress_bar_at_the_bottom_of_the_page
import com.riffle.feature.settings.ui.generated.resources.ui_reading_progress
import com.riffle.feature.settings.ui.readersettings.ThemeChipRows
import com.riffle.feature.settings.ui.readersettings.ThemeSwatchStyle
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ComicDisplaySettingsPanel(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold(stringResource(Res.string.ui_display), onDismiss) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(Res.string.ui_background),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        ThemeChipRows(
            selected = prefs.backgroundTheme.asComicBackgroundTheme(),
            onSelect = { onPrefsChange(prefs.copy(backgroundTheme = it)) },
            includeAuto = true,
            swatchStyle = ThemeSwatchStyle.BackgroundOnly,
            concreteThemes = ComicBackgroundThemeChoices,
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    ListItem(
        headlineContent = { Text(stringResource(Res.string.ui_panel_view)) },
        supportingContent = { Text(stringResource(Res.string.ui_frame_one_panel_at_a_time_in_reading_order)) },
        trailingContent = {
            Switch(
                checked = prefs.panelViewOn,
                onCheckedChange = { onPrefsChange(prefs.copy(panelViewOn = it)) },
                modifier = Modifier.testTag(TestTags.SETTINGS_COMIC_DISPLAY_PANEL_VIEW),
            )
        },
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Text(
        text = stringResource(Res.string.ui_panel_overflow),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    Text(
        text = stringResource(Res.string.ui_how_to_handle_panels_that_are_too_wide_or_tall_to_zoom_into_usefully),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
    PanelOverflowRadioGroup(
        selected = prefs.panelOverflow,
        enabled = prefs.panelViewOn,
        onSelect = { onPrefsChange(prefs.copy(panelOverflow = it)) },
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    PanelAnimationSpeedSlider(
        speedMs = prefs.panelAnimationSpeedMs,
        onSpeedChange = { onPrefsChange(prefs.copy(panelAnimationSpeedMs = it)) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        enabled = prefs.panelViewOn,
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Text(
        text = stringResource(Res.string.ui_on_screen_info),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    ListItem(
        headlineContent = { Text(stringResource(Res.string.ui_reading_progress)) },
        supportingContent = { Text(stringResource(Res.string.ui_progress_bar_at_the_bottom_of_the_page)) },
        trailingContent = {
            Switch(
                checked = prefs.showChapterMap,
                onCheckedChange = { onPrefsChange(prefs.copy(showChapterMap = it)) },
                modifier = Modifier.testTag(TestTags.SETTINGS_COMIC_DISPLAY_READING_PROGRESS),
            )
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(Res.string.ui_page_numbers)) },
        supportingContent = { Text(stringResource(Res.string.ui_current_page_and_remaining_pages)) },
        trailingContent = {
            Switch(
                checked = prefs.showPageProgress,
                enabled = prefs.showChapterMap,
                onCheckedChange = { onPrefsChange(prefs.copy(showPageProgress = it)) },
                modifier = Modifier.testTag(TestTags.SETTINGS_COMIC_DISPLAY_PAGE_NUMBERS),
            )
        },
    )
}
