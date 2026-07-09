package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role

/**
 * Device-behavior toggles that apply to all books. Global in both hosts (reader sheet Behavior tab
 * and Settings → Behavior) — never per-book. Row rhythm mirrors [DisplaySection]'s on-screen-info
 * toggles: single-line label + Switch, no per-row supporting text, no explicit spacers — the
 * Switch's 48dp minimum interactive size dictates row height uniformly. The row itself is the
 * toggle target (Role.Switch) so tapping the label toggles, matching the pre-existing UX and the
 * `WakeLockHarnessTest` which drives the wake-lock preference via a label tap.
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
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
