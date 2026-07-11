package com.riffle.app.feature.settings.sections

import androidx.compose.runtime.Composable
import com.riffle.app.feature.reader.behaviorSummary
import com.riffle.app.feature.reader.displaySummary
import com.riffle.app.feature.reader.formattingSummary
import com.riffle.app.feature.settings.SettingsDrillInRow
import com.riffle.app.feature.settings.SettingsPanel
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.core.domain.FormattingPreferences

/**
 * "Reading" section — presentation-of-the-book preferences. Formatting = typography (fonts, sizes),
 * Display = layout (paginated/vertical/continuous, dark), Behavior = device inputs (keep screen on,
 * volume-key navigation). Pacing (Auto-Scroll + Cadence) lives in its own section — same shape,
 * different concern.
 */
@Composable
internal fun ReadingSection(
    globalFormatting: FormattingPreferences,
    keepScreenOn: Boolean,
    volumeKeyNavigationEnabled: Boolean,
    onOpenPanel: (SettingsPanel) -> Unit,
) {
    SettingsSectionHeader("Reading")
    SettingsDrillInRow(
        title = "Formatting",
        summary = formattingSummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.Formatting) },
    )
    SettingsDrillInRow(
        title = "Display",
        summary = displaySummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.Display) },
    )
    SettingsDrillInRow(
        title = "Behavior",
        summary = behaviorSummary(keepScreenOn, volumeKeyNavigationEnabled),
        onClick = { onOpenPanel(SettingsPanel.Behavior) },
    )
}
