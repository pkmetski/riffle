package com.riffle.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R

/**
 * Downloads/removes the synced readaloud bundle for a matched ABS item. Mirrors [DownloadButton]
 * (tap to download, tap again to remove) but its glyph is a download arrow with the readaloud
 * book badge, so it reads as "download readaloud" next to the plain ebook download.
 */
@Composable
fun ReadaloudDownloadButton(
    state: DownloadState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val size = 40.dp
    when (state) {
        DownloadState.InProgress -> {
            Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(size))
            }
        }
        DownloadState.NotDownloaded -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(enabled = enabled, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Download readaloud",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                ReadaloudBadge(tint = MaterialTheme.colorScheme.outline)
            }
        }
        DownloadState.Downloaded -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Remove readaloud download",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                ReadaloudBadge(tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun BoxScope.ReadaloudBadge(tint: Color) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_readaloud),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
    }
}
