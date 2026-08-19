package com.riffle.app.feature.settings.panels

import androidx.compose.runtime.Composable
import com.riffle.app.feature.reader.cbz.ComicDisplaySection
import com.riffle.core.domain.comic.ComicFormattingPreferences

@Composable
internal fun ComicDisplaySettingsPanel(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Display", onDismiss) {
    ComicDisplaySection(
        prefs = prefs,
        onPanelViewChange = { onPrefsChange(prefs.copy(panelViewOn = it)) },
        onPanelOverflowChange = { onPrefsChange(prefs.copy(panelOverflow = it)) },
        onShowReadingProgressChange = { onPrefsChange(prefs.copy(showChapterMap = it)) },
        onShowPageNumbersChange = { onPrefsChange(prefs.copy(showPageProgress = it)) },
    )
}
