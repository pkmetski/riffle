package com.riffle.app.feature.settings.panels

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.reader.cbz.ComicDisplaySection
import com.riffle.core.domain.comic.ComicFormattingPreferences

@Composable
internal fun ComicDisplaySettingsPanel(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold(stringResource(R.string.ui_display), onDismiss) {
    ComicDisplaySection(
        prefs = prefs,
        onPanelViewChange = { onPrefsChange(prefs.copy(panelViewOn = it)) },
        onPanelOverflowChange = { onPrefsChange(prefs.copy(panelOverflow = it)) },
        onPanelAnimationSpeedChange = { onPrefsChange(prefs.copy(panelAnimationSpeedMs = it)) },
        onShowReadingProgressChange = { onPrefsChange(prefs.copy(showChapterMap = it)) },
        onShowPageNumbersChange = { onPrefsChange(prefs.copy(showPageProgress = it)) },
    )
}
