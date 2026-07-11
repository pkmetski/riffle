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
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.cadence.CadenceHeroIcon
import com.riffle.core.domain.FormattingPreferences

/**
 * Cadence drill-in — the sentence-highlight hands-free reading feature. See issue #403 / ADR 0040.
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
    onPrefsChange: (FormattingPreferences) -> Unit,
    platformSupported: Boolean = true,
    onDismiss: () -> Unit,
) = DetailScaffold("Cadence", onDismiss) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CadenceHeroIcon()
    }
    Text(
        text = "Cadence highlights one sentence at a time and advances on its own at a set " +
            "pace, so you can read hands-free. Start and stop from the reader top bar; " +
            "nudge speed with the volume keys while running.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Text(
        text = "This icon appears in the reader top bar to start and stop Cadence.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp),
    )
    if (!platformSupported) {
        Text(
            text = "Cadence isn't available on this device's WebView. Update the Android System " +
                "WebView from the Play Store to enable it.",
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
        headlineContent = { Text("Show Cadence") },
        supportingContent = { Text("Adds the toggle to the reader top bar (all orientations)") },
        trailingContent = {
            Switch(checked = prefs.showCadence, onCheckedChange = null)
        },
    )
    WpmSliderRow(
        label = "Default speed",
        helper = "Per-book override in the reader's Formatting panel. Adjust live with the volume keys while running.",
        wpm = prefs.cadenceWpm,
        onWpmChange = { onPrefsChange(prefs.copy(cadenceWpm = it)) },
    )
    HighlightColorRow(
        selected = prefs.cadenceHighlightColor,
        onSelectedChange = { onPrefsChange(prefs.copy(cadenceHighlightColor = it)) },
    )
}
