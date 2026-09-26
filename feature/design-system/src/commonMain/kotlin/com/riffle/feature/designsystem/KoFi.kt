package com.riffle.feature.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ui_kofi_nudge_body
import com.riffle.feature.designsystem.generated.resources.ui_kofi_nudge_not_now
import com.riffle.feature.designsystem.generated.resources.ui_kofi_nudge_support
import com.riffle.feature.designsystem.generated.resources.ui_kofi_nudge_title
import com.riffle.feature.designsystem.generated.resources.ui_support_support_on_ko_fi
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource

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
        Text(stringResource(Res.string.ui_support_support_on_ko_fi))
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
                        text = stringResource(Res.string.ui_kofi_nudge_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource(Res.string.ui_kofi_nudge_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    modifier = Modifier.width(IntrinsicSize.Max),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = {
                            uriHandler.openUri(KO_FI_URL)
                            onSupport()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.ui_kofi_nudge_support))
                    }
                    OutlinedButton(
                        onClick = onNotNow,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text(
                            text = stringResource(Res.string.ui_kofi_nudge_not_now),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
