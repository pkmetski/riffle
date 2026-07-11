package com.riffle.app.feature.settings.panels

import androidx.compose.runtime.Composable
import com.riffle.app.feature.reader.BehaviorSection

@Composable
fun BehaviorSettingsPanel(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Behavior", onDismiss) {
    BehaviorSection(
        keepScreenOn, onKeepScreenOnChange,
        volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
        invertVolumeKeys, onInvertVolumeKeysChange,
    )
}
