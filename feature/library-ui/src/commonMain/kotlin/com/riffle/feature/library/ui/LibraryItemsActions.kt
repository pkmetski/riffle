package com.riffle.feature.library.ui

import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.feature.library.AnnotationSearchResult
import com.riffle.feature.library.AudiobookBookmarkSearchResult
import com.riffle.feature.library.LibrarySectionType

data class LibraryItemsActions(
    val onOpenDrawer: () -> Unit,
    val onItemSelected: (LibraryItem) -> Unit,
    val onAnnotatedBookSelected: (sourceId: String, itemId: String) -> Unit,
    val onSeriesSelected: (Series) -> Unit,
    val onCollectionSelected: (Collection) -> Unit,
    val onSectionSeeMore: (LibrarySectionType) -> Unit,
    val onPlaylistSelected: (CatalogPlaylist) -> Unit,
    val onSearchAnnotations: (String) -> Unit,
    val onAnnotationSelected: (AnnotationSearchResult) -> Unit,
    val onAudiobookBookmarkSelected: (AudiobookBookmarkSearchResult) -> Unit,
    val onShowAllAnnotations: (String) -> Unit,
    val onNavigateBack: () -> Unit,
)

fun LibraryItemsActions.probeFields(): List<Pair<String, Any>> = listOf(
    "onOpenDrawer" to onOpenDrawer,
    "onItemSelected" to onItemSelected,
    "onAnnotatedBookSelected" to onAnnotatedBookSelected,
    "onSeriesSelected" to onSeriesSelected,
    "onCollectionSelected" to onCollectionSelected,
    "onSectionSeeMore" to onSectionSeeMore,
    "onPlaylistSelected" to onPlaylistSelected,
    "onSearchAnnotations" to onSearchAnnotations,
    "onAnnotationSelected" to onAnnotationSelected,
    "onAudiobookBookmarkSelected" to onAudiobookBookmarkSelected,
    "onShowAllAnnotations" to onShowAllAnnotations,
    "onNavigateBack" to onNavigateBack,
)
