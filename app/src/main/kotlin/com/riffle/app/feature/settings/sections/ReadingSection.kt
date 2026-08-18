package com.riffle.app.feature.settings.sections

import androidx.compose.runtime.Composable
import com.riffle.app.feature.reader.autoScrollSummary
import com.riffle.app.feature.reader.cadenceSummary
import com.riffle.app.feature.reader.displaySummary
import com.riffle.app.feature.reader.formattingSummary
import com.riffle.app.feature.settings.SettingsDrillInRow
import com.riffle.app.feature.settings.SettingsPanel
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.core.domain.FormattingPreferences

@Composable
internal fun ReadingSection(
    globalFormatting: FormattingPreferences,
    onOpenPanel: (SettingsPanel) -> Unit,
) {
    SettingsSectionHeader("Books")
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
        title = "Auto-Scroll",
        summary = autoScrollSummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.AutoScroll) },
    )
    if (globalFormatting.cadencePlatformSupported) {
        SettingsDrillInRow(
            title = "Cadence",
            summary = cadenceSummary(globalFormatting),
            onClick = { onOpenPanel(SettingsPanel.Cadence) },
        )
    }
}
