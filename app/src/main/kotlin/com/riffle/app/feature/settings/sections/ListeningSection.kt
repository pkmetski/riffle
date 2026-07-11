package com.riffle.app.feature.settings.sections

import androidx.compose.runtime.Composable
import com.riffle.app.feature.settings.SettingsDrillInRow
import com.riffle.app.feature.settings.SettingsPanel
import com.riffle.app.feature.settings.SettingsSectionHeader

/**
 * "Listening" section — audiobook-playback preferences. Distinct from Readaloud (which is
 * Storyteller-driven text-synced audio for a book that already has an ebook); Listening covers
 * the audiobook player's speed, skip, and rewind knobs regardless of source.
 */
@Composable
internal fun ListeningSection(onOpenPanel: (SettingsPanel) -> Unit) {
    SettingsSectionHeader("Listening")
    SettingsDrillInRow(
        title = "Preferences",
        summary = "Speed, skip interval, rewind on resume",
        onClick = { onOpenPanel(SettingsPanel.Listening) },
    )
}
