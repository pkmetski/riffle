package com.riffle.app.feature.audio

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider

/**
 * The granular playback-speed range shared by the Read Aloud mini-player and the full-screen
 * Audiobook player: any [STEP] multiple in [[MIN], [MAX]] (so 1.4× is reachable), with the same
 * quick-preset chips and detent legend. Both players talk to the same ABS audio file, so keeping one
 * range/snap rule here means they behave identically.
 */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 3.0f
    const val STEP = 0.05f

    /** Quick-jump presets surfaced as chips in the sheet (the familiar tap-cycle set). */
    val PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Detent labels printed under the slider track — a rough scale legend, not pixel-aligned. */
    val DETENTS = listOf("0.5×", "1×", "2×", "3×")

    /** Clamps to [[MIN], [MAX]] and snaps to the nearest [STEP], free of float drift. */
    fun snap(raw: Float): Float = (Math.round(raw / STEP) * STEP).coerceIn(MIN, MAX)

    /** Formats the speed as 1×, 1.25×, 1.4×, 0.75× … (rounded to 0.05, trailing zeros trimmed). */
    fun label(speed: Float): String {
        // Round to 2 decimals first so 0.05-step floats (e.g. 1.35000002) print cleanly.
        val rounded = Math.round(speed * 100.0) / 100.0
        val s = if (rounded % 1.0 == 0.0) rounded.toInt().toString()
        else rounded.toString().trimEnd('0').trimEnd('.')
        return "${s}×"
    }
}

/**
 * The granular speed control: a caller-supplied [anchor] that, when tapped, opens a [SpeedSliderSheet]
 * floating **above** it in an overlay [Popup] (so nothing underneath reflows). The two players style
 * their own anchor (a compact chip vs. a labelled pill) but share the sheet and the popup mechanics.
 *
 * [tagPrefix] namespaces the test tags (`<prefix>_speed_slider`, `<prefix>_speed_preset_<label>`, …)
 * so each player's instrumentation stays distinct.
 */
@Composable
fun PlaybackSpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    anchor: @Composable (onClick: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    var sheetOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        anchor { sheetOpen = !sheetOpen }

        if (sheetOpen) {
            val gapPx = with(density) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gapPx) { AboveAnchorPositionProvider(gapPx) },
                onDismissRequest = { sheetOpen = false },
            ) {
                SpeedSliderSheet(speed = speed, onSpeedChange = onSpeedChange, tagPrefix = tagPrefix)
            }
        }
    }
}

/**
 * The card that floats above the anchor while adjusting speed: a big readout, a 0.05-step slider with
 * a detent legend, and quick-preset chips.
 */
@Composable
private fun SpeedSliderSheet(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    tagPrefix: String,
) {
    // One discrete stop per STEP between the endpoints (Slider snaps onValueChange to them).
    val stepCount = Math.round((PlaybackSpeed.MAX - PlaybackSpeed.MIN) / PlaybackSpeed.STEP) - 1
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .width(320.dp)
            .testTag("${tagPrefix}_speed_slider_card"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                PlaybackSpeed.label(speed),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = speed.coerceIn(PlaybackSpeed.MIN, PlaybackSpeed.MAX),
                onValueChange = { onSpeedChange(PlaybackSpeed.snap(it)) },
                valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
                steps = stepCount,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_speed_slider"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlaybackSpeed.DETENTS.forEach { detent ->
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
                PlaybackSpeed.PRESETS.forEach { preset ->
                    SpeedPresetChip(
                        label = PlaybackSpeed.label(preset),
                        selected = Math.abs(preset - speed) < 0.001f,
                        onClick = { onSpeedChange(preset) },
                        tagPrefix = tagPrefix,
                    )
                }
            }
        }
    }
}

/** A compact pill preset (lighter than a Material FilterChip so all five fit one row). */
@Composable
private fun SpeedPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tagPrefix: String,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("${tagPrefix}_speed_preset_$label"),
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
 * Positions the speed sheet centred horizontally in the WINDOW (not over its anchor — the anchor can
 * sit at the far edge of a bar, so anchoring there would shove the sheet against the screen edge),
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
        // Float above the anchor, but never let the top clip off-screen on a short viewport.
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
