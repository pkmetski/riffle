package com.riffle.feature.settings.ui.sections
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

import androidx.compose.runtime.Composable

import com.riffle.feature.settings.ui.readersettings.localizedAutoScrollSummary
import com.riffle.feature.settings.ui.readersettings.localizedCadenceSummary
import com.riffle.feature.settings.ui.readersettings.localizedDisplaySummary
import com.riffle.feature.settings.ui.readersettings.localizedFormattingSummary
import com.riffle.feature.settings.ui.SettingsDrillInRow
import com.riffle.feature.settings.ui.SettingsPanel
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.designsystem.SettingsSectionHeader

@Composable
internal fun ReadingSection(
    globalFormatting: FormattingPreferences,
    onOpenPanel: (SettingsPanel) -> Unit,
) {
    // TODO: migrate to Res.string.ui_books
    SettingsSectionHeader(stringResource(Res.string.ui_books))
    SettingsDrillInRow(
        // TODO: migrate to Res.string.ui_formatting
        title = stringResource(Res.string.ui_formatting),
        summary = localizedFormattingSummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.Formatting) },
    )
    SettingsDrillInRow(
        // TODO: migrate to Res.string.ui_display
        title = stringResource(Res.string.ui_display),
        summary = localizedDisplaySummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.Display) },
    )
    SettingsDrillInRow(
        // TODO: migrate to Res.string.ui_auto_scroll
        title = stringResource(Res.string.ui_auto_scroll),
        summary = localizedAutoScrollSummary(globalFormatting),
        onClick = { onOpenPanel(SettingsPanel.AutoScroll) },
    )
    if (globalFormatting.cadencePlatformSupported) {
        SettingsDrillInRow(
            // TODO: migrate to Res.string.ui_cadence
            title = stringResource(Res.string.ui_cadence),
            summary = localizedCadenceSummary(globalFormatting),
            onClick = { onOpenPanel(SettingsPanel.Cadence) },
        )
    }
}
