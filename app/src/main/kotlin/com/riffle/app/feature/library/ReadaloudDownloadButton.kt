package com.riffle.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
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

private val CircleSize = 40.dp
private val BadgeSize = 22.dp
// Outer box is larger than the circle so the corner badge overflows the circle edge (as designed)
// instead of being clipped by the circle's CircleShape.
private val OuterSize = 46.dp

/**
 * Downloads/removes the synced readaloud bundle for a matched ABS item. Mirrors [DownloadButton]
 * (tap to download, tap again to remove), but the 40dp circle carries a download arrow with the
 * readaloud glyph as a badge on the bottom-right corner, so it reads as "download readaloud" beside
 * the plain ebook download. The badge lives in a slightly larger, unclipped outer box so the
 * circle's clip never cuts it.
 */
@Composable
fun ReadaloudDownloadButton(
    state: DownloadState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is DownloadState.InProgress -> {
            Box(modifier = modifier.size(OuterSize), contentAlignment = Alignment.Center) {
                DownloadProgressIndicator(
                    percent = state.percent,
                    size = CircleSize,
                    label = "readaloudDownloadProgress",
                )
            }
        }
        DownloadState.NotDownloaded -> {
            BadgedDownloadCircle(
                modifier = modifier,
                contentDescription = "Download readaloud",
                tint = MaterialTheme.colorScheme.outline,
                circleModifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                enabled = enabled,
                onClick = onDownload,
            )
        }
        DownloadState.Downloaded -> {
            BadgedDownloadCircle(
                modifier = modifier,
                contentDescription = "Remove readaloud download",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                circleModifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer),
                enabled = true,
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun BadgedDownloadCircle(
    modifier: Modifier,
    contentDescription: String,
    tint: Color,
    circleModifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.size(OuterSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(CircleSize)
                .clip(CircleShape)
                .then(circleModifier)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        // Badge overlays the circle's bottom-right corner; it is a sibling of the clipped circle
        // (not a child) so the circle's clip does not cut it.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(BadgeSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_readaloud),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
