package com.riffle.app.feature.settings.panels

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.app.feature.readersettings.CadenceHeroIcon
import com.riffle.app.feature.readersettings.swatchBackdropColor
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.withResolvedTheme
import java.time.LocalTime

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
) = DetailScaffold(stringResource(R.string.ui_cadence), onDismiss) {
    val systemInDark = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CadenceHeroIcon()
    }
    Text(
        text = stringResource(R.string.ui_cadence_description),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Text(
        text = stringResource(R.string.ui_this_icon_appears_in_the_reader_top_bar_to_start_and_stop_cadence),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp),
    )
    if (!platformSupported) {
        Text(
            text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cadence_webview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@DetailScaffold
    }
    ListItem(
        modifier = Modifier.toggleable(
            value = prefs.showCadence,
            onValueChange = { onPrefsChange(prefs.copy(showCadence = it)) },
        ),
        headlineContent = { Text(stringResource(R.string.ui_show_cadence)) },
        supportingContent = { Text(stringResource(R.string.ui_adds_the_toggle_to_the_reader_top_bar_all_orientations)) },
        trailingContent = {
            Switch(checked = prefs.showCadence, onCheckedChange = null)
        },
    )
    WpmSliderRow(
        label = stringResource(R.string.ui_default_speed),
        helper = stringResource(R.string.ui_per_book_override_formatting_panel_volume_keys),
        wpm = prefs.cadenceWpm,
        onWpmChange = { onPrefsChange(prefs.copy(cadenceWpm = it)) },
    )
    HighlightColorRow(
        selected = prefs.cadenceHighlightColor,
        // Resolve Auto → concrete so the picker previews against the paper Readium is currently
        // painting, not the Light fallback the palette accessor uses when Auto slips through.
        readerBackground = prefs.withResolvedTheme(LocalTime.now(), appTheme, systemInDark).swatchBackdropColor,
        onSelectedChange = { onPrefsChange(prefs.copy(cadenceHighlightColor = it)) },
    )
}
