package com.riffle.app.feature.library

enum class LibrarySectionType {
    IN_PROGRESS, SERIES, COLLECTIONS, ALL_BOOKS, FINISHED;

    val displayName: String get() = when (this) {
        IN_PROGRESS  -> "In Progress"
        SERIES       -> "Series"
        COLLECTIONS  -> "Collections"
        ALL_BOOKS    -> "All Books"
        FINISHED     -> "Finished"
    }
}
