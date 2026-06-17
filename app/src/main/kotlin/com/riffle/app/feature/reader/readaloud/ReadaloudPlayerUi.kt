@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.riffle.app.feature.reader.readaloud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.audio.PlaybackSpeed
import com.riffle.app.feature.audio.PlaybackSpeedControl
import com.riffle.app.feature.audio.SKIP_NUMBER_DOWN_FRACTION

/**
 * The mini-player speed control: a compact chip on the bar that opens the shared granular speed
 * sheet ([PlaybackSpeedControl]) floating **above** the bar. Read Aloud and the full-screen Audiobook
 * player share one sheet so they offer the same 0.05-step range; only the on-bar anchor differs.
 */
@Composable
private fun SpeedControl(
    speed: Float,
    contentColor: Color,
    onSpeedChange: (Float) -> Unit,
) {
    PlaybackSpeedControl(speed = speed, onSpeedChange = onSpeedChange, tagPrefix = "readaloud") { onClick ->
        // Compact chip-style label. One tap toggles the sheet; there are no inline steppers and no
        // press-and-hold gesture — all adjustment happens in the sheet.
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = contentColor.copy(alpha = 0.08f),
            contentColor = contentColor,
            modifier = Modifier.testTag("readaloud_speed"),
        ) {
            Text(
                PlaybackSpeed.label(speed),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                // Fixed width so the chip doesn't grow/shrink as the label changes (1× ↔ 0.75× ↔
                // 1.45×) and shove the rest of the bar sideways while scrubbing.
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .width(48.dp),
            )
        }
    }
}


@Composable
// The circular-arrow glyph is enlarged (from the 24dp default) so the overlaid "15"/"30" interval
// numbers fit inside the arc rather than spilling past its edges.
private fun SkipIcon(seconds: Int, forward: Boolean, tint: Color, iconSize: Dp = 32.dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Filled.Replay,
            contentDescription = null,
            tint = tint,
            modifier = if (forward) Modifier.size(iconSize).scale(scaleX = -1f, scaleY = 1f)
                       else Modifier.size(iconSize),
        )
        Text(
            text = "$seconds",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = tint,
            // The Replay glyph's arrowhead sits at the top, so the loop's open centre is below the
            // icon's geometric centre. Nudge the interval number down (proportional to the glyph) so
            // it reads as centred inside the loop. Mirror-safe: the offset is vertical only.
            modifier = Modifier.offset(y = iconSize * SKIP_NUMBER_DOWN_FRACTION),
        )
    }
}

/**
 * Bottom mini-player bar. Sits above the chapter rail in the screen layout.
 */
@Composable
fun ReadaloudMiniPlayer(
    isPlaying: Boolean,
    speed: Float,
    skipIntervalSeconds: Int,
    rewindIntervalSeconds: Int,
    offlineMessage: Boolean,
    downloadProgress: Float?,
    canPreviousChapter: Boolean,
    canNextChapter: Boolean,
    containerColor: Color,
    contentColor: Color,
    onPlayPause: () -> Unit,
    onSpeedChange: (Float) -> Unit,
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
                SpeedControl(speed = speed, contentColor = contentColor, onSpeedChange = onSpeedChange)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRewind, modifier = Modifier.testTag("readaloud_rewind")) {
                    SkipIcon(seconds = rewindIntervalSeconds, forward = false, tint = contentColor)
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
                    SkipIcon(seconds = skipIntervalSeconds, forward = true, tint = contentColor)
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
