package com.riffle.app.feature.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.riffle.app.ui.fadingScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.riffle.app.R
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Collection
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.Series
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.floor
import kotlin.math.max


/**
 * True within an audiobooks-only library, so cover tiles that carry no per-item audio signal —
 * [SeriesCoverTile], [CollectionCoverTile], [SeeMoreTile] — render square like the audiobook covers
 * around them (ADR 0029). Provided once at the library screen root from the ViewModel.
 */
internal val LocalCoversAreSquare = staticCompositionLocalOf { false }

private fun coverAspectRatio(square: Boolean): Float = if (square) 1f else 2f / 3f

@Composable
fun LibraryItemsScreen(
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onAnnotationSelected: (AnnotationSearchResult) -> Unit,
    onAudiobookBookmarkSelected: (AudiobookBookmarkSearchResult) -> Unit,
    onShowAllAnnotations: (query: String) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    // When the navigation drawer is open, its own BackHandler must take Back so it can close
    // itself. We disable our layered Back in that case (issue #60).
    backEnabled: Boolean = true,
    viewModel: LibraryItemsViewModel = hiltViewModel(),
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val projection by viewModel.projection.collectAsState()
    val filteredUngroupedItems = projection.ungrouped
    val inProgress = projection.inProgress
    val continueSeries = projection.continueSeries
    val allBooks = projection.allBooks
    val finished = projection.finished
    val recentlyAdded = projection.recentlyAdded
    val series = projection.series
    val collections = projection.collections
    val toReadItems = projection.toRead
    val annotationResults = projection.annotations
    val audiobookBookmarkResults = projection.audiobookBookmarks
    val collectionCoverUrls by viewModel.collectionCoverUrls.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val linkedItemIds by viewModel.linkedItemIds.collectAsState()
    val notStartedFilterActive by viewModel.notStartedFilterActive.collectAsState()

    val coversAreSquare by viewModel.coversAreSquare.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Drive the grids off a local live scale so a pinch reflows instantly; the
    // persisted value (collected here) seeds it and wins on any external change.
    val persistedCoverScale by viewModel.coverGridScale.collectAsState()
    var liveCoverScale by remember { mutableFloatStateOf(persistedCoverScale) }
    LaunchedEffect(persistedCoverScale) { liveCoverScale = persistedCoverScale }
    val onCoverScaleChange: (Float) -> Unit = {
        liveCoverScale = it
        viewModel.setCoverGridScale(it)
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.yield()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                // Re-check the server on every resume (e.g. wake from sleep) so a stale offline
                // banner left by an earlier failed refresh clears immediately, rather than only
                // when the periodic retry next fires.
                viewModel.onScreenResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Layered Back (issue #60): clear an active search first, then drop back to the Home tab,
    // and only exit the app from a clean Home state. Without this the drawer's own BackHandler
    // can intercept Back if the drawer is still in its closing animation.
    val activity = LocalActivity.current
    BackHandler(enabled = backEnabled) {
        when (libraryBackAction(searchQuery, selectedTab)) {
            LibraryBackAction.ClearSearch -> {
                viewModel.onSearchQueryChange("")
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            LibraryBackAction.ResetToHomeTab -> { selectedTab = 0 }
            LibraryBackAction.Exit -> activity?.finish()
        }
    }

    CompositionLocalProvider(
        LocalCoversAreSquare provides coversAreSquare,
        LocalCoverGridScale provides liveCoverScale,
    ) {
    Scaffold(
        topBar = {
            LibrarySearchHeader(
                libraryName = libraryName,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onOpenDrawer = onOpenDrawer,
            )
        },
        bottomBar = {
            if (searchQuery.isEmpty()) {
                LibraryTabBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (isOffline) {
                OfflineBanner()
            }

            val queryActive = searchQuery.isNotEmpty()
            if (queryActive) {
                SearchResultsContent(
                    query = searchQuery,
                    filteredSeries = series,
                    filteredCollections = collections,
                    filteredItems = filteredUngroupedItems,
                    annotationResults = annotationResults,
                    audiobookBookmarkResults = audiobookBookmarkResults,
                    token = viewModel.authToken,
                    onSeriesSelected = onSeriesSelected,
                    onCollectionSelected = onCollectionSelected,
                    onItemSelected = onItemSelected,
                    onAnnotationSelected = onAnnotationSelected,
                    onAudiobookBookmarkSelected = onAudiobookBookmarkSelected,
                    onShowAllAnnotations = onShowAllAnnotations,
                    linkedItemIds = linkedItemIds,
                )
            } else {
                when (selectedTab) {
                    0 -> HomeTabContent(
                        inProgress = inProgress,
                        continueSeries = continueSeries,
                        recentlyAdded = recentlyAdded,
                        finished = finished,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        onItemSelected = onItemSelected,
                        onSectionSeeMore = onSectionSeeMore,
                        linkedItemIds = linkedItemIds,
                        onCoverScaleChange = onCoverScaleChange,
                    )
                    1 -> ToReadTabContent(
                        items = toReadItems,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        onItemSelected = onItemSelected,
                        linkedItemIds = linkedItemIds,
                        onCoverScaleChange = onCoverScaleChange,
                    )
                    2 -> SeriesTabContent(
                        items = series,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        onSeriesSelected = onSeriesSelected,
                        onCoverScaleChange = onCoverScaleChange,
                    )
                    3 -> CollectionsTabContent(
                        items = collections,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        collectionCoverUrls = collectionCoverUrls,
                        onCollectionSelected = onCollectionSelected,
                        onCoverScaleChange = onCoverScaleChange,
                    )
                    4 -> AllBooksTabContent(
                        items = allBooks,
                        isLoading = isLoading,
                        token = viewModel.authToken,
                        onItemSelected = onItemSelected,
                        linkedItemIds = linkedItemIds,
                        onCoverScaleChange = onCoverScaleChange,
                        notStartedFilterActive = notStartedFilterActive,
                        onToggleNotStartedFilter = viewModel::toggleNotStartedFilter,
                    )
                    else -> {}
                }
            }
        }
    }
    }
}

@Composable
private fun SearchResultsContent(
    query: String,
    filteredSeries: List<Series>,
    filteredCollections: List<Collection>,
    filteredItems: List<LibraryItem>,
    annotationResults: List<AnnotationSearchResult>,
    audiobookBookmarkResults: List<AudiobookBookmarkSearchResult>,
    token: String,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onAnnotationSelected: (AnnotationSearchResult) -> Unit,
    onAudiobookBookmarkSelected: (AudiobookBookmarkSearchResult) -> Unit,
    onShowAllAnnotations: (query: String) -> Unit,
    linkedItemIds: Set<String> = emptySet(),
) {
    val allEmpty = filteredSeries.isEmpty() && filteredCollections.isEmpty() &&
        filteredItems.isEmpty() && annotationResults.isEmpty() && audiobookBookmarkResults.isEmpty()
    if (allEmpty) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No results for '$query'")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (filteredSeries.isNotEmpty()) {
            item { SectionHeader("Series") }
            items(filteredSeries, key = { "series_${it.id}" }) { s ->
                SearchSeriesRow(series = s, token = token, onClick = { onSeriesSelected(s) })
            }
        }
        if (filteredCollections.isNotEmpty()) {
            item { SectionHeader("Collections") }
            items(filteredCollections, key = { "col_${it.id}" }) { col ->
                SearchCollectionRow(collection = col, onClick = { onCollectionSelected(col) })
            }
        }
        if (filteredItems.isNotEmpty()) {
            item { SectionHeader("Books") }
            items(filteredItems, key = { "item_${it.id}" }) { item ->
                LibraryItemCard(
                    item = item,
                    token = token,
                    onClick = { onItemSelected(item) },
                    hasReadaloudLink = item.id in linkedItemIds,
                )
            }
        }
        val hasAnnotations = annotationResults.isNotEmpty() || audiobookBookmarkResults.isNotEmpty()
        if (hasAnnotations) {
            item { SectionHeader("Annotations") }
            val totalCount = annotationResults.size + audiobookBookmarkResults.size
            // Split the preview cap fairly so neither type is starved when both are present.
            // Each type gets at least ceil(cap/2) slots; unused slots from one side spill to the other.
            val halfCap = (ANNOTATION_PREVIEW_CAP + 1) / 2
            val annoPreview = annotationResults.take(if (audiobookBookmarkResults.isEmpty()) ANNOTATION_PREVIEW_CAP else halfCap)
            val bmPreview = audiobookBookmarkResults.take(ANNOTATION_PREVIEW_CAP - annoPreview.size)
            items(annoPreview, key = { "anno_${it.annotation.id}" }) { result ->
                AnnotationResultRow(
                    result = result,
                    token = token,
                    onClick = { onAnnotationSelected(result) },
                )
            }
            items(bmPreview, key = { "abm_${it.bookmark.id}" }) { result ->
                AudiobookBookmarkResultRow(
                    result = result,
                    token = token,
                    onClick = { onAudiobookBookmarkSelected(result) },
                )
            }
            if (totalCount > ANNOTATION_PREVIEW_CAP) {
                item(key = "anno_show_all") {
                    ShowAllAnnotationsRow(
                        count = totalCount,
                        onClick = { onShowAllAnnotations(query) },
                    )
                }
            }
        }
    }
}

private const val ANNOTATION_PREVIEW_CAP = 5

// --- Section grids ---

@Composable
fun BookSectionGrid(
    items: List<LibraryItem>,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    onSeeMore: (() -> Unit)? = null,
    linkedItemIds: Set<String> = emptySet(),
    showSeriesBadge: Boolean = false,
) {
    val minCell = shelfCoverMinCellSize()
    val spacing = 8.dp
    BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
        val columns = max(1, floor((maxWidth + spacing) / (minCell + spacing)).toInt())
        val previewCount = max(1, columns * 2 - 1)
        val showSeeMore = onSeeMore != null && items.size > previewCount
        val preview = if (onSeeMore != null) items.take(previewCount) else items
        val overflowCount = items.size - previewCount
        CoverGridLayout(
            count = preview.size + (if (showSeeMore) 1 else 0),
            columns = columns,
            spacing = spacing,
        ) { index ->
            if (showSeeMore && index == preview.size) {
                SeeMoreTile(overflowCount = overflowCount, onClick = onSeeMore)
            } else {
                val item = preview[index]
                BookCoverTile(
                    item = item,
                    token = token,
                    onClick = { onItemSelected(item) },
                    hasReadaloudLink = item.id in linkedItemIds,
                    seriesNameBadge = if (showSeriesBadge) item.seriesName else null,
                )
            }
        }
    }
}

@Composable
fun SeriesSectionGrid(
    items: List<Series>,
    token: String,
    onSeriesSelected: (Series) -> Unit,
    onSeeMore: (() -> Unit)? = null,
) {
    val minCell = shelfCoverMinCellSize()
    val spacing = 8.dp
    BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
        val columns = max(1, floor((maxWidth + spacing) / (minCell + spacing)).toInt())
        val previewCount = max(1, columns * 2 - 1)
        val showSeeMore = onSeeMore != null && items.size > previewCount
        val preview = if (onSeeMore != null) items.take(previewCount) else items
        val overflowCount = items.size - previewCount
        CoverGridLayout(
            count = preview.size + (if (showSeeMore) 1 else 0),
            columns = columns,
            spacing = spacing,
        ) { index ->
            if (showSeeMore && index == preview.size) {
                SeeMoreTile(overflowCount = overflowCount, onClick = onSeeMore)
            } else {
                val s = preview[index]
                SeriesCoverTile(series = s, token = token, onClick = { onSeriesSelected(s) })
            }
        }
    }
}

