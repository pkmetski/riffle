package com.riffle.feature.settings.ui.panels
import androidx.compose.runtime.Composable
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.designsystem.generated.resources.ui_formatting
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.readersettings.FormattingSection
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.designsystem.generated.resources.Res as DsRes

@Composable
fun FormattingSettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
// TODO: migrate to DsRes.string.ui_formatting
) = DetailScaffold(stringResource(DsRes.string.ui_formatting), onDismiss) { FormattingSection(prefs, onPrefsChange) }
