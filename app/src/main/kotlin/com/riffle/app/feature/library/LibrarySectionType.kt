package com.riffle.app.feature.library

enum class LibrarySectionType {
    IN_PROGRESS, FINISHED;

    val displayName: String get() = when (this) {
        IN_PROGRESS -> "In Progress"
        FINISHED    -> "Completed"
    }
}
