package com.riffle.shared.reader

import androidx.compose.runtime.Composable
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.settings.RenderCapabilities
import com.riffle.feature.settings.ui.readersettings.ReaderSettingsSheet

/**
 * iOS wrapper around the shared [ReaderSettingsSheet]. Per-book overrides are not yet
 * implemented on iOS, so the "Reset to global defaults" button is always disabled.
 */
@Composable
fun IosReaderSettingsSheet(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ReaderSettingsSheet(
        prefs = prefs,
        capabilities = RenderCapabilities.EPUB,
        hasBookOverrides = false,
        onPrefsChange = onPrefsChange,
        onReset = {},
        onDismiss = onDismiss,
    )
}
