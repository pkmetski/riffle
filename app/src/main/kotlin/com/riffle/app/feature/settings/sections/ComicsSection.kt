package com.riffle.app.feature.settings.sections

import androidx.compose.runtime.Composable
import com.riffle.app.feature.settings.SettingsDrillInRow
import com.riffle.app.feature.settings.SettingsPanel
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior

@Composable
internal fun ComicsSection(
    comicFormatting: ComicFormattingPreferences,
    onOpenPanel: (SettingsPanel) -> Unit,
) {
    SettingsSectionHeader("Comics")
    SettingsDrillInRow(
        title = "Panel View",
        summary = if (comicFormatting.panelViewOn) "On" else "Off",
        onClick = { onOpenPanel(SettingsPanel.PanelView) },
    )
    SettingsDrillInRow(
        title = "Panel Overflow",
        summary = when (comicFormatting.panelOverflow) {
            PanelOverflowBehavior.SPLIT -> "Split at centre"
            PanelOverflowBehavior.SMART_SPLIT -> "Smart split"
            PanelOverflowBehavior.OFF -> "Off"
        },
        onClick = { onOpenPanel(SettingsPanel.PanelOverflow) },
    )
}
