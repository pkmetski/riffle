package com.riffle.app.feature.settings.panels

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.readersettings.BehaviorSection

@Composable
fun BehaviorSettingsPanel(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold(stringResource(R.string.ui_behavior), onDismiss) {
    BehaviorSection(
        keepScreenOn, onKeepScreenOnChange,
        volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
        invertVolumeKeys, onInvertVolumeKeysChange,
    )
}
