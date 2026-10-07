package com.riffle.feature.settings.ui.sections
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.readersettings.BehaviorSection
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AppBehaviorSection(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
) {
    // TODO: migrate to Res.string.ui_behavior
    SettingsSectionHeader(stringResource(Res.string.ui_behavior))
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        BehaviorSection(
            keepScreenOn, onKeepScreenOnChange,
            volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
            invertVolumeKeys, onInvertVolumeKeysChange,
        )
    }
}
