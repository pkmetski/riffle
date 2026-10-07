package com.riffle.feature.settings.ui.sections
import androidx.compose.runtime.Composable
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.settings.ui.SettingsDrillInRow
import com.riffle.feature.settings.ui.SettingsPanel
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun localizedComicDisplaySummary(prefs: ComicFormattingPreferences): String {
    val parts = mutableListOf(
        if (prefs.panelViewOn) {
            when (prefs.panelOverflow) {
                // TODO: migrate to Res.string.ui_panel_view_split
                PanelOverflowBehavior.SPLIT -> stringResource(Res.string.ui_panel_view_split)
                // TODO: migrate to Res.string.ui_panel_view_smart_split
                PanelOverflowBehavior.SMART_SPLIT -> stringResource(Res.string.ui_panel_view_smart_split)
                // TODO: migrate to Res.string.ui_panel_view_no_split
                PanelOverflowBehavior.OFF -> stringResource(Res.string.ui_panel_view_no_split)
            }
        } else {
            // TODO: migrate to Res.string.ui_panel_view_off
            stringResource(Res.string.ui_panel_view_off)
        },
    )
    if (prefs.showChapterMap) {
        // TODO: migrate to Res.string.ui_reading_progress
        parts += stringResource(Res.string.ui_reading_progress)
        if (prefs.showPageProgress) {
            // TODO: migrate to Res.string.ui_page_numbers
            parts += stringResource(Res.string.ui_page_numbers)
        }
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun ComicsSection(
    comicFormatting: ComicFormattingPreferences,
    onOpenPanel: (SettingsPanel) -> Unit,
) {
    // TODO: migrate to Res.string.ui_comics
    SettingsSectionHeader(stringResource(Res.string.ui_comics))
    SettingsDrillInRow(
        // TODO: migrate to Res.string.ui_display
        title = stringResource(Res.string.ui_display),
        summary = localizedComicDisplaySummary(comicFormatting),
        onClick = { onOpenPanel(SettingsPanel.ComicDisplay) },
    )
}
