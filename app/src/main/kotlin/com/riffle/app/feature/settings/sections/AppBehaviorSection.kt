package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.BehaviorSection
import com.riffle.app.feature.settings.SettingsSectionHeader

@Composable
internal fun AppBehaviorSection(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
) {
    SettingsSectionHeader("Behavior")
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        BehaviorSection(
            keepScreenOn, onKeepScreenOnChange,
            volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
            invertVolumeKeys, onInvertVolumeKeysChange,
        )
    }
}
