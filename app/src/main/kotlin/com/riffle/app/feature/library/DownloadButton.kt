package com.riffle.app.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DownloadButton(
    state: DownloadState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = 40.dp
    when (state) {
        DownloadState.NotDownloaded -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Download",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        is DownloadState.InProgress -> {
            Box(
                modifier = modifier.size(size),
                contentAlignment = Alignment.Center,
            ) {
                val percent = state.percent
                if (percent == null) {
                    // No advertised size — fall back to the indeterminate spinner.
                    CircularProgressIndicator(modifier = Modifier.size(size))
                } else {
                    // Animate between reported steps so the ring sweeps smoothly rather than jumping.
                    val animated by animateFloatAsState(
                        targetValue = percent / 100f,
                        label = "downloadProgress",
                    )
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.size(size),
                    )
                    Text(
                        text = "$percent%",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        DownloadState.Downloaded -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Remove download",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
