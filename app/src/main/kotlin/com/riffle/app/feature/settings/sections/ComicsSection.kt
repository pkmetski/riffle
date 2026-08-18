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
        title = "Display",
        summary = if (comicFormatting.panelViewOn) when (comicFormatting.panelOverflow) {
            PanelOverflowBehavior.SPLIT -> "Panel View · Split"
            PanelOverflowBehavior.SMART_SPLIT -> "Panel View · Smart split"
            PanelOverflowBehavior.OFF -> "Panel View on"
        } else "Panel View off",
        onClick = { onOpenPanel(SettingsPanel.ComicDisplay) },
    )
}
