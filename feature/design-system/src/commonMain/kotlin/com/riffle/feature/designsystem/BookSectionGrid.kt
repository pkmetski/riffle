package com.riffle.feature.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.core.models.LibraryItem

/**
 * A 2-row preview grid of book cover tiles, used in the Home tab for each library section
 * (In Progress, Continue Series, Recently Added, Completed).
 *
 * Column count is computed from available width using [shelfCoverMinCell]. Preview shows
 * `columns × 2 − 1` tiles. When [onSeeMore] is non-null and there are more items, a
 * [SeeMoreTile] is appended as the last tile of the grid.
 *
 * Shared between Android (`:app`) and iOS (`:shared`) so the two hosts never drift.
 */
@Composable
fun BookSectionGrid(
    items: List<LibraryItem>,
    token: String,
    linkedItemIds: Set<String> = emptySet(),
    onItemSelected: (LibraryItem) -> Unit,
    onSeeMore: (() -> Unit)? = null,
    showSeriesBadge: Boolean = false,
    onItemLongPress: ((LibraryItem) -> Unit)? = null,
    tokenMap: Map<String, String> = emptyMap(),
    sourceBadgeProvider: ((LibraryItem) -> String?)? = null,
) {
    val minCell = shelfCoverMinCell()
    val spacing = 8.dp
    BoxWithConstraints(modifier = Modifier.padding(horizontal = 12.dp)) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minCell + spacing)).toInt())
        val previewCount = maxOf(1, columns * 2 - 1)
        val showSeeMore = onSeeMore != null && items.size > previewCount
        val preview = if (onSeeMore != null) items.take(previewCount) else items
        val overflowCount = items.size - previewCount
        CoverGridLayout(
            count = preview.size + (if (showSeeMore) 1 else 0),
            columns = columns,
            spacing = spacing,
            bottomPadding = spacing,
        ) { index ->
            if (showSeeMore && index == preview.size) {
                SeeMoreTile(overflowCount = overflowCount, onClick = onSeeMore!!)
            } else {
                val item = preview[index]
                val resolvedToken = tokenMap[item.sourceId] ?: token
                BookCoverTile(
                    item = item,
                    token = resolvedToken,
                    onClick = { onItemSelected(item) },
                    onLongClick = if (onItemLongPress != null) ({ onItemLongPress(item) }) else null,
                    hasReadaloudLink = item.id in linkedItemIds,
                    seriesNameBadge = if (showSeriesBadge) item.seriesName else null,
                    sourceBadge = sourceBadgeProvider?.invoke(item),
                )
            }
        }
    }
}

/**
 * Generic adaptive column grid laid out as plain [Row]s inside a [Column].
 * Unlike [androidx.compose.foundation.lazy.grid.LazyVerticalGrid] this can be embedded inside a
 * [androidx.compose.foundation.lazy.LazyColumn] without triggering a nested-scroll conflict.
 */
@Composable
fun CoverGridLayout(
    count: Int,
    columns: Int,
    spacing: Dp = 8.dp,
    bottomPadding: Dp = 0.dp,
    content: @Composable (index: Int) -> Unit,
) {
    require(columns > 0) { "columns must be > 0, got $columns" }
    val rows = (count + columns - 1) / columns
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = Modifier.padding(bottom = bottomPadding),
    ) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (col in 0 until columns) {
                    val index = row * columns + col
                    Box(modifier = Modifier.weight(1f)) {
                        if (index < count) {
                            content(index)
                        } else {
                            // Empty filler cell keeps the last row evenly spaced.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(coverAspectRatio(LocalCoversAreSquare.current)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Overflow tile shown after the last preview item. Displays "+N\nmore" inside a dashed rounded
 * rectangle, with the same cover aspect ratio as its siblings so the row heights stay aligned.
 */
@Composable
fun SeeMoreTile(overflowCount: Int, onClick: () -> Unit) {
    val dashColor = MaterialTheme.colorScheme.outline
    Column(modifier = Modifier.testTag(TestTags.SEE_MORE_TILE).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspectRatio(LocalCoversAreSquare.current))
                .clip(RoundedCornerShape(4.dp))
                .drawBehind {
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()), 0f)
                    drawRoundRect(
                        color = dashColor,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashEffect),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+$overflowCount\nmore",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Blank label preserves the same vertical rhythm as a tile with a title below it.
        Text(text = "", style = MaterialTheme.typography.bodySmall)
    }
}
