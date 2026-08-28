package com.riffle.app.feature.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.readersettings.localizedAutoScrollSummary
import com.riffle.app.feature.readersettings.localizedCadenceSummary
import com.riffle.app.feature.readersettings.localizedDisplaySummary
import com.riffle.app.feature.readersettings.localizedFormattingSummary
import com.riffle.app.feature.settings.SettingsDrillInRow
import com.riffle.app.feature.settings.SettingsPanel
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.core.domain.FormattingPreferences

@Composable
internal fun ReadingSection(
    globalFormatting: FormattingPreferences,
    onOpenPanel: (SettingsPanel) -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.ui_books))
    SettingsDrillInRow(
        title = stringResource(R.string.ui_formatting),
        summary = localizedFormattingSummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.Formatting) },
    )
    SettingsDrillInRow(
        title = stringResource(R.string.ui_display),
        summary = localizedDisplaySummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.Display) },
    )
    SettingsDrillInRow(
        title = stringResource(R.string.ui_auto_scroll),
        summary = localizedAutoScrollSummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.AutoScroll) },
    )
    if (globalFormatting.cadencePlatformSupported) {
        SettingsDrillInRow(
            title = stringResource(R.string.ui_cadence),
            summary = localizedCadenceSummary(globalFormatting),
            onClick = { onOpenPanel(SettingsPanel.Cadence) },
        )
    }
}
