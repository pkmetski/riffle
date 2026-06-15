package com.riffle.app.feature.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.audiobook.SleepTimerMode
import com.riffle.app.feature.audiobook.formatCountdown

private val PRESETS_MINUTES = listOf(5, 15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerControl(
    timerMode: SleepTimerMode,
    onSetTimer: (SleepTimerMode) -> Unit,
    onCancel: () -> Unit,
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
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.titleMedium,
            )

            // Active timer banner — shown only when a timer is already running.
            if (timerMode !is SleepTimerMode.None) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bannerText = when (timerMode) {
                        is SleepTimerMode.CountDown ->
                            "Sleeping in ${timerMode.formatCountdown()}"
                        is SleepTimerMode.EndOfChapter ->
                            "Sleeping at end of chapter"
                        is SleepTimerMode.None -> ""
                    }
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = { onCancel(); onDismiss() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel timer")
                    }
                }
            }

            // End of chapter — full-width.
            FilledTonalButton(
                onClick = { onSetTimer(SleepTimerMode.EndOfChapter); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("End of chapter")
            }

            // 3-column preset grid: row 1 = 5/15/30, row 2 = 45/60/90.
            val rowOne = PRESETS_MINUTES.take(3)
            val rowTwo = PRESETS_MINUTES.drop(3)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOne.forEach { minutes ->
                    PresetButton(
                        minutes = minutes,
                        onSetTimer = onSetTimer,
                        onDismiss = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTwo.forEach { minutes ->
                    PresetButton(
                        minutes = minutes,
                        onSetTimer = onSetTimer,
                        onDismiss = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetButton(
    minutes: Int,
    onSetTimer: (SleepTimerMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = {
            onSetTimer(SleepTimerMode.CountDown(minutes * 60 * 1_000L))
            onDismiss()
        },
        modifier = modifier,
        shape = RoundedCornerShape(50),
    ) {
        Text("$minutes min", style = MaterialTheme.typography.labelLarge)
    }
}
