package com.riffle.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.player.PlaybackSpeed
import com.riffle.feature.player.skipBackwardLabel
import com.riffle.feature.player.skipForwardLabel
import com.riffle.feature.player.ui.generated.resources.Res
import com.riffle.feature.player.ui.generated.resources.ui_cancel
import com.riffle.feature.player.ui.generated.resources.ui_close_readaloud
import com.riffle.feature.player.ui.generated.resources.ui_download
import com.riffle.feature.player.ui.generated.resources.ui_download_readaloud_audio_size
import com.riffle.feature.player.ui.generated.resources.ui_downloading_percent
import com.riffle.feature.player.ui.generated.resources.ui_next_chapter
import com.riffle.feature.player.ui.generated.resources.ui_playback_speed
import com.riffle.feature.player.ui.generated.resources.ui_previous_chapter
import com.riffle.feature.player.ui.generated.resources.ui_switch_to_audiobook
import com.riffle.feature.player.ui.generated.resources.ui_wi_fi_only
import org.jetbrains.compose.resources.stringResource

/**
 * Shared readaloud mini-player bar. Rendered by both `:app` (Android) and `:shared` (iOS).
 *
 * The bar shows playback controls (speed chip, rewind, prev chapter, play/pause, next chapter,
 * forward, close) when playing, or a [barMessage] / [downloadProgress] label when the audio isn't
 * ready. Colors come from the reader-theme palette so the bar reads as one continuous strip.
 */
@Composable
fun ReadaloudMiniPlayer(
    isPlaying: Boolean,
    speed: Float,
    skipIntervalSeconds: Int,
    rewindIntervalSeconds: Int,
    barMessage: String?,
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
    /** When non-null, a "Switch to audiobook" button is shown and this callback fires on tap. */
    onSwitchToAudiobook: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.READALOUD_MINI_PLAYER),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (barMessage != null) {
                Text(
                    text = barMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag(TestTags.READALOUD_OFFLINE_MESSAGE),
                )
            } else if (downloadProgress != null) {
                Text(
                    text = stringResource(Res.string.ui_downloading_percent, (downloadProgress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag(TestTags.READALOUD_DOWNLOADING),
                )
            } else {
                ReadaloudSpeedChip(speed = speed, contentColor = contentColor, onSpeedChange = onSpeedChange)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRewind, modifier = Modifier.testTag(TestTags.READALOUD_REWIND)) {
                    ReadaloudSkipIcon(seconds = rewindIntervalSeconds, forward = false, tint = contentColor)
                }
                IconButton(
                    onClick = onPreviousChapter,
                    enabled = canPreviousChapter,
                    modifier = Modifier.testTag(TestTags.READALOUD_PREV_CHAPTER),
                ) {
                    Icon(PlayerGlyphs.SkipPrevious, contentDescription = stringResource(Res.string.ui_previous_chapter))
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.testTag(TestTags.READALOUD_PLAY_PAUSE)) {
                    Icon(
                        imageVector = if (isPlaying) PlayerGlyphs.Pause else PlayerGlyphs.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(
                    onClick = onNextChapter,
                    enabled = canNextChapter,
                    modifier = Modifier.testTag(TestTags.READALOUD_NEXT_CHAPTER),
                ) {
                    Icon(PlayerGlyphs.SkipNext, contentDescription = stringResource(Res.string.ui_next_chapter))
                }
                IconButton(onClick = onForward, modifier = Modifier.testTag(TestTags.READALOUD_FORWARD)) {
                    ReadaloudSkipIcon(seconds = skipIntervalSeconds, forward = true, tint = contentColor)
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            if (onSwitchToAudiobook != null) {
                IconButton(
                    onClick = onSwitchToAudiobook,
                    modifier = Modifier.testTag(TestTags.READALOUD_SWITCH_TO_AUDIOBOOK),
                ) {
                    Icon(RiffleIcons.Headphones, contentDescription = stringResource(Res.string.ui_switch_to_audiobook))
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag(TestTags.READALOUD_CLOSE)) {
                Icon(PlayerGlyphs.Close, contentDescription = stringResource(Res.string.ui_close_readaloud))
            }
        }
    }
}

/**
 * Compact speed chip that opens the shared [PlaybackSpeedControl] sheet on tap.
 *
 * Uses a translucent fill on the reader's content color so it sits harmoniously over the
 * reader-theme surface without clashing with the page background.
 */
@Composable
private fun ReadaloudSpeedChip(
    speed: Float,
    contentColor: Color,
    onSpeedChange: (Float) -> Unit,
) {
    PlaybackSpeedControl(
        speed = speed,
        onSpeedChange = onSpeedChange,
        tagPrefix = "readaloud",
        title = stringResource(Res.string.ui_playback_speed),
    ) { onClick ->
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = contentColor.copy(alpha = 0.08f),
            contentColor = contentColor,
            modifier = Modifier.testTag(TestTags.READALOUD_SPEED),
        ) {
            Text(
                PlaybackSpeed.label(speed),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .width(48.dp),
            )
        }
    }
}

/**
 * The ⟲/⟳ glyph with the configured interval drawn inside the loop.
 *
 * The number is offset downward by [SKIP_NUMBER_DOWN_FRACTION] of the icon size so it reads as
 * centred inside the loop's open arc — the arrowhead at the top shifts the visual centre down.
 */
@Composable
private fun ReadaloudSkipIcon(seconds: Int, forward: Boolean, tint: Color, iconSize: Dp = 32.dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = PlayerGlyphs.Replay,
            contentDescription = if (forward) skipForwardLabel(seconds) else skipBackwardLabel(seconds),
            tint = tint,
            modifier = if (forward) {
                Modifier.size(iconSize).scale(scaleX = -1f, scaleY = 1f)
            } else {
                Modifier.size(iconSize)
            },
        )
        Text(
            text = "$seconds",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.offset(y = iconSize * SKIP_NUMBER_DOWN_FRACTION),
        )
    }
}

/**
 * Download confirmation dialog shown when the readaloud bundle is not on disk.
 *
 * [sizeLabel] is a pre-formatted size string (e.g. "245 MB") produced by the caller. The dialog
 * offers a Wi-Fi-only toggle, matching the Android readaloud download experience.
 */
@Composable
fun ReadaloudDownloadDialog(
    sizeLabel: String,
    onConfirm: (wifiOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var wifiOnly by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.READALOUD_DOWNLOAD_DIALOG),
        title = {
            Text(stringResource(Res.string.ui_download_readaloud_audio_size, sizeLabel))
        },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.ui_wi_fi_only),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it },
                    modifier = Modifier.testTag(TestTags.READALOUD_WIFI_ONLY),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(wifiOnly) }) {
                Text(stringResource(Res.string.ui_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.ui_cancel))
            }
        },
    )
}