@Composable
fun CollectionsSectionGrid(
    items: List<Collection>,
    token: String,
    coverUrls: Map<String, List<String>>,
    onCollectionSelected: (Collection) -> Unit,
    onSeeMore: (() -> Unit)? = null,
) {
    val minCell = shelfCoverMinCellSize()
    val spacing = 8.dp
    BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
        val columns = max(1, floor((maxWidth + spacing) / (minCell + spacing)).toInt())
        val previewCount = max(1, columns * 2 - 1)
        val showSeeMore = onSeeMore != null && items.size > previewCount
        val preview = if (onSeeMore != null) items.take(previewCount) else items
        val overflowCount = items.size - previewCount
        CoverGridLayout(
            count = preview.size + (if (showSeeMore) 1 else 0),
            columns = columns,
            spacing = spacing,
        ) { index ->
            if (showSeeMore && index == preview.size) {
                SeeMoreTile(overflowCount = overflowCount, onClick = onSeeMore)
            } else {
                val col = preview[index]
                CollectionCoverTile(
                    collection = col,
                    coverUrls = coverUrls[col.id].orEmpty(),
                    token = token,
                    onClick = { onCollectionSelected(col) },
                )
            }
        }
    }
}

// --- Generic adaptive cover grid layout ---

@Composable
private fun CoverGridLayout(
    count: Int,
    columns: Int,
    spacing: Dp = 8.dp,
    content: @Composable (index: Int) -> Unit,
) {
    val rows = (count + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (col in 0 until columns) {
                    val index = row * columns + col
                    Box(modifier = Modifier.weight(1f)) {
                        if (index < count) content(index)
                    }
                }
            }
        }
    }
}

