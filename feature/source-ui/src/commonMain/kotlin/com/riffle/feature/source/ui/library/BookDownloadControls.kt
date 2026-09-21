package com.riffle.feature.source.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.DetailCapabilities
import com.riffle.feature.library.DownloadState
import com.riffle.feature.library.bookDownloadAffordances
import com.riffle.feature.source.ui.MaterialGlyphs
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ic_readaloud
import com.riffle.feature.source.ui.generated.resources.ui_download
import com.riffle.feature.source.ui.generated.resources.ui_download_cached_item
import com.riffle.feature.source.ui.generated.resources.ui_download_readaloud
import com.riffle.feature.source.ui.generated.resources.ui_remove_download
import com.riffle.feature.source.ui.generated.resources.ui_remove_readaloud_download
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The per-format download controls for one library item: ebook, audiobook and readaloud bundle.
 *
 * Both hosts render this. It is deliberately stateless — every flow value and every action comes
 * in as a parameter — so a host can mount it inside whatever chrome it already has (Android's
 * action row wraps the individual buttons in offline tooltips; iOS's item detail drops this whole
 * row in) without the composable reaching for a ViewModel of its own.
 *
 * Which controls appear, and which are tappable, is [bookDownloadAffordances] — a shared
 * derivation rather than a rule written once per platform.
 */
