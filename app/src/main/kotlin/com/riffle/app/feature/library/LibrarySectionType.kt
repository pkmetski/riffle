package com.riffle.app.feature.library

enum class LibrarySectionType {
    IN_PROGRESS, FINISHED, RECENTLY_ADDED, CONTINUE_SERIES;

    val displayName: String get() = when (this) {
        IN_PROGRESS    -> "In Progress"
        FINISHED       -> "Completed"
        RECENTLY_ADDED -> "Recently Added"
        CONTINUE_SERIES -> "Continue Series"
    }
}
