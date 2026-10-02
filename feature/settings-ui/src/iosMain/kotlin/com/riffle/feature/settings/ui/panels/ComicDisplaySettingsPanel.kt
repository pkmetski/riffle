package com.riffle.feature.settings.ui.panels
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

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
internal actual fun ComicDisplaySettingsPanel(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold(stringResource(Res.string.ui_display), onDismiss) {
    Text(
        text = stringResource(Res.string.ui_panel_view),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    ListItem(
        headlineContent = { Text(stringResource(Res.string.ui_panel_view)) },
        supportingContent = { Text(stringResource(Res.string.ui_frame_one_panel_at_a_time_in_reading_order)) },
        trailingContent = {
            Switch(
                checked = prefs.panelViewOn,
                onCheckedChange = { onPrefsChange(prefs.copy(panelViewOn = it)) },
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
    val overflowOptions = listOf(
        PanelOverflowBehavior.OFF to stringResource(Res.string.ui_off),
        PanelOverflowBehavior.SPLIT to stringResource(Res.string.ui_split),
        PanelOverflowBehavior.SMART_SPLIT to stringResource(Res.string.ui_smart_split),
    )
    Column(Modifier.selectableGroup()) {
        overflowOptions.forEach { (behavior, label) ->
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = {
                    RadioButton(
                        selected = prefs.panelOverflow == behavior,
                        onClick = null,
                    )
                },
                modifier = Modifier.selectable(
                    selected = prefs.panelOverflow == behavior,
                    enabled = prefs.panelViewOn,
                    role = Role.RadioButton,
                    onClick = { onPrefsChange(prefs.copy(panelOverflow = behavior)) },
                ),
            )
        }
    }

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
            )
        },
    )
}