@Composable
fun BookDownloadControls(
    item: LibraryItem,
    capabilities: DetailCapabilities,
    isOffline: Boolean,
    downloadState: DownloadState,
    audiobookDownloadState: DownloadState?,
    readaloudDownloadState: DownloadState?,
    onDownloadEbook: () -> Unit,
    onRemoveEbook: () -> Unit,
    onDownloadAudiobook: () -> Unit,
    onRemoveAudiobook: () -> Unit,
    onDownloadReadaloud: () -> Unit,
    onRemoveReadaloud: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val affordances = bookDownloadAffordances(
        item = item,
        capabilities = capabilities,
        isOffline = isOffline,
        audiobookDownloadState = audiobookDownloadState,
        readaloudDownloadState = readaloudDownloadState,
    )
    if (!affordances.any) return
    Row(
        modifier = modifier.testTag("BookDownloadControls"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (affordances.showEbook) {
            DownloadButton(
                state = downloadState,
                onDownload = onDownloadEbook,
                onRemove = onRemoveEbook,
                enabled = affordances.ebookEnabled,
                testTag = "BookDownloadControls.Ebook",
            )
        }
        if (affordances.showAudiobook && audiobookDownloadState != null) {
            DownloadButton(
                state = audiobookDownloadState,
                onDownload = onDownloadAudiobook,
                onRemove = onRemoveAudiobook,
                enabled = affordances.audiobookEnabled,
                testTag = "BookDownloadControls.Audiobook",
            )
        }
        if (affordances.showReadaloud && readaloudDownloadState != null) {
            ReadaloudDownloadButton(
                state = readaloudDownloadState,
                onDownload = onDownloadReadaloud,
                onRemove = onRemoveReadaloud,
                enabled = affordances.readaloudEnabled,
            )
        }
    }
}

private val ButtonSize = 40.dp

/**
 * Tap-to-download / tap-again-to-remove circle for one downloadable artifact.
 *
 * Lifted verbatim out of `:app` (there is no Android copy left) so iOS renders the identical
 * affordance: outlined circle when nothing is stored, a determinate ring with the live percent
 * while fetching, a `secondaryContainer` circle for a cached copy that can be promoted to a real
 * download, and a filled `primary` circle that removes.
 */
@Composable
fun DownloadButton(
    state: DownloadState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val tagged = if (testTag != null) modifier.testTag(testTag) else modifier
    when (state) {
        DownloadState.NotDownloaded -> {
            Box(
                modifier = tagged
                    .size(ButtonSize)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(enabled = enabled, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialGlyphs.ArrowDownward,
                    contentDescription = stringResource(Res.string.ui_download),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        is DownloadState.InProgress -> {
            DownloadProgressIndicator(
                percent = state.percent,
                size = ButtonSize,
                label = "downloadProgress",
                modifier = tagged,
            )
        }
        DownloadState.Cached -> {
            Box(
                modifier = tagged
                    .size(ButtonSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(enabled = enabled, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialGlyphs.ArrowDownward,
                    contentDescription = stringResource(Res.string.ui_download_cached_item),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DownloadState.Downloaded -> {
            Box(
                modifier = tagged
                    .size(ButtonSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialGlyphs.ArrowDownward,
                    contentDescription = stringResource(Res.string.ui_remove_download),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The in-progress affordance shared by [DownloadButton] and [ReadaloudDownloadButton]: a
 * determinate ring with the live percent centered inside, or an indeterminate spinner when
 * [percent] is null (the download advertised no size). [label] names the animation for tooling.
 *
 * Public because Android's Dictionary-packs screen draws the same ring for a pack download; it is
 * the one download-progress affordance in the app and must not be re-drawn per screen.
 */
@Composable
fun DownloadProgressIndicator(
    percent: Int?,
    size: Dp,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (percent == null) {
            CircularProgressIndicator(modifier = Modifier.size(size))
        } else {
            // animateFloatAsState remembers its Animatable across recompositions, so the ring
            // sweeps smoothly from the current value toward each reported step rather than
            // jumping.
            val animated by animateFloatAsState(targetValue = percent / 100f, label = label)
            CircularProgressIndicator(progress = { animated }, modifier = Modifier.size(size))
            Text(
                text = "$percent%",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private val ReadaloudCircleSize = 40.dp
private val ReadaloudBadgeSize = 22.dp

// Outer box is larger than the circle so the corner badge overflows the circle edge (as designed)
// instead of being clipped by the circle's CircleShape.
private val ReadaloudOuterSize = 46.dp

/**
 * Downloads/removes the synced readaloud bundle for a matched ABS item. Mirrors [DownloadButton]
 * (tap to download, tap again to remove), but the 40dp circle carries a download arrow with the
 * readaloud glyph as a badge on the bottom-right corner, so it reads as "download readaloud"
 * beside the plain ebook download. The badge lives in a slightly larger, unclipped outer box so
 * the circle's clip never cuts it.
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
            Box(
                modifier = modifier
                    .testTag("BookDownloadControls.Readaloud")
                    .size(ReadaloudOuterSize),
                contentAlignment = Alignment.Center,
            ) {
                DownloadProgressIndicator(
                    percent = state.percent,
                    size = ReadaloudCircleSize,
                    label = "readaloudDownloadProgress",
                )
            }
        }
        DownloadState.NotDownloaded,
        DownloadState.Cached,
        -> {
            BadgedDownloadCircle(
                modifier = modifier,
                contentDescription = stringResource(Res.string.ui_download_readaloud),
                tint = MaterialTheme.colorScheme.outline,
                badgeTint = MaterialTheme.colorScheme.outline,
                circleModifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                enabled = enabled,
                onClick = onDownload,
            )
        }
        DownloadState.Downloaded -> {
            BadgedDownloadCircle(
                modifier = modifier,
                contentDescription = stringResource(Res.string.ui_remove_readaloud_download),
                tint = MaterialTheme.colorScheme.onPrimary,
                // The badge sits on a surface-colored chip, so it keeps a surface-readable tint
                // rather than the circle's onPrimary content color.
                badgeTint = MaterialTheme.colorScheme.onSurfaceVariant,
                circleModifier = Modifier.background(MaterialTheme.colorScheme.primary),
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
    badgeTint: Color,
    circleModifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .testTag("BookDownloadControls.Readaloud")
            .size(ReadaloudOuterSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ReadaloudCircleSize)
                .clip(CircleShape)
                .then(circleModifier)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MaterialGlyphs.ArrowDownward,
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
                .size(ReadaloudBadgeSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_readaloud),
                contentDescription = null,
                tint = badgeTint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
