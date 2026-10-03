package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import com.riffle.app.feature.readersettings.formatting.RenderCapabilities
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.settings.ui.readersettings.ReaderSettingsSheet as SharedReaderSettingsSheet

@Composable
fun ReaderSettingsSheet(
    prefs: FormattingPreferences,
    capabilities: RenderCapabilities,
    hasBookOverrides: Boolean,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) = SharedReaderSettingsSheet(
    prefs = prefs,
    capabilities = capabilities,
    hasBookOverrides = hasBookOverrides,
    onPrefsChange = onPrefsChange,
    onReset = onReset,
    onDismiss = onDismiss,
)
