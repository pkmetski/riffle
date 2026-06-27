package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * and Settings → Behavior) — never per-book.
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(
                value = keepScreenOn, role = Role.Switch, onValueChange = onKeepScreenOnChange,
            ),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                Text("Applies to all books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = keepScreenOn, onCheckedChange = null)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(
                value = volumeKeyNavigationEnabled, role = Role.Switch, onValueChange = onVolumeKeyNavigationEnabledChange,
            ),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Volume key navigation", style = MaterialTheme.typography.bodyLarge)
                Text("Applies to all books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = volumeKeyNavigationEnabled, onCheckedChange = null)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .toggleable(
                    value = invertVolumeKeys,
                    enabled = volumeKeyNavigationEnabled,
                    role = Role.Switch,
                    onValueChange = onInvertVolumeKeysChange,
                )
                .alpha(if (volumeKeyNavigationEnabled) 1f else 0.38f),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Invert volume keys", style = MaterialTheme.typography.bodyLarge)
                Text("Applies to all books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = invertVolumeKeys, onCheckedChange = null, enabled = volumeKeyNavigationEnabled)
        }
    }
}
