package com.riffle.feature.settings.ui.panels
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

import androidx.compose.runtime.Composable
import com.riffle.core.domain.comic.ComicFormattingPreferences

@Composable
internal actual fun ComicDisplaySettingsPanel(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
// TODO: migrate to Res.string.ui_display
) = DetailScaffold(stringResource(Res.string.ui_display), onDismiss) {
    // TODO: ComicDisplaySection must be moved from :app to :feature:settings-ui before this compiles.
    // See: https://github.com/pkmetski/riffle/issues/TODO
    // ComicDisplaySection(
    //     prefs = prefs,
    //     onBackgroundThemeChange = { onPrefsChange(prefs.copy(backgroundTheme = it)) },
    //     onPanelViewChange = { onPrefsChange(prefs.copy(panelViewOn = it)) },
    //     onPanelOverflowChange = { onPrefsChange(prefs.copy(panelOverflow = it)) },
    //     onPanelAnimationSpeedChange = { onPrefsChange(prefs.copy(panelAnimationSpeedMs = it)) },
    //     onShowReadingProgressChange = { onPrefsChange(prefs.copy(showChapterMap = it)) },
    //     onShowPageNumbersChange = { onPrefsChange(prefs.copy(showPageProgress = it)) },
    // )
}
