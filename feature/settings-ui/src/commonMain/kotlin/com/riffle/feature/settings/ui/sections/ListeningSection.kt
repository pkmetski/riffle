package com.riffle.feature.settings.ui.sections
import androidx.compose.runtime.Composable
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.settings.ui.SettingsDrillInRow
import com.riffle.feature.settings.ui.SettingsPanel
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * "Listening" section — audiobook-playback preferences. Distinct from Readaloud (which is
 * Storyteller-driven text-synced audio for a book that already has an ebook); Listening covers
 * the audiobook player's speed, skip, and rewind knobs regardless of source.
 */
@Composable
internal fun ListeningSection(onOpenPanel: (SettingsPanel) -> Unit) {
    // TODO: migrate to Res.string.ui_listening
    SettingsSectionHeader(stringResource(Res.string.ui_listening))
    SettingsDrillInRow(
        // TODO: migrate to Res.string.ui_preferences
        title = stringResource(Res.string.ui_preferences),
        // TODO: migrate to Res.string.ui_listening_preferences_summary
        summary = stringResource(Res.string.ui_listening_preferences_summary),
        onClick = { onOpenPanel(SettingsPanel.Listening) },
    )
}
