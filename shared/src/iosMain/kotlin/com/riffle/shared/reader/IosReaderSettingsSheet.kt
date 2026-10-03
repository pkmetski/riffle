package com.riffle.shared.reader

import androidx.compose.runtime.Composable
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.settings.RenderCapabilities
import com.riffle.feature.settings.ui.readersettings.ReaderSettingsSheet

@Composable
fun IosReaderSettingsSheet(
    prefs: FormattingPreferences,
    hasBookOverrides: Boolean,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ReaderSettingsSheet(
        prefs = prefs,
        capabilities = RenderCapabilities.EPUB,
        hasBookOverrides = hasBookOverrides,
        onPrefsChange = onPrefsChange,
        onReset = onReset,
        onDismiss = onDismiss,
    )
}
