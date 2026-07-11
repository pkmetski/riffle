package com.riffle.app.feature.settings.panels

import androidx.compose.runtime.Composable
import com.riffle.app.feature.reader.FormattingSection
import com.riffle.core.domain.FormattingPreferences

@Composable
fun FormattingSettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Formatting", onDismiss) { FormattingSection(prefs, onPrefsChange) }
