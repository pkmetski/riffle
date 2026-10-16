package com.riffle.feature.navigation

import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.FacetType

sealed interface NavigationIntent {
    data class ReadItem(val item: LibraryItem) : NavigationIntent
    data class ListenItem(val item: LibraryItem) : NavigationIntent
    data class ReadItemAtHref(val item: LibraryItem, val href: String) : NavigationIntent
    data class ListenItemAtSec(val item: LibraryItem, val startAtSec: Double) : NavigationIntent
    data class OpenElidedReader(val itemId: String, val sourceId: String?) : NavigationIntent
    data class OpenAudiobookBookmark(val itemId: String, val sourceId: String?, val startAtSec: Double) : NavigationIntent
    data class OpenItemDetail(val itemId: String, val sourceId: String?) : NavigationIntent
    data class OpenSeriesDetail(val libraryId: String, val seriesId: String, val seriesName: String) : NavigationIntent
    data class OpenCollectionDetail(val libraryId: String, val collectionId: String, val collectionName: String) : NavigationIntent
    data class ShowAnnotationSearch(val libraryId: String, val query: String) : NavigationIntent
    data class OpenFilteredBooks(val libraryId: String, val facetType: FacetType, val facetValue: String) : NavigationIntent
    data object Back : NavigationIntent
}

fun reduceNavStack(
    intent: NavigationIntent,
    currentStack: List<LibraryNav>,
): List<LibraryNav> = when (intent) {
    is NavigationIntent.ReadItem -> {
        val incoming = readNavForItem(intent.item) ?: return currentStack
        if (isAlreadyAtPlayerDestination(currentStack, incoming)) currentStack
        else currentStack + incoming
    }
    is NavigationIntent.ListenItem -> {
        val incoming = readerNavForItem(intent.item) ?: return currentStack
        if (isAlreadyAtPlayerDestination(currentStack, incoming)) currentStack
        else currentStack + incoming
    }
    is NavigationIntent.ReadItemAtHref -> {
        val incoming = readNavForItemAtHref(intent.item, intent.href) ?: return currentStack
        currentStack + incoming
    }
    is NavigationIntent.ListenItemAtSec -> {
        val incoming = LibraryNav.AudiobookPlayer(intent.item, startAtSec = intent.startAtSec)
        if (isAlreadyAtPlayerDestination(currentStack, incoming)) currentStack
        else currentStack + incoming
    }
    is NavigationIntent.OpenElidedReader ->
        currentStack + LibraryNav.ElidedReader(intent.itemId, intent.sourceId)
    is NavigationIntent.OpenAudiobookBookmark ->
        currentStack + LibraryNav.AudiobookBookmark(intent.itemId, intent.sourceId, intent.startAtSec)
    is NavigationIntent.OpenItemDetail ->
        currentStack + LibraryNav.ItemDetail(intent.itemId, intent.sourceId)
    is NavigationIntent.OpenSeriesDetail ->
        currentStack + LibraryNav.SeriesDetail(intent.seriesId, intent.libraryId, intent.seriesName)
    is NavigationIntent.OpenCollectionDetail ->
        currentStack + LibraryNav.CollectionDetail(intent.collectionId, intent.libraryId, intent.collectionName)
    is NavigationIntent.ShowAnnotationSearch ->
        currentStack + LibraryNav.AnnotationSearch(intent.libraryId, intent.query)
    is NavigationIntent.OpenFilteredBooks ->
        currentStack + LibraryNav.FilteredBooks(intent.libraryId, intent.facetType, intent.facetValue)
    NavigationIntent.Back -> currentStack.dropLast(1)
}
