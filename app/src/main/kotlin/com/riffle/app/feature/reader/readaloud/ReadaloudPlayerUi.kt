@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.riffle.app.feature.reader.readaloud

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.riffle.app.R

/** Formats the speed as the spec wants it: 1×, 1.25×, 0.75×, … (trailing zeros trimmed). */
private fun speedLabel(speed: Float): String {
    val s = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')
    return "${s}×"
}


/**
 * Bottom mini-player bar. Sits above the chapter rail in the screen layout.
 */
@Composable
fun ReadaloudMiniPlayer(
    isPlaying: Boolean,
    speed: Float,
    offlineMessage: Boolean,
    downloadProgress: Float?,
    canPreviousChapter: Boolean,
    canNextChapter: Boolean,
    containerColor: Color,
    contentColor: Color,
    onPlayPause: () -> Unit,
    onCycleSpeed: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Colours come from the reader-theme palette (the same page colour the chapter-rail overlay
    // paints as its backdrop), so the player, the progress labels, and the rail read as one
    // continuous, theme-following strip rather than separate chrome. The bar [containerColor] is
    // slightly translucent so the floating player lets the covered text/highlight show through.
    Surface(
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .fillMaxWidth()
            .testTag("readaloud_mini_player"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                TextButton(onClick = onCycleSpeed, modifier = Modifier.testTag("readaloud_speed")) {
                    Text(
                        speedLabel(speed),
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRewind, modifier = Modifier.testTag("readaloud_rewind")) {
                    Icon(
                        painter = painterResource(R.drawable.ic_replay_15),
                        contentDescription = "Rewind 15 seconds",
                        tint = contentColor,
                    )
                }
                IconButton(
                    onClick = onPreviousChapter,
                    enabled = canPreviousChapter,
                    modifier = Modifier.testTag("readaloud_prev_chapter"),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter")
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.testTag("readaloud_play_pause")) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(
                    onClick = onNextChapter,
                    enabled = canNextChapter,
                    modifier = Modifier.testTag("readaloud_next_chapter"),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter")
                }
                IconButton(onClick = onForward, modifier = Modifier.testTag("readaloud_forward")) {
                    Icon(
                        imageVector = Icons.Filled.Forward30,
                        contentDescription = "Forward 30 seconds",
                        tint = contentColor,
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
