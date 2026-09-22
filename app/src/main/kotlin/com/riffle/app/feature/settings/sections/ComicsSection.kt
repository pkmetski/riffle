package com.riffle.app.feature.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.settings.SettingsDrillInRow
import com.riffle.app.feature.settings.SettingsPanel
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.feature.designsystem.SettingsSectionHeader

@Composable
internal fun localizedComicDisplaySummary(prefs: ComicFormattingPreferences): String {
    val parts = mutableListOf(
        if (prefs.panelViewOn) {
            when (prefs.panelOverflow) {
                PanelOverflowBehavior.SPLIT -> stringResource(R.string.ui_panel_view_split)
                PanelOverflowBehavior.SMART_SPLIT -> stringResource(R.string.ui_panel_view_smart_split)
                PanelOverflowBehavior.OFF -> stringResource(R.string.ui_panel_view_no_split)
            }
        } else {
            stringResource(R.string.ui_panel_view_off)
        },
    )
    if (prefs.showChapterMap) {
        parts += stringResource(R.string.ui_reading_progress)
        if (prefs.showPageProgress) {
            parts += stringResource(R.string.ui_page_numbers)
        }
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun ComicsSection(
    comicFormatting: ComicFormattingPreferences,
    onOpenPanel: (SettingsPanel) -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.ui_comics))
    SettingsDrillInRow(
        title = stringResource(R.string.ui_display),
        summary = localizedComicDisplaySummary(comicFormatting),
        onClick = { onOpenPanel(SettingsPanel.ComicDisplay) },
    )
}
