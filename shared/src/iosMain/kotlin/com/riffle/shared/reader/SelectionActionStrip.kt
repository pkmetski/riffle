package com.riffle.shared.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.ui_action_copy
import com.riffle.shared.generated.resources.ui_action_play_from_here
import com.riffle.shared.generated.resources.ui_action_search
import com.riffle.shared.generated.resources.ui_action_share
import org.jetbrains.compose.resources.stringResource

/**
 * A compact action strip shown while text is selected, before the user picks "Annotate".
 *
 * Mirrors the Android ActionMode items (Copy / Search / Share / Play from here) so both platforms
 * surface the same affordances for plain text selection.
 *
 * [onPlayFromHere] is non-null only when readaloud is active — matches the
 * `currentReadaloudAvailable` gate in Android's `onCreateActionMode`.
 */
@Composable
internal fun SelectionActionStrip(
    onCopy: () -> Unit,
    onSearchWeb: () -> Unit,
    onShare: () -> Unit,
    onPlayFromHere: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            TextButton(onClick = onCopy) { Text(stringResource(Res.string.ui_action_copy), style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onSearchWeb) { Text(stringResource(Res.string.ui_action_search), style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onShare) { Text(stringResource(Res.string.ui_action_share), style = MaterialTheme.typography.labelMedium) }
            if (onPlayFromHere != null) {
                TextButton(onClick = onPlayFromHere) { Text(stringResource(Res.string.ui_action_play_from_here), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
