package com.riffle.feature.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow

private const val KO_FI_URL = "https://ko-fi.com/pkmetski"

const val KO_FI_THRESHOLD = 0.98f

/**
 * Collects [progressionFlow] and calls [onTrigger] exactly once when the user reaches
 * ≥[KO_FI_THRESHOLD] after having been seen below it — preventing the saved bookmark
 * (often already ≥98%) from immediately triggering the nudge on book-open.
 */
suspend fun collectKoFiProgressionNudge(
    progressionFlow: Flow<Float?>,
    onTrigger: () -> Unit,
) {
    var seenBelowThreshold = false
    var shownThisSession = false
    progressionFlow.collect { prog ->
        if (prog == null) return@collect
        if (!seenBelowThreshold && prog < KO_FI_THRESHOLD) seenBelowThreshold = true
        if (prog >= KO_FI_THRESHOLD && seenBelowThreshold && !shownThisSession) {
            onTrigger()
            shownThisSession = true
        }
    }
}

@Composable
fun KoFiDrawerButton(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    OutlinedButton(
        onClick = { uriHandler.openUri(KO_FI_URL) },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("☕ Support on Ko-fi")
    }
}

@Composable
fun KoFiNudgeCard(
    visible: Boolean,
    onNotNow: () -> Unit,
    onSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "☕", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enjoying Riffle?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "Support development on Ko-fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = {
                            uriHandler.openUri(KO_FI_URL)
                            onSupport()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text("Support")
                    }
                    TextButton(onClick = onNotNow) {
                        Text(
                            text = "Not now",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}
