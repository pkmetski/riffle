package com.riffle.shared.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.feature.library.LibrarySectionType
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private val SectionTitleStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
private val SeeAllStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF6650A4))

private const val SECTION_ROW_HEIGHT = 200
private const val SECTION_CELL_WIDTH = 120

@Composable
fun LibraryItemsScreen(
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (com.riffle.core.models.Collection) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    viewModel: LibraryItemsViewModel = koinInject { parametersOf(libraryId) },
) {
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    SideEffect {
        viewModel.setScreenDimensionBucket(
            com.riffle.core.models.ScreenDimensionBucket.PhonePortrait.copy(
                wider = if (containerWidthPx > 1400) {
                    com.riffle.core.models.ScreenDimensionBucket.SizeClass.Expanded
                } else {
                    com.riffle.core.models.ScreenDimensionBucket.SizeClass.Medium
                },
            )
        )
    }

    val isLoading by viewModel.isLoading.collectAsState()
    val projection by viewModel.projection.collectAsState()
    val coversAreSquare by viewModel.coversAreSquare.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            LibraryTopBar(title = libraryName, onMenuClick = onOpenDrawer)
        }
        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    BasicText("Loading…")
                }
            }
        } else {
            if (projection.inProgress.isNotEmpty()) {
                item { SectionHeader("In Progress") { onSectionSeeMore(LibrarySectionType.IN_PROGRESS) } }
                item {
                    HorizontalBookRow(
                        items = projection.inProgress.take(10),
                        onItemClick = onItemSelected,
                    )
                }
            }
            if (projection.continueSeries.isNotEmpty()) {
                item { SectionHeader("Continue Series") { onSectionSeeMore(LibrarySectionType.CONTINUE_SERIES) } }
                item {
                    HorizontalBookRow(
                        items = projection.continueSeries.take(10),
                        onItemClick = onItemSelected,
                    )
                }
            }
            if (projection.recentlyAdded.isNotEmpty()) {
                item { SectionHeader("Recently Added") { onSectionSeeMore(LibrarySectionType.RECENTLY_ADDED) } }
                item {
                    HorizontalBookRow(
                        items = projection.recentlyAdded.take(10),
                        onItemClick = onItemSelected,
                    )
                }
            }
            if (projection.finished.isNotEmpty()) {
                item { SectionHeader("Finished") { onSectionSeeMore(LibrarySectionType.FINISHED) } }
                item {
                    HorizontalBookRow(
                        items = projection.finished.take(10),
                        onItemClick = onItemSelected,
                    )
                }
            }
            if (projection.series.isNotEmpty()) {
                item { SectionHeader("Series", onSeeAll = null) }
                item {
                    SeriesRow(
                        series = projection.series.take(10),
                        onSeriesClick = onSeriesSelected,
                    )
                }
            }
            if (projection.collections.isNotEmpty()) {
                item { SectionHeader("Collections", onSeeAll = null) }
                item {
                    CollectionRow(
                        collections = projection.collections.take(10),
                        onCollectionClick = onCollectionSelected,
                    )
                }
            }
            if (projection.allBooks.isNotEmpty()) {
                item { SectionHeader("All Books", onSeeAll = null) }
                item {
                    BookGrid(
                        items = projection.allBooks,
                        coversAreSquare = coversAreSquare,
                        onItemClick = onItemSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTopBar(title: String, onMenuClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "☰",
            modifier = Modifier
                .padding(end = 12.dp)
                .clickable(onClick = onMenuClick),
            style = TextStyle(fontSize = 20.sp),
        )
        BasicText(text = title, style = SectionTitleStyle)
    }
}

@Composable
private fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = title, style = SectionTitleStyle)
        if (onSeeAll != null) {
            BasicText(
                text = "See all",
                modifier = Modifier.clickable(onClick = onSeeAll),
                style = SeeAllStyle,
            )
        }
    }
}

@Composable
private fun HorizontalBookRow(
    items: List<LibraryItem>,
    onItemClick: (LibraryItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(SECTION_ROW_HEIGHT.dp),
    ) {
        items(items, key = { it.id }) { item ->
            BookCoverTile(
                item = item,
                modifier = Modifier.width(SECTION_CELL_WIDTH.dp),
                onClick = { onItemClick(item) },
            )
        }
    }
}

@Composable
private fun BookGrid(
    items: List<LibraryItem>,
    coversAreSquare: Boolean,
    onItemClick: (LibraryItem) -> Unit,
) {
    val aspect = if (coversAreSquare) 1f else 2f / 3f
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(600.dp),
    ) {
        items(items, key = { it.id }) { item ->
            BookCoverTile(
                item = item,
                modifier = Modifier.aspectRatio(aspect),
                onClick = { onItemClick(item) },
            )
        }
    }
}

@Composable
fun BookCoverTile(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        DefaultCoverPlaceholder(
            isAudiobook = item.isListenable && !item.isReadable,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SeriesRow(
    series: List<Series>,
    onSeriesClick: (Series) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(SECTION_ROW_HEIGHT.dp),
    ) {
        items(series, key = { it.id }) { s ->
            SeriesTile(
                series = s,
                modifier = Modifier.width(SECTION_CELL_WIDTH.dp),
                onClick = { onSeriesClick(s) },
            )
        }
    }
}

@Composable
private fun SeriesTile(
    series: Series,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        DefaultCoverPlaceholder(
            isAudiobook = false,
            modifier = Modifier.fillMaxSize(),
        )
        BasicText(
            text = series.name,
            modifier = Modifier
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(4.dp),
            style = TextStyle(color = Color.White, fontSize = 11.sp),
        )
    }
}

@Composable
private fun CollectionRow(
    collections: List<Collection>,
    onCollectionClick: (Collection) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(SECTION_ROW_HEIGHT.dp),
    ) {
        items(collections, key = { it.id }) { col ->
            CollectionTile(
                collection = col,
                modifier = Modifier.width(SECTION_CELL_WIDTH.dp),
                onClick = { onCollectionClick(col) },
            )
        }
    }
}

@Composable
private fun CollectionTile(
    collection: Collection,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        DefaultCoverPlaceholder(
            isAudiobook = false,
            modifier = Modifier.fillMaxSize(),
        )
        BasicText(
            text = collection.name,
            modifier = Modifier
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(4.dp),
            style = TextStyle(color = Color.White, fontSize = 11.sp),
        )
    }
}
