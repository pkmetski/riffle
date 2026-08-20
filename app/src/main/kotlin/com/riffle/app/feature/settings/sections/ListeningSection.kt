package com.riffle.app.feature.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
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
    SettingsSectionHeader(stringResource(R.string.ui_listening))
    SettingsDrillInRow(
        title = stringResource(R.string.ui_preferences),
        summary = stringResource(R.string.ui_listening_preferences_summary),
        onClick = { onOpenPanel(SettingsPanel.Listening) },
    )
}
