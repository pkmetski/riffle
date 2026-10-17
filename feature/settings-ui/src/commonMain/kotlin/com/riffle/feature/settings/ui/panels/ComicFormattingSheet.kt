package com.riffle.feature.settings.ui.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.BookComicFormattingOverrides
import com.riffle.core.domain.comic.ComicBackgroundThemeChoices
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.ui.comicBackgroundChipSelection
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_background
import com.riffle.feature.settings.ui.generated.resources.ui_current_page_and_remaining_pages
import com.riffle.feature.settings.ui.generated.resources.ui_frame_one_panel_at_a_time_in_reading_order
import com.riffle.feature.settings.ui.generated.resources.ui_how_to_handle_panels_that_are_too_wide_or_tall_to_zoom_into_usefully
import com.riffle.feature.settings.ui.generated.resources.ui_on_screen_info
import com.riffle.feature.settings.ui.generated.resources.ui_page_numbers
import com.riffle.feature.settings.ui.generated.resources.ui_panel_overflow
import com.riffle.feature.settings.ui.generated.resources.ui_panel_view
import com.riffle.feature.settings.ui.generated.resources.ui_progress_bar_at_the_bottom_of_the_page
import com.riffle.feature.settings.ui.generated.resources.ui_reading_progress
import com.riffle.feature.settings.ui.generated.resources.ui_reset_to_global_defaults
import com.riffle.feature.settings.ui.readersettings.ThemeChipRows
import com.riffle.feature.settings.ui.readersettings.ThemeSwatchStyle
import org.jetbrains.compose.resources.stringResource

/**
 * In-reader comic formatting bottom sheet, shared by Android and iOS.
 *
 * Contains the same Background-theme, Panel View, Panel Overflow, Animation Speed, and
 * On-screen Info controls as [ComicDisplaySettingsPanel] but wrapped in a [ModalBottomSheet]
 * instead of a full-screen [DetailScaffold], and using delta-update [BookComicFormattingOverrides]
 * so the reader's ViewModel only persists what the user changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicFormattingSheet(
    formatting: ComicFormattingPreferences,
    hasBookOverrides: Boolean,
    onUpdate: (BookComicFormattingOverrides) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(Res.string.ui_background),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                ThemeChipRows(
                    selected = comicBackgroundChipSelection(formatting.backgroundTheme),
                    onSelect = { onUpdate(BookComicFormattingOverrides(backgroundTheme = it)) },
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
                        checked = formatting.panelViewOn,
                        onCheckedChange = { onUpdate(BookComicFormattingOverrides(panelViewOn = it)) },
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
                selected = formatting.panelOverflow,
                enabled = formatting.panelViewOn,
                onSelect = { onUpdate(BookComicFormattingOverrides(panelOverflow = it)) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            PanelAnimationSpeedSlider(
                speedMs = formatting.panelAnimationSpeedMs,
                onSpeedChange = { onUpdate(BookComicFormattingOverrides(panelAnimationSpeedMs = it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = formatting.panelViewOn,
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
                        checked = formatting.showChapterMap,
                        onCheckedChange = { onUpdate(BookComicFormattingOverrides(showChapterMap = it)) },
                        modifier = Modifier.testTag(TestTags.SETTINGS_COMIC_DISPLAY_READING_PROGRESS),
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(Res.string.ui_page_numbers)) },
                supportingContent = { Text(stringResource(Res.string.ui_current_page_and_remaining_pages)) },
                trailingContent = {
                    Switch(
                        checked = formatting.showPageProgress,
                        enabled = formatting.showChapterMap,
                        onCheckedChange = { onUpdate(BookComicFormattingOverrides(showPageProgress = it)) },
                        modifier = Modifier.testTag(TestTags.SETTINGS_COMIC_DISPLAY_PAGE_NUMBERS),
                    )
                },
            )
            HorizontalDivider()
            TextButton(
                onClick = onReset,
                enabled = hasBookOverrides,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 4.dp),
            ) {
                Text(stringResource(Res.string.ui_reset_to_global_defaults))
            }
        }
    }
}
