@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.riffle.app.feature.reader.readaloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Formats the speed as the spec wants it: 1×, 1.25×, 0.75×, … (trailing zeros trimmed). */
private fun speedLabel(speed: Float): String {
    val s = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')
    return "${s}×"
}

/**
 * Bottom mini-player bar. Tapping the bar body (not a control) expands to the full sheet.
 * Sits above the chapter rail in the screen layout.
 */
@Composable
fun ReadaloudMiniPlayer(
    isPlaying: Boolean,
    speed: Float,
    offlineMessage: Boolean,
    downloadProgress: Float?,
    onPlayPause: () -> Unit,
    onCycleSpeed: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // inverseSurface/onInverseSurface keeps the bar legible over any reader theme — same pair the
    // PullChip uses.
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier
            .fillMaxWidth()
            .testTag("readaloud_mini_player"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !offlineMessage) { onExpand() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (offlineMessage) {
                Text(
                    text = "Connect to download readaloud audio",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag("readaloud_offline_message"),
                )
            } else if (downloadProgress != null) {
                Text(
                    text = "Downloading… ${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag("readaloud_downloading"),
                )
            } else {
                IconButton(onClick = onPlayPause, modifier = Modifier.testTag("readaloud_play_pause")) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                TextButton(onClick = onCycleSpeed, modifier = Modifier.testTag("readaloud_speed")) {
                    Text(
                        speedLabel(speed),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag("readaloud_close")) {
                Icon(Icons.Default.Close, contentDescription = "Close readaloud")
            }
        }
    }
}

/**
 * Full-height expanded player. A scrubbable position slider plus the same play/pause and a
 * row of selectable speeds.
 */
@Composable
fun ReadaloudExpandedSheet(
    isPlaying: Boolean,
    speed: Float,
    positionSec: Double,
    onPlayPause: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Local scrub state: while the user drags, show the dragged value; commit visually only.
    // We can't seek a position the controller doesn't expose, so the slider reflects the live
    // position and dragging is best-effort visual (see limitation note in EpubReaderScreen).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .testTag("readaloud_expanded_sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Readaloud", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Slider(
                value = positionSec.toFloat(),
                onValueChange = { /* live position is driven by playback; scrub is visual-only */ },
                valueRange = 0f..(positionSec.toFloat().coerceAtLeast(1f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("readaloud_position_slider"),
            )
            Spacer(modifier = Modifier.height(8.dp))
            IconButton(onClick = onPlayPause, modifier = Modifier.testTag("readaloud_sheet_play_pause")) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadaloudController.SPEEDS.forEach { s ->
                    val selected = kotlin.math.abs(s - speed) < 0.001f
                    TextButton(onClick = { onSpeedSelected(s) }) {
                        Text(
                            speedLabel(s),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** "Download readaloud audio (X GB)" confirm dialog with a Wi-Fi-only toggle. */
@Composable
fun ReadaloudDownloadDialog(
    sizeBytes: Long,
    onConfirm: (wifiOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var wifiOnly by remember { mutableStateOf(true) }
    val sizeLabel = formatBytes(sizeBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("readaloud_download_dialog"),
        title = { Text("Download readaloud audio ($sizeLabel)") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wi-Fi only", modifier = Modifier.weight(1f))
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it },
                    modifier = Modifier.testTag("readaloud_wifi_only"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(wifiOnly) }) { Text("Download") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
}
