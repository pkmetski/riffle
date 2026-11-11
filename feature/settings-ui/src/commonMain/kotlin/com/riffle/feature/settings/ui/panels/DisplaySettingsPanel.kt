package com.riffle.feature.settings.ui.panels
import androidx.compose.runtime.Composable
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.readersettings.DisplaySection
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.designsystem.generated.resources.Res as DsRes
import com.riffle.feature.designsystem.generated.resources.ui_display

@Composable
fun DisplaySettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
// TODO: migrate to DsRes.string.ui_display
) = DetailScaffold(stringResource(DsRes.string.ui_display), onDismiss) { DisplaySection(prefs, onPrefsChange, scheduleEditable = true) }
