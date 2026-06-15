package com.riffle.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.audio.PlaybackSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningPreferencesPanel(
    defaultSpeed: Float,
    onDefaultSpeedChange: (Float) -> Unit,
    skipIntervalSeconds: Int,
    onSkipIntervalSecondsChange: (Int) -> Unit,
    rewindOnResumeSeconds: Int,
    onRewindOnResumeSecondsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        tonalElevation = 1.dp,
    ) {
        Column {
            TopAppBar(
                title = { Text("Listening settings") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
            ) {
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

                Text("Skip interval", style = MaterialTheme.typography.labelMedium)
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

                Text("Rewind on resume", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                val rewindOptions = listOf(0, 5, 10, 30)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    rewindOptions.forEachIndexed { index, seconds ->
                        SegmentedButton(
                            selected = seconds == rewindOnResumeSeconds,
                            onClick = { onRewindOnResumeSecondsChange(seconds) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = rewindOptions.size),
                        ) {
                            Text(if (seconds == 0) "Off" else "${seconds}s")
                        }
                    }
                }
            }
        }
    }
}
