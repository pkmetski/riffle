package com.riffle.feature.settings.ui.panels
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.ui.platform.testTag
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.ui.readersettings.CadenceHeroIcon
import com.riffle.feature.settings.ui.readersettings.swatchBackdropColor
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.LocalMinuteTime
import com.riffle.core.domain.withResolvedTheme

/**
 * Cadence drill-in — the sentence-highlight hands-free reading feature. See issue #403 / ADR 0047.
 *
 * [platformSupported] is the WebView `Intl.Segmenter` gate. When false, the whole drill-in body
 * shows a "not supported on this WebView" note instead of the toggles — same posture as
 * Storyteller-not-configured. The Pacing-list row that opens this panel should also hide itself
 * globally in that case; this fallback body is a defence in depth in case the user reaches the
 * panel some other way (e.g. quick-settings deep link).
 */
@Composable
fun CadenceSettingsPanel(
    prefs: FormattingPreferences,
    appTheme: AppTheme = AppTheme.System,
    onPrefsChange: (FormattingPreferences) -> Unit,
    platformSupported: Boolean = true,
    onDismiss: () -> Unit,
// TODO: migrate to Res.string.ui_cadence
) = DetailScaffold(stringResource(Res.string.ui_cadence), onDismiss) {
    val systemInDark = isSystemInDarkTheme()
    if (!platformSupported) {
        Text(
            text = stringResource(Res.string.ui_cadence_webview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@DetailScaffold
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CadenceHeroIcon()
    }
    Text(
        // TODO: migrate to Res.string.ui_cadence_description
        text = stringResource(Res.string.ui_cadence_description),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Text(
        // TODO: migrate to Res.string.ui_this_icon_appears_in_the_reader_top_bar_to_start_and_stop_cadence
        text = stringResource(Res.string.ui_this_icon_appears_in_the_reader_top_bar_to_start_and_stop_cadence),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp),
    )
    ListItem(
        modifier = Modifier
            .testTag(TestTags.SETTINGS_CADENCE_READER_TOGGLE)
            .toggleable(
                value = prefs.showCadence,
                onValueChange = { onPrefsChange(prefs.copy(showCadence = it)) },
            ),
        // TODO: migrate to Res.string.ui_show_cadence
        headlineContent = { Text(stringResource(Res.string.ui_show_cadence)) },
        // TODO: migrate to Res.string.ui_adds_the_toggle_to_the_reader_top_bar_all_orientations
        supportingContent = { Text(stringResource(Res.string.ui_adds_the_toggle_to_the_reader_top_bar_all_orientations)) },
        trailingContent = {
            Switch(checked = prefs.showCadence, onCheckedChange = null)
        },
    )
    WpmSliderRow(
        // TODO: migrate to Res.string.ui_default_speed
        label = stringResource(Res.string.ui_default_speed),
        // TODO: migrate to Res.string.ui_per_book_override_formatting_panel_volume_keys
        helper = stringResource(Res.string.ui_per_book_override_formatting_panel_volume_keys),
        wpm = prefs.cadenceWpm,
        onWpmChange = { onPrefsChange(prefs.copy(cadenceWpm = it)) },
        decrementTestTag = TestTags.SETTINGS_CADENCE_WPM_DECREMENT,
        incrementTestTag = TestTags.SETTINGS_CADENCE_WPM_INCREMENT,
    )
    HighlightColorRow(
        selected = prefs.cadenceHighlightColor,
        // Resolve Auto → concrete so the picker previews against the paper Readium is currently
        // painting, not the Light fallback the palette accessor uses when Auto slips through.
        readerBackground = prefs.withResolvedTheme(run {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            LocalMinuteTime(now.hour, now.minute)
        }, appTheme, systemInDark).swatchBackdropColor,
        onSelectedChange = { onPrefsChange(prefs.copy(cadenceHighlightColor = it)) },
    )
}
