package com.riffle.feature.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ic_readaloud
import com.riffle.feature.designsystem.generated.resources.ui_cached
import com.riffle.feature.designsystem.generated.resources.ui_downloaded
import com.riffle.feature.designsystem.generated.resources.ui_has_readaloud_synced_narration
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * One cover in a grid or shelf: the artwork, its overlay badges, and the title/author caption.
 *
 * Android and iOS each had a `BookCoverTile` and they had drifted into different components.
 * Android's drew the cover through Coil with the source's `Authorization` header, an alpha for an
 * unplayable item, a reading-progress bar and a title/author caption; iOS's drew the procedural
 * placeholder and nothing else, took its aspect ratio from the caller, and added an unlabelled
 * 8dp dot for downloaded/cached. This is the union, and it is the only definition.
 *
 * Deliberate convergences, since the two sides genuinely differed:
 *  - **iOS gains** cover artwork ([CoverImage]), the caption, the progress bar and the
 *    unplayable alpha. Callers no longer pass `Modifier.aspectRatio(…)`: the tile picks the
 *    cover's own ratio from [LibraryItem.isAudiobookOnly] / [LocalCoversAreSquare] (ADR 0035),
 *    which is what made an audiobook tile genuinely square on Android.
 *  - **Android gains** the offline-availability badge, now with a `contentDescription`
 *    ("Downloaded" / "Cached") instead of iOS's silent dot.
 *
 * ### Semantics contract — the harness depends on it
 * The clickable node merges its descendants and sets `contentDescription = item.title`, so the
 * tile is one accessibility element whose label is **exactly** the title. Both harnesses locate
 * tiles that way (`app.buttons["Test EPUB"]` on iOS, `onNodeWithContentDescription` on Android),
 * and `BookCoverTileTest` pins it. Two things keep that label exact: the cover image passes
 * `contentDescription = null` (`ContentDescription` merges by concatenation), and the caption is
 * `clearAndSetSemantics {}` because on iOS the accessibility label is built from the merged
 * node's text as well as its contentDescription. The badges sit *outside* the merge so they stay
 * individually addressable rather than being swallowed into it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCoverTile(
    item: LibraryItem,
    token: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    hasReadaloudLink: Boolean = false,
    seriesNameBadge: String? = null,
    sourceBadge: String? = null,
) {
    val isAudiobookOnly = item.isAudiobookOnly
    val coverAspect = coverAspectRatio(isAudiobookOnly || LocalCoversAreSquare.current)
    Box(modifier = modifier.alpha(if (item.isPlayable) 1f else 0.38f)) {
        Column(
            modifier = Modifier
                .semantics(mergeDescendants = true) { contentDescription = item.title }
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(
                            enabled = item.isPlayable,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    } else {
                        Modifier.clickable(enabled = item.isPlayable, onClick = onClick)
                    },
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(coverAspect)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                CoverImage(
                    url = item.coverUrl,
                    token = token,
                    // The merged parent already announces the title; a second copy here would be
                    // appended to the tile's label (ContentDescription merges by concatenation).
                    contentDescription = null,
                    isAudiobook = isAudiobookOnly,
                    modifier = Modifier.fillMaxSize(),
                    instrumentationKind = "item",
                    instrumentationKey = item.id,
                )
                if (item.readingProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { item.readingProgress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                        strokeCap = StrokeCap.Butt,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // clearAndSetSemantics, not plain Text: Compose's iOS accessibility bridge builds an
            // element's label from the merged node's contentDescription AND its text, so a
            // caption left in the merge turns the tile's label into "Title Title Author" and
            // `app.buttons["<title>"]` stops matching — the iOS harness locates every book that
            // way. (Compose's own `hasContentDescriptionExactly` still passes in that state, so
            // only the XCUITest suite catches it; see BookCoverTileTest.)
            Column(modifier = Modifier.clearAndSetSemantics { }) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Badge overlay: a sibling of the clickable column, sized to the cover box, so the badges
        // stay their own accessibility nodes instead of being merged into the tile's label. It
        // declares no pointer input, so taps fall through to the tile underneath.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspect)
                .align(Alignment.TopCenter),
        ) {
            if (hasReadaloudLink) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(RiffleTokens.CoverScrim),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_readaloud),
                        contentDescription = stringResource(Res.string.ui_has_readaloud_synced_narration),
                        tint = RiffleTokens.OnCoverScrim,
                        modifier = Modifier.size(17.dp),
                    )
                }
            } else if (item.isDownloaded || item.isCached) {
                // The offline-availability dot sits where the readaloud badge would; a linked
                // item shows the headphones instead so the two never collide.
                OfflineAvailabilityBadge(
                    downloaded = item.isDownloaded,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
            if (seriesNameBadge != null) {
                CoverPill(
                    text = seriesNameBadge,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = 5.dp, start = 5.dp),
                )
            }
            if (sourceBadge != null) {
                CoverPill(
                    text = sourceBadge,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 7.dp, end = 4.dp),
                    scrim = RiffleTokens.CoverPillScrimStrong,
                    horizontalPadding = 5.dp,
                )
            }
        }
    }
}

/** A rounded, scrimmed caption chip drawn over a cover (series position, source name). */
@Composable
private fun CoverPill(
    text: String,
    modifier: Modifier = Modifier,
    scrim: androidx.compose.ui.graphics.Color = RiffleTokens.CoverPillScrim,
    horizontalPadding: androidx.compose.ui.unit.Dp = 6.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scrim)
            .padding(horizontal = horizontalPadding, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = RiffleTokens.OnCoverScrim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The dot announcing that an item's file is on the device: solid for a pinned download, faded for
 * a merely cached copy. iOS drew this with no `contentDescription` at all, so VoiceOver skipped
 * the only offline signal on the screen.
 */
@Composable
private fun OfflineAvailabilityBadge(downloaded: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(if (downloaded) Res.string.ui_downloaded else Res.string.ui_cached)
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .semantics { contentDescription = label }
            .size(8.dp)
            .clip(CircleShape)
            .background(if (downloaded) color else color.copy(alpha = 0.4f)),
    )
}
