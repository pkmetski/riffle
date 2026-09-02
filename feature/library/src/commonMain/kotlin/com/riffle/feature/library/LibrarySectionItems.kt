package com.riffle.feature.library

import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.Flow

fun librarySectionItems(
    libraryObserver: LibraryObserver,
    libraryId: String,
    sectionType: LibrarySectionType,
): Flow<List<LibraryItem>> = when (sectionType) {
    LibrarySectionType.IN_PROGRESS -> libraryObserver.observeInProgressItems(libraryId)
    LibrarySectionType.FINISHED -> libraryObserver.observeFinishedItems(libraryId)
    LibrarySectionType.RECENTLY_ADDED -> libraryObserver.observeRecentlyAddedItems(libraryId)
    LibrarySectionType.CONTINUE_SERIES -> libraryObserver.observeContinueSeriesItems(libraryId)
}
