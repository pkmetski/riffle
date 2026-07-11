package com.riffle.app.feature.settings.panels

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
import com.riffle.app.feature.audio.PlaybackSpeed

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
) = DetailScaffold("Listening settings", onDismiss) {
    Text("Default speed", style = MaterialTheme.typography.labelMedium)
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

    Text("Forward skip", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    val skipOptions = listOf(10, 15, 30, 45, 60)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        skipOptions.forEachIndexed { index, seconds ->
            SegmentedButton(
                selected = seconds == skipIntervalSeconds,
                onClick = { onSkipIntervalSecondsChange(seconds) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = skipOptions.size),
            ) {
                Text("${seconds}s")
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    Text("Backward rewind", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    val rewindOptions = listOf(5, 10, 15, 30)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        rewindOptions.forEachIndexed { index, seconds ->
            SegmentedButton(
                selected = seconds == rewindIntervalSeconds,
                onClick = { onRewindIntervalSecondsChange(seconds) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = rewindOptions.size),
            ) {
                Text("${seconds}s")
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    Text("Rewind on resume", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    val resumeRewindOptions = listOf(0, 5, 10, 30)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        resumeRewindOptions.forEachIndexed { index, seconds ->
            SegmentedButton(
                selected = seconds == rewindOnResumeSeconds,
                onClick = { onRewindOnResumeSecondsChange(seconds) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = resumeRewindOptions.size),
            ) {
                Text(if (seconds == 0) "Off" else "${seconds}s")
            }
        }
    }
}
