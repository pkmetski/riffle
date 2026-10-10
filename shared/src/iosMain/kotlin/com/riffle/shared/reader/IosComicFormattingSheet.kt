package com.riffle.shared.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.BookComicFormattingOverrides
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_animation_speed
import com.riffle.feature.reader.ui.generated.resources.ui_current_page_and_remaining_pages
import com.riffle.feature.reader.ui.generated.resources.ui_cuts_oversized_panels_in_half
import com.riffle.feature.reader.ui.generated.resources.ui_frame_one_panel_at_a_time_in_reading_order
import com.riffle.feature.reader.ui.generated.resources.ui_how_to_handle_panels_that_are_too_wide_or_tall_to_zoom_into_usefully
import com.riffle.feature.reader.ui.generated.resources.ui_no_split
import com.riffle.feature.reader.ui.generated.resources.ui_off
import com.riffle.feature.reader.ui.generated.resources.ui_on_screen_info
import com.riffle.feature.reader.ui.generated.resources.ui_page_numbers
import com.riffle.feature.reader.ui.generated.resources.ui_panel_overflow
import com.riffle.feature.reader.ui.generated.resources.ui_panel_view
import com.riffle.feature.reader.ui.generated.resources.ui_progress_bar_at_the_bottom_of_the_page
import com.riffle.feature.reader.ui.generated.resources.ui_reading_progress
import com.riffle.feature.reader.ui.generated.resources.ui_reset_to_global_defaults
import com.riffle.feature.reader.ui.generated.resources.ui_show_oversized_panels_as_is_without_splitting
import com.riffle.feature.reader.ui.generated.resources.ui_smart_split
import com.riffle.feature.reader.ui.generated.resources.ui_smart_split_description
import com.riffle.feature.reader.ui.generated.resources.ui_split
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IosComicFormattingSheet(
    formatting: ComicFormattingPreferences,
    hasBookOverrides: Boolean,
    onUpdate: (BookComicFormattingOverrides) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            // Panel View
            ListItem(
                headlineContent = { Text(stringResource(Res.string.ui_panel_view)) },
                supportingContent = { Text(stringResource(Res.string.ui_frame_one_panel_at_a_time_in_reading_order)) },
                trailingContent = {
                    Switch(
                        checked = formatting.panelViewOn,
                        onCheckedChange = { onUpdate(BookComicFormattingOverrides(panelViewOn = it)) },
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Panel Overflow
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
            val overflowOptions = listOf(
                Triple(
                    PanelOverflowBehavior.OFF,
                    stringResource(Res.string.ui_no_split),
                    stringResource(Res.string.ui_show_oversized_panels_as_is_without_splitting),
                ),
                Triple(
                    PanelOverflowBehavior.SPLIT,
                    stringResource(Res.string.ui_split),
                    stringResource(Res.string.ui_cuts_oversized_panels_in_half),
                ),
                Triple(
                    PanelOverflowBehavior.SMART_SPLIT,
                    stringResource(Res.string.ui_smart_split),
                    stringResource(Res.string.ui_smart_split_description),
                ),
            )
            val overflowEnabled = formatting.panelViewOn
            Column(Modifier.selectableGroup()) {
                overflowOptions.forEach { (behavior, label, description) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = { Text(description) },
                        leadingContent = {
                            RadioButton(
                                selected = formatting.panelOverflow == behavior,
                                onClick = null,
                                enabled = overflowEnabled,
                            )
                        },
                        modifier = Modifier
                            .alpha(if (overflowEnabled) 1f else 0.38f)
                            .then(
                                if (overflowEnabled) {
                                    Modifier.selectable(
                                        selected = formatting.panelOverflow == behavior,
                                        onClick = { onUpdate(BookComicFormattingOverrides(panelOverflow = behavior)) },
                                        role = Role.RadioButton,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Animation Speed
            val animEnabled = formatting.panelViewOn
            val speedLabel = if (formatting.panelAnimationSpeedMs == 0) {
                stringResource(Res.string.ui_off)
            } else {
                "${formatting.panelAnimationSpeedMs}ms"
            }
            Text(
                text = stringResource(Res.string.ui_animation_speed) + ": $speedLabel",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
            Slider(
                value = formatting.panelAnimationSpeedMs.toFloat(),
                onValueChange = { onUpdate(BookComicFormattingOverrides(panelAnimationSpeedMs = it.toInt())) },
                valueRange = 0f..600f,
                steps = 11,
                enabled = animEnabled,
                modifier = Modifier.padding(horizontal = 16.dp).alpha(if (animEnabled) 1f else 0.38f),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // On-screen info
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
