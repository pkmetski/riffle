package com.riffle.app.feature.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.settings.SettingsDrillInRow
import com.riffle.app.feature.settings.SettingsPanel
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.readersettings.label
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.asComicBackgroundTheme

internal fun comicDisplaySummary(prefs: ComicFormattingPreferences): String = buildString {
    val backgroundTheme = prefs.backgroundTheme.asComicBackgroundTheme()
    append(
        if (backgroundTheme == ReaderTheme.Auto) {
            "Auto background"
        } else {
            "${backgroundTheme.label()} background"
        }
    )
    append(" · ")
    append(
        if (prefs.panelViewOn) when (prefs.panelOverflow) {
            PanelOverflowBehavior.SPLIT -> "Panel View · Split"
            PanelOverflowBehavior.SMART_SPLIT -> "Panel View · Smart split"
            PanelOverflowBehavior.OFF -> "Panel View · No split"
        } else "Panel View off"
    )
    if (prefs.showChapterMap) {
        append(" · Reading progress")
        if (prefs.showPageProgress) append(" · Page numbers")
    }
}

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
