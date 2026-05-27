package com.riffle.app.feature.library

enum class LibrarySectionType {
    IN_PROGRESS, FINISHED, RECENTLY_ADDED;

    val displayName: String get() = when (this) {
        IN_PROGRESS    -> "In Progress"
        FINISHED       -> "Completed"
        RECENTLY_ADDED -> "Recently Added"
    }
}
