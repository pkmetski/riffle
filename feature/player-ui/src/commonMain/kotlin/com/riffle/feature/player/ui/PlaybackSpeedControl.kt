package com.riffle.feature.player.ui

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
import com.riffle.feature.player.PlaybackSpeed
import kotlin.math.abs

/** The presets the sheet offers, on top of the ± nudge buttons. */
internal val SPEED_SHEET_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** Tolerance for "this preset is the current speed" — [PlaybackSpeed.STEP] is 0.05. */
private const val SPEED_MATCH_EPSILON = 0.001f

/** Whether [preset] is the speed currently in effect, free of float-comparison noise. */
internal fun isSelectedSpeedPreset(preset: Float, speed: Float): Boolean =
    abs(preset - speed) < SPEED_MATCH_EPSILON

/**
 * The speed control: a caller-supplied [anchor] that, when tapped, opens a [SpeedSheet]
 * (ModalBottomSheet) with +/− nudge buttons and preset options, matching the sleep timer's
 * presentation pattern.
 *
 * [tagPrefix] namespaces the test tags so each player's instrumentation stays distinct. [title] is
 * the sheet's heading, supplied by the host so Android keeps serving it from `res/values*` — it is
 * the only string this control draws, so it takes the string rather than a whole
 * [PlayerChromeLabels] (the in-reader Readaloud mini-player renders this sheet too and has no
 * player-chrome catalogue of its own).
 */
@Composable
fun PlaybackSpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    tagPrefix: String,
    title: String,
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
            title = title,
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
    title: String,
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
            Text(title, style = MaterialTheme.typography.titleMedium)

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

            val row1 = SPEED_SHEET_PRESETS.take(3)
            val row2 = SPEED_SHEET_PRESETS.drop(3)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row1.forEach { preset ->
                    SpeedPresetButton(
                        label = PlaybackSpeed.label(preset),
                        selected = isSelectedSpeedPreset(preset, speed),
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
                        selected = isSelectedSpeedPreset(preset, speed),
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
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonBorder(enabled = true)
        },
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
