package com.riffle.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.player.SleepTimerMode

internal val SLEEP_PRESETS_MINUTES = listOf(5, 15, 30, 45, 60, 90)

private const val MS_PER_MINUTE = 60 * 1_000L

/** The countdown a preset button arms. Pinned so the six buttons cannot drift from each other. */
internal fun sleepPresetMode(minutes: Int): SleepTimerMode.CountDown =
    SleepTimerMode.CountDown(minutes * MS_PER_MINUTE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerControl(
    timerMode: SleepTimerMode,
    onSetTimer: (SleepTimerMode) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    labels: PlayerChromeLabels,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag(TestTags.SLEEP_TIMER_SHEET),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = labels.sleepTimerSheetTitle,
                style = MaterialTheme.typography.titleMedium,
            )

            // Active timer banner — shown only when a timer is already running.
            if (timerMode !is SleepTimerMode.None) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sleepBannerLabel(timerMode, labels),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(
                        onClick = {
                            onCancel()
                            onDismiss()
                        },
                    ) {
                        Icon(PlayerGlyphs.Close, contentDescription = labels.cancelTimer)
                    }
                }
            }

            // End of chapter — full-width.
            FilledTonalButton(
                onClick = {
                    onSetTimer(SleepTimerMode.EndOfChapter)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SLEEP_END_OF_CHAPTER),
                shape = RoundedCornerShape(50),
            ) {
                Icon(PlayerGlyphs.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(labels.endOfChapter)
            }

            // 3-column preset grid: row 1 = 5/15/30, row 2 = 45/60/90.
            val rowOne = SLEEP_PRESETS_MINUTES.take(3)
            val rowTwo = SLEEP_PRESETS_MINUTES.drop(3)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOne.forEach { minutes ->
                    PresetButton(
                        minutes = minutes,
                        onSetTimer = onSetTimer,
                        onDismiss = onDismiss,
                        labels = labels,
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
                        labels = labels,
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
    labels: PlayerChromeLabels,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = {
            onSetTimer(sleepPresetMode(minutes))
            onDismiss()
        },
        modifier = modifier.testTag(TestTags.sleepPreset(minutes)),
        shape = RoundedCornerShape(50),
    ) {
        Text(sleepPresetLabel(minutes, labels), style = MaterialTheme.typography.labelLarge)
    }
}
