package com.riffle.shared.library

import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series

data class LibraryProjection(
    val series: List<Series>,
    val collections: List<Collection>,
    val ungrouped: List<LibraryItem>,
    val inProgress: List<LibraryItem>,
    val finished: List<LibraryItem>,
    val recentlyAdded: List<LibraryItem>,
    val continueSeries: List<LibraryItem>,
    val allBooks: List<LibraryItem>,
    val toRead: List<LibraryItem>,
    val annotations: List<AnnotationSearchResult>,
    val audiobookBookmarks: List<AudiobookBookmarkSearchResult>,
) {
    companion object {
        val Empty = LibraryProjection(
            series = emptyList(),
            collections = emptyList(),
            ungrouped = emptyList(),
            inProgress = emptyList(),
            finished = emptyList(),
            recentlyAdded = emptyList(),
            continueSeries = emptyList(),
            allBooks = emptyList(),
            toRead = emptyList(),
            annotations = emptyList(),
            audiobookBookmarks = emptyList(),
        )
    }
}
