package com.riffle.feature.settings.ui.panels
import androidx.compose.runtime.Composable
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.readersettings.BehaviorSection
import org.jetbrains.compose.resources.stringResource

@Composable
fun BehaviorSettingsPanel(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
// TODO: migrate to Res.string.ui_behavior
) = DetailScaffold(stringResource(Res.string.ui_behavior), onDismiss) {
    BehaviorSection(
        keepScreenOn, onKeepScreenOnChange,
        volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
        invertVolumeKeys, onInvertVolumeKeysChange,
    )
}
