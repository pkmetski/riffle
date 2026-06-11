@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.riffle.app.feature.reader.readaloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.riffle.app.R

/** Formats the speed as the spec wants it: 1×, 1.25×, 1.4×, 0.75×, … (rounded to 0.05, zeros trimmed). */
private fun speedLabel(speed: Float): String {
    // Round to 2 decimals first so 0.05-step floats (e.g. 1.35000002) print cleanly.
    val rounded = Math.round(speed * 100.0) / 100.0
    val s = if (rounded % 1.0 == 0.0) rounded.toInt().toString()
    else rounded.toString().trimEnd('0').trimEnd('.')
    return "${s}×"
}

// Quick-jump presets surfaced as chips in the speed sheet (the familiar tap-cycle set), so common
// speeds are one tap while the slider still reaches any 0.05-step value in between.
private val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

// Detent labels printed under the slider track, spaced to read as a rough scale (not pixel-aligned
// to the track — purely a legend, matching the prototype).
private val SPEED_DETENTS = listOf("0.5×", "1×", "2×", "3×")

/**
 * The mini-player speed control: a single compact label on the bar. A **tap** (not a hold) opens a
 * [SpeedSliderSheet] floating **above** the bar — a big live readout over a 0.05-step slider with a
 * detent legend and quick-preset chips. The sheet is an overlay (a [Popup]), so the page text
 * underneath never reflows, consistent with the floating player.
 */
@Composable
private fun SpeedControl(
    speed: Float,
    contentColor: Color,
    onSpeedChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    var sheetOpen by remember { mutableStateOf(false) }

    Box {
        // Compact chip-style label. One tap toggles the sheet; there are no inline steppers and no
        // press-and-hold gesture — all adjustment happens in the sheet.
        Surface(
            onClick = { sheetOpen = !sheetOpen },
            shape = RoundedCornerShape(10.dp),
            color = contentColor.copy(alpha = 0.08f),
            contentColor = contentColor,
            modifier = Modifier.testTag("readaloud_speed"),
        ) {
            Text(
                speedLabel(speed),
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

        if (sheetOpen) {
            val gapPx = with(density) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gapPx) { AboveAnchorPositionProvider(gapPx) },
                onDismissRequest = { sheetOpen = false },
            ) {
                SpeedSliderSheet(speed = speed, onSpeedChange = onSpeedChange)
            }
        }
    }
}

/**
 * The card that floats above the bar while adjusting speed: a big readout, a 0.05-step slider with a
 * detent legend, and quick-preset chips (prototype option B).
 */
@Composable
private fun SpeedSliderSheet(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    // One discrete stop per SPEED_STEP between the endpoints (Slider snaps onValueChange to them).
    val stepCount = Math.round((ReadaloudController.SPEED_MAX - ReadaloudController.SPEED_MIN) /
        ReadaloudController.SPEED_STEP) - 1
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .width(320.dp)
            .testTag("readaloud_speed_slider_card"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                speedLabel(speed),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = speed.coerceIn(ReadaloudController.SPEED_MIN, ReadaloudController.SPEED_MAX),
                onValueChange = { onSpeedChange(ReadaloudController.snapSpeed(it)) },
                valueRange = ReadaloudController.SPEED_MIN..ReadaloudController.SPEED_MAX,
                steps = stepCount,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("readaloud_speed_slider"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SPEED_DETENTS.forEach { detent ->
                    Text(
                        detent,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SPEED_PRESETS.forEach { preset ->
                    SpeedPresetChip(
                        label = speedLabel(preset),
                        selected = Math.abs(preset - speed) < 0.001f,
                        onClick = { onSpeedChange(preset) },
                    )
                }
            }
        }
    }
}

/** A compact pill preset (lighter than a Material [FilterChip] so all five fit one row). */
@Composable
private fun SpeedPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("readaloud_speed_preset_$label"),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Positions the speed sheet centred horizontally in the WINDOW (not over its anchor — the speed label
 * sits at the far left of the bar, so anchoring there would shove the sheet against the screen edge),
 * floating just above the anchor row.
 */
private class AboveAnchorPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = ((windowSize.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = anchorBounds.top - popupContentSize.height - gapPx
        return IntOffset(x, y)
    }
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
