package com.riffle.app.feature.settings.panels

import androidx.compose.runtime.Composable
import com.riffle.app.feature.reader.DisplaySection
import com.riffle.core.domain.FormattingPreferences

@Composable
fun DisplaySettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Display", onDismiss) { DisplaySection(prefs, onPrefsChange, scheduleEditable = true) }
