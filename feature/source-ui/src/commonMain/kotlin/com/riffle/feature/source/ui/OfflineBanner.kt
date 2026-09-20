package com.riffle.feature.source.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_offline_showing_cached_data
import org.jetbrains.compose.resources.stringResource

/**
 * The "you are offline" strip every library-ish grid puts above its content. Shared so the
 * Android library screens and the iOS unbounded-browse screen cannot drift apart on the copy.
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.ui_offline_showing_cached_data),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(vertical = 6.dp),
    )
}
