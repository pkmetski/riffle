package com.riffle.feature.settings.ui.panels
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

import androidx.compose.runtime.Composable
import com.riffle.core.domain.comic.ComicFormattingPreferences

/** iOS stub — ComicDisplaySection lives in :app (Android-only) until it is migrated to :feature:settings-ui. */
@Composable
internal actual fun ComicDisplaySettingsPanel(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold(stringResource(Res.string.ui_display), onDismiss) {
    // No-op on iOS until ComicDisplaySection is migrated out of :app.
}
