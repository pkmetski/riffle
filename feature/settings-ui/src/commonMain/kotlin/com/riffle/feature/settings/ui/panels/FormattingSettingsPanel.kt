package com.riffle.feature.settings.ui.panels
import androidx.compose.runtime.Composable
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.readersettings.FormattingSection
import org.jetbrains.compose.resources.stringResource

@Composable
fun FormattingSettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
// TODO: migrate to Res.string.ui_formatting
) = DetailScaffold(stringResource(Res.string.ui_formatting), onDismiss) { FormattingSection(prefs, onPrefsChange) }