// --- Cover tiles ---

@Composable
fun BookCoverTile(
    item: LibraryItem,
    token: String,
    onClick: () -> Unit,
    hasReadaloudLink: Boolean = false,
    seriesNameBadge: String? = null,
) {
    val alpha = if (!item.isPlayable) 0.38f else 1f
    // Audiobook covers are square (1:1); ebook covers are 2:3. The tile takes the cover's own aspect
    // ratio so an audiobook tile is genuinely square, not a square letterboxed inside a 2:3 box
    // (ADR 0029).
    val isAudiobookOnly = item.isListenable && !item.isReadable
    val coverAspect = coverAspectRatio(isAudiobookOnly || LocalCoversAreSquare.current)
    Column(
        modifier = Modifier
            .alpha(alpha)
            .clickable(enabled = item.isPlayable, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspect)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.coverUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .crossfade(true)
                    .build(),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.readingProgress > 0f) {
                LinearProgressIndicator(
                    progress = { item.readingProgress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
            if (hasReadaloudLink) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_readaloud),
                        contentDescription = "Has readaloud (synced narration)",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            if (seriesNameBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 5.dp, start = 5.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.70f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = seriesNameBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // No medium glyph: the square cover already distinguishes an Audiobook from a 2:3 ebook
            // (and a glyph would collide with the readaloud badge above).
        }
        Spacer(modifier = Modifier.height(4.dp))
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

@Composable
fun SeriesCoverTile(
    series: Series,
    token: String,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(series.coverUrl)
                .addHeader("Authorization", "Bearer $token")
                .crossfade(true)
                .build(),
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            contentDescription = series.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspectRatio(LocalCoversAreSquare.current))
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = series.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CollectionCoverTile(
    collection: Collection,
    coverUrls: List<String>,
    token: String,
    onClick: () -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.outline
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspectRatio(LocalCoversAreSquare.current))
                .clip(RoundedCornerShape(4.dp))
                .drawBehind {
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        ) {
            when {
                coverUrls.isEmpty() -> { /* outlined empty tile */ }
                coverUrls.size == 1 -> CollectionCoverImage(coverUrls[0], token, Modifier.fillMaxSize())
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            CollectionCoverImage(coverUrls.getOrNull(0), token, Modifier.weight(1f).fillMaxHeight())
                            CollectionCoverImage(coverUrls.getOrNull(1), token, Modifier.weight(1f).fillMaxHeight())
                        }
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            CollectionCoverImage(coverUrls.getOrNull(2), token, Modifier.weight(1f).fillMaxHeight())
                            CollectionCoverImage(coverUrls.getOrNull(3), token, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = collection.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CollectionCoverImage(url: String?, token: String, modifier: Modifier) {
    if (url == null) {
        Box(modifier = modifier)
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .addHeader("Authorization", "Bearer $token")
            .crossfade(true)
            .build(),
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
fun SeeMoreTile(overflowCount: Int, onClick: () -> Unit) {
    val dashColor = MaterialTheme.colorScheme.outline
    Column(modifier = Modifier.clickable(onClick = onClick)) {
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
        Text(
            text = "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// --- Search result rows (kept for search mode) ---

@Composable
private fun SearchSeriesRow(series: Series, token: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(series.coverUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .crossfade(true)
                    .build(),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                contentDescription = series.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = 72.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = series.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${series.bookCount} book${if (series.bookCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchCollectionRow(collection: Collection, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = collection.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = "${collection.bookCount} book${if (collection.bookCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AnnotationResultRow(
    result: AnnotationSearchResult,
    token: String,
    onClick: () -> Unit,
) {
    val annotation = result.annotation
    val isBookmark = annotation.type == AnnotationEntity.TYPE_BOOKMARK
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            // Leading: highlight colour bar, or a bookmark glyph.
            Box(modifier = Modifier.size(width = 16.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                if (isBookmark) {
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    val color = HighlightColor.fromToken(annotation.color)
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = Color(color.argb.toLong() and 0xFFFFFFFFL),
                        modifier = Modifier.size(width = 4.dp, height = 40.dp),
                    ) {}
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val primary = if (isBookmark) annotation.bookmarkTitle.ifBlank { "Bookmark" } else annotation.textSnippet
                Text(
                    text = primary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val note = annotation.note
                if (!isBookmark && !note.isNullOrBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(result.bookCoverUrl)
                            .addHeader("Authorization", "Bearer $token")
                            .crossfade(true)
                            .build(),
                        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 20.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = result.bookTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AudiobookBookmarkResultRow(
    result: AudiobookBookmarkSearchResult,
    token: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.size(width = 16.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.bookmark.title.ifBlank { "Audiobook Bookmark" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(result.bookCoverUrl)
                            .addHeader("Authorization", "Bearer $token")
                            .crossfade(true)
                            .build(),
                        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 20.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = result.bookTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowAllAnnotationsRow(count: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Show all $count annotations",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
}

// --- Header / banner composables ---

@Composable
internal fun LibrarySearchHeader(
    libraryName: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    // Claim initial focus on an invisible focusable element so the BasicTextField below never
    // receives auto-focus on entry (e.g. after login). clearFocus() alone races with Android's
    // view-focus pass; assigning focus explicitly is reliable.
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        initialFocus.requestFocus()
        keyboardController?.hide()
    }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(initialFocus)
                .focusable(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 16.dp),
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "Open menu")
            }
            Text(
                text = libraryName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            val underlineColor = MaterialTheme.colorScheme.primary
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = underlineColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    },
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun LibraryItemCard(
    item: LibraryItem,
    token: String,
    onClick: (() -> Unit)? = null,
    hasReadaloudLink: Boolean = false,
) {
    val alpha = if (!item.isPlayable) 0.38f else 1f
    // Square thumbnail for an audiobook (1:1), 2:3 for an ebook (ADR 0029).
    val isAudiobookOnly = item.isListenable && !item.isReadable
    Surface(
        modifier = if (onClick != null)
            Modifier.fillMaxWidth().alpha(alpha).clickable(onClick = onClick)
        else
            Modifier.fillMaxWidth().alpha(alpha),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.coverUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .crossfade(true)
                    .build(),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = if (isAudiobookOnly) 48.dp else 72.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasReadaloudLink) {
                            // Glyph badge per #36: signals an ABS book has a Confirmed-matched
                            // Storyteller readaloud, or a Readaloud book is paired with an ABS item.
                            Icon(
                                painter = painterResource(R.drawable.ic_readaloud),
                                contentDescription = "Has readaloud (synced narration)",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        if (!item.isPlayable) {
                            Text(
                                text = "Not supported",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (item.isCached) {
                                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text("Cached", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (item.isDownloaded) {
                                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("Downloaded", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.readingProgress > 0f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { item.readingProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(item.readingProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --- Tab bar ---

@Composable
private fun LibraryTabBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            icon = { Icon(Icons.Filled.Bookmarks, contentDescription = "To Read") },
        )
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
            icon = { Icon(Icons.Filled.FormatListNumbered, contentDescription = "Series") },
        )
        NavigationBarItem(
            selected = selectedTab == 3,
            onClick = { onTabSelected(3) },
            icon = { Icon(Icons.Filled.Folder, contentDescription = "Collections") },
        )
        NavigationBarItem(
            selected = selectedTab == 4,
            onClick = { onTabSelected(4) },
            icon = { Icon(Icons.Filled.GridView, contentDescription = "All Books") },
        )
    }
}

// --- Tab content composables ---

internal fun homeLeadingSectionKey(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    recentlyAdded: List<LibraryItem>,
    finished: List<LibraryItem>,
): String? = when {
    inProgress.isNotEmpty() -> "in_progress"
    continueSeries.isNotEmpty() -> "continue_series"
    recentlyAdded.isNotEmpty() -> "recently_added"
    finished.isNotEmpty() -> "finished"
    else -> null
}

@Composable
private fun HomeTabContent(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    recentlyAdded: List<LibraryItem>,
    finished: List<LibraryItem>,
    isLoading: Boolean,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    linkedItemIds: Set<String> = emptySet(),
    onCoverScaleChange: (Float) -> Unit = {},
) {
    if (isLoading) return
    if (inProgress.isEmpty() && continueSeries.isEmpty() && recentlyAdded.isEmpty() && finished.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to show here")
        }
        return
    }
    val listState = rememberLazyListState()
    val leadingSectionKey = homeLeadingSectionKey(
        inProgress = inProgress,
        continueSeries = continueSeries,
        recentlyAdded = recentlyAdded,
        finished = finished,
    )
    val userHasScrolled = remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions
            .filterIsInstance<DragInteraction.Start>()
            .collect { userHasScrolled.value = true }
    }
    // Sections hydrate asynchronously; a late-arriving higher-priority section
    // would be prepended above the user's view, leaving them parked on a lower
    // section ("scrolled to the bottom"). While the user hasn't dragged, keep
    // the column anchored at the top so prepends don't shove them down.
    LaunchedEffect(leadingSectionKey, userHasScrolled.value) {
        if (!userHasScrolled.value && leadingSectionKey != null) {
            listState.scrollToItem(0)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .pinchCoverZoom(onCoverScaleChange)
            .fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (inProgress.isNotEmpty()) {
            item(key = "header_in_progress") { SectionHeader(LibrarySectionType.IN_PROGRESS.displayName) }
            item(key = "grid_in_progress") {
                BookSectionGrid(
                    items = inProgress,
                    token = token,
                    onItemSelected = onItemSelected,
                    onSeeMore = { onSectionSeeMore(LibrarySectionType.IN_PROGRESS) },
                    linkedItemIds = linkedItemIds,
                )
            }
        }
        if (continueSeries.isNotEmpty()) {
            item(key = "header_continue_series") { SectionHeader(LibrarySectionType.CONTINUE_SERIES.displayName) }
            item(key = "grid_continue_series") {
                BookSectionGrid(
                    items = continueSeries,
                    token = token,
                    onItemSelected = onItemSelected,
                    onSeeMore = null,
                    linkedItemIds = linkedItemIds,
                    showSeriesBadge = true,
                )
            }
        }
        if (recentlyAdded.isNotEmpty()) {
            item(key = "header_recently_added") { SectionHeader(LibrarySectionType.RECENTLY_ADDED.displayName) }
            item(key = "grid_recently_added") {
                BookSectionGrid(
                    items = recentlyAdded,
                    token = token,
                    onItemSelected = onItemSelected,
                    onSeeMore = { onSectionSeeMore(LibrarySectionType.RECENTLY_ADDED) },
                    linkedItemIds = linkedItemIds,
                )
            }
        }
        if (finished.isNotEmpty()) {
            item(key = "header_completed") { SectionHeader(LibrarySectionType.FINISHED.displayName) }
            item(key = "grid_completed") {
                BookSectionGrid(
                    items = finished,
                    token = token,
                    onItemSelected = onItemSelected,
                    onSeeMore = { onSectionSeeMore(LibrarySectionType.FINISHED) },
                    linkedItemIds = linkedItemIds,
                )
            }
        }
    }
}

@Composable
private fun SeriesTabContent(
    items: List<Series>,
    isLoading: Boolean,
    token: String,
    onSeriesSelected: (Series) -> Unit,
    onCoverScaleChange: (Float) -> Unit = {},
) {
    if (isLoading) return
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No series in this library")
        }
        return
    }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(coverGridMinCellSize()),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp, bottom = 16.dp,
        ),
        modifier = Modifier
            .pinchCoverZoom(onCoverScaleChange)
            .fillMaxSize()
            .fadingScrollbar(gridState),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader("Series (${items.size})")
        }
        items(items, key = { it.id }) { s ->
            Box(modifier = Modifier.padding(4.dp)) {
                SeriesCoverTile(series = s, token = token, onClick = { onSeriesSelected(s) })
            }
        }
    }
}

@Composable
private fun CollectionsTabContent(
    items: List<Collection>,
    isLoading: Boolean,
    token: String,
    collectionCoverUrls: Map<String, List<String>>,
    onCollectionSelected: (Collection) -> Unit,
    onCoverScaleChange: (Float) -> Unit = {},
) {
    if (isLoading) return
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No collections in this library")
        }
        return
    }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(coverGridMinCellSize()),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp, bottom = 16.dp,
        ),
        modifier = Modifier
            .pinchCoverZoom(onCoverScaleChange)
            .fillMaxSize()
            .fadingScrollbar(gridState),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader("Collections (${items.size})")
        }
        items(items, key = { it.id }) { col ->
            Box(modifier = Modifier.padding(4.dp)) {
                CollectionCoverTile(
                    collection = col,
                    coverUrls = collectionCoverUrls[col.id].orEmpty(),
                    token = token,
                    onClick = { onCollectionSelected(col) },
                )
            }
        }
    }
}

@Composable
private fun ToReadTabContent(
    items: List<LibraryItem>,
    isLoading: Boolean,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    linkedItemIds: Set<String> = emptySet(),
    onCoverScaleChange: (Float) -> Unit = {},
) {
    if (isLoading) return
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing in To Read")
        }
        return
    }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(shelfCoverMinCellSize()),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp, bottom = 16.dp,
        ),
        modifier = Modifier
            .pinchCoverZoom(onCoverScaleChange)
            .fillMaxSize()
            .fadingScrollbar(gridState),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader("To Read (${items.size})")
        }
        items(items, key = { it.id }) { item ->
            Box(modifier = Modifier.padding(4.dp)) {
                BookCoverTile(item = item, token = token, onClick = { onItemSelected(item) }, hasReadaloudLink = item.id in linkedItemIds)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllBooksTabContent(
    items: List<LibraryItem>,
    isLoading: Boolean,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    linkedItemIds: Set<String> = emptySet(),
    onCoverScaleChange: (Float) -> Unit = {},
    notStartedFilterActive: Boolean = false,
    onToggleNotStartedFilter: () -> Unit = {},
) {
    if (isLoading) return
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("All Books (${items.size})")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = notStartedFilterActive,
                    onClick = onToggleNotStartedFilter,
                    label = { Text("Not Started") },
                    leadingIcon = if (notStartedFilterActive) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                )
            }
        }
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (notStartedFilterActive) "No unstarted books" else "No items in this library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(coverGridMinCellSize()),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                modifier = Modifier
                    .pinchCoverZoom(onCoverScaleChange)
                    .fillMaxSize()
                    .fadingScrollbar(gridState),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(modifier = Modifier.padding(4.dp)) {
                        BookCoverTile(
                            item = item,
                            token = token,
                            onClick = { onItemSelected(item) },
                            hasReadaloudLink = item.id in linkedItemIds,
                        )
                    }
                }
            }
        }
    }
}
