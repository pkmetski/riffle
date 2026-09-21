package com.riffle.feature.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ui_no_items
import org.jetbrains.compose.resources.stringResource

/**
 * An adaptive grid of [BookCoverTile]s — the body of every "all of these books" screen.
 *
 * Android's copy lived in `LibrarySectionScreen.kt` and iOS's in `shared/.../LibraryItemsScreen.kt`,
 * and they disagreed on three things: Android showed an empty state and iOS showed a blank grid;
 * iOS pinned itself to `Modifier.height(600.dp)` while Android filled the pane; and only Android
 * indexed the cell size on the window width. This is one definition with Android's empty state,
 * Android's fill behaviour, and the shared [coverGridMinCell] breakpoint.
 *
 * [contentPadding] is added to the grid's own 12dp/8dp gutter, so a caller can pass a `Scaffold`'s
 * inner padding straight through.
 */
@Composable
fun BookGrid(
    items: List<LibraryItem>,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    hasReadaloudLink: (LibraryItem) -> Boolean = { false },
    seriesNameBadge: (LibraryItem) -> String? = { null },
) {
    if (items.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.ui_no_items))
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCell()),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            BookCoverTile(
                item = item,
                token = token,
                onClick = { onItemSelected(item) },
                hasReadaloudLink = hasReadaloudLink(item),
                seriesNameBadge = seriesNameBadge(item),
            )
        }
    }
}
