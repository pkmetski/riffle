package com.riffle.feature.settings.ui.panels
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import com.riffle.feature.player.PlaybackSpeed

@Composable
fun ListeningPreferencesPanel(
    defaultSpeed: Float,
    onDefaultSpeedChange: (Float) -> Unit,
    skipIntervalSeconds: Int,
    onSkipIntervalSecondsChange: (Int) -> Unit,
    rewindIntervalSeconds: Int,
    onRewindIntervalSecondsChange: (Int) -> Unit,
    rewindOnResumeSeconds: Int,
    onRewindOnResumeSecondsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
// TODO: migrate to Res.string.ui_listening_settings
) = DetailScaffold(stringResource(Res.string.ui_listening_settings), onDismiss) {
    // TODO: migrate to Res.string.ui_default_speed
    Text(stringResource(Res.string.ui_default_speed), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        PlaybackSpeed.PRESETS.forEachIndexed { index, speed ->
            SegmentedButton(
                selected = speed == defaultSpeed,
                onClick = { onDefaultSpeedChange(speed) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = PlaybackSpeed.PRESETS.size),
            ) {
                Text(PlaybackSpeed.label(speed))
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // TODO: migrate to Res.string.ui_forward_skip
    Text(stringResource(Res.string.ui_forward_skip), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    val skipOptions = listOf(10, 15, 30, 45, 60)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        skipOptions.forEachIndexed { index, seconds ->
            SegmentedButton(
                selected = seconds == skipIntervalSeconds,
                onClick = { onSkipIntervalSecondsChange(seconds) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = skipOptions.size),
            ) {
                // TODO: migrate to Res.string.ui_seconds_short
                Text(stringResource(Res.string.ui_seconds_short, seconds))
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // TODO: migrate to Res.string.ui_backward_rewind
    Text(stringResource(Res.string.ui_backward_rewind), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    val rewindOptions = listOf(5, 10, 15, 30)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        rewindOptions.forEachIndexed { index, seconds ->
            SegmentedButton(
                selected = seconds == rewindIntervalSeconds,
                onClick = { onRewindIntervalSecondsChange(seconds) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = rewindOptions.size),
            ) {
                // TODO: migrate to Res.string.ui_seconds_short
                Text(stringResource(Res.string.ui_seconds_short, seconds))
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // TODO: migrate to Res.string.ui_rewind_on_resume
    Text(stringResource(Res.string.ui_rewind_on_resume), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    val resumeRewindOptions = listOf(0, 5, 10, 30)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        resumeRewindOptions.forEachIndexed { index, seconds ->
            SegmentedButton(
                selected = seconds == rewindOnResumeSeconds,
                onClick = { onRewindOnResumeSecondsChange(seconds) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = resumeRewindOptions.size),
            ) {
                Text(
                    if (seconds == 0) {
                        // TODO: migrate to Res.string.ui_off
                        stringResource(Res.string.ui_off)
                    } else {
                        // TODO: migrate to Res.string.ui_seconds_short
                        stringResource(Res.string.ui_seconds_short, seconds)
                    },
                )
            }
        }
    }
}
