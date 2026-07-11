package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.autoscroll.AutoScrollToggleIcon
import com.riffle.app.feature.reader.cadence.CadenceHeroIcon
import com.riffle.app.feature.settings.SettingsPanel
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.core.domain.FormattingPreferences

/**
 * "Pacing" section — hands-free reading aids. Auto-Scroll drives the page upward at a wpm-set
 * pace; Cadence highlights one sentence at a time and advances at its own wpm. Both are
 * independent of Readaloud (which requires a Storyteller Service configured under its own
 * section) — Cadence is a pure client-side reading aid; the shared placement here reflects the
 * shared mechanism, not a coupling to audio sources.
 *
 * The Cadence row hides when the current device's WebView doesn't provide `Intl.Segmenter`
 * (e.g. Android 7.1.1 with a stale system WebView) — the reader persists that flag on first JS
 * feature-detect so Settings never advertises a runtime button it can't render.
 */
@Composable
internal fun PacingSection(
    globalFormatting: FormattingPreferences,
    onOpenPanel: (SettingsPanel) -> Unit,
) {
    SettingsSectionHeader("Pacing")
    ListItem(
        modifier = Modifier.clickable { onOpenPanel(SettingsPanel.AutoScroll) },
        leadingContent = {
            AutoScrollToggleIcon(
                isRunning = false,
                onClick = { onOpenPanel(SettingsPanel.AutoScroll) },
            )
        },
        headlineContent = { Text("Auto-Scroll") },
        supportingContent = {
            Text(
                if (globalFormatting.showAutoScroll)
                    "Hands-free scroll — ${globalFormatting.autoScrollWpm} wpm"
                else "Off",
            )
        },
        trailingContent = {
            TextButton(onClick = { onOpenPanel(SettingsPanel.AutoScroll) }) { Text("Edit") }
        },
    )
    if (globalFormatting.cadencePlatformSupported) {
        ListItem(
            modifier = Modifier.clickable { onOpenPanel(SettingsPanel.Cadence) },
            leadingContent = {
                IconButton(onClick = { onOpenPanel(SettingsPanel.Cadence) }) {
                    CadenceHeroIcon(size = 24.dp)
                }
            },
            headlineContent = { Text("Cadence") },
            supportingContent = {
                Text(
                    if (globalFormatting.showCadence)
                        "Sentence highlight — ${globalFormatting.cadenceWpm} wpm"
                    else "Off",
                )
            },
            trailingContent = {
                TextButton(onClick = { onOpenPanel(SettingsPanel.Cadence) }) { Text("Edit") }
            },
        )
    }
}
