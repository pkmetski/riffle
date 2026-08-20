package com.riffle.app.feature.settings.panels

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.reader.DisplaySection
import com.riffle.core.domain.FormattingPreferences

@Composable
fun DisplaySettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold(stringResource(R.string.ui_display), onDismiss) { DisplaySection(prefs, onPrefsChange, scheduleEditable = true) }
