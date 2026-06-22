package com.riffle.app.feature.audio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The granular playback-speed range shared by the Read Aloud mini-player and the full-screen
 * Audiobook player: any [STEP] multiple in [[MIN], [MAX]] (so 1.4× is reachable). Both players
 * talk to the same ABS audio file, so keeping one range/snap rule here means they behave identically.
 */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 3.0f
    const val STEP = 0.05f

    /** Quick-jump presets surfaced as options in Settings and the sheet. */
    val PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Clamps to [[MIN], [MAX]] and snaps to the nearest [STEP], free of float drift. */
    fun snap(raw: Float): Float = (Math.round(raw / STEP) * STEP).coerceIn(MIN, MAX)

    /** Formats the speed as 1×, 1.25×, 1.4×, 0.75× … (rounded to 0.05, trailing zeros trimmed). */
    fun label(speed: Float): String {
        val rounded = Math.round(speed * 100.0) / 100.0
        val s = if (rounded % 1.0 == 0.0) rounded.toInt().toString()
        else rounded.toString().trimEnd('0').trimEnd('.')
        return "${s}×"
    }
}

private val SHEET_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/**
 * The speed control: a caller-supplied [anchor] that, when tapped, opens a [SpeedSheet]
 * (ModalBottomSheet) with +/− nudge buttons and preset options, matching the sleep timer's
 * presentation pattern.
 *
 * [tagPrefix] namespaces the test tags so each player's instrumentation stays distinct.
 */
@Composable
fun PlaybackSpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    anchor: @Composable (onClick: () -> Unit) -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        anchor { sheetOpen = true }
    }

    if (sheetOpen) {
        SpeedSheet(
            speed = speed,
            onSpeedChange = onSpeedChange,
            tagPrefix = tagPrefix,
            onDismiss = { sheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    tagPrefix: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Playback Speed", style = MaterialTheme.typography.titleMedium)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    onClick = { onSpeedChange(PlaybackSpeed.snap(speed - PlaybackSpeed.STEP)) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(48.dp).testTag("${tagPrefix}_speed_minus"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("−", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
                    }
                }
                Text(
                    PlaybackSpeed.label(speed),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("${tagPrefix}_speed_display"),
                )
                Surface(
                    onClick = { onSpeedChange(PlaybackSpeed.snap(speed + PlaybackSpeed.STEP)) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(48.dp).testTag("${tagPrefix}_speed_plus"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("+", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
                    }
                }
            }

            val row1 = SHEET_PRESETS.take(3)
            val row2 = SHEET_PRESETS.drop(3)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row1.forEach { preset ->
                    SpeedPresetButton(
                        label = PlaybackSpeed.label(preset),
                        selected = Math.abs(preset - speed) < 0.001f,
                        onClick = { onSpeedChange(preset) },
                        tagPrefix = tagPrefix,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row2.forEach { preset ->
                    SpeedPresetButton(
                        label = PlaybackSpeed.label(preset),
                        selected = Math.abs(preset - speed) < 0.001f,
                        onClick = { onSpeedChange(preset) },
                        tagPrefix = tagPrefix,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedPresetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.testTag("${tagPrefix}_speed_preset_$label"),
        shape = RoundedCornerShape(50),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                 else ButtonDefaults.outlinedButtonBorder(enabled = true),
        colors = if (selected) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                 else ButtonDefaults.outlinedButtonColors(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
