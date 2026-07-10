package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Device-behavior toggles that apply to all books. Global in both hosts (reader sheet Behavior tab
 * and Settings → Behavior) — never per-book. Row rhythm mirrors [DisplaySection]'s on-screen-info
 * toggles: single-line label + Switch, no per-row supporting text, no explicit spacers. Row height
 * is pinned to a 48dp minimum on the [Row] itself — the Switch here uses `onCheckedChange = null`
 * (the whole row is the tap target via [toggleable], required by `WakeLockHarnessTest` which taps
 * the label), and Material3 only applies `minimumInteractiveComponentSize` to a Switch when its
 * `onCheckedChange` is non-null, so without the explicit `heightIn` the row would collapse to the
 * Switch's ~32dp intrinsic height.
 */
@Composable
fun BehaviorSection(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
) {
    Column {
        ToggleRow("Keep screen on", keepScreenOn, onKeepScreenOnChange)
        ToggleRow("Volume key navigation", volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange)
        ToggleRow(
            label = "Invert volume keys",
            checked = invertVolumeKeys,
            onChange = onInvertVolumeKeysChange,
            enabled = volumeKeyNavigationEnabled,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
