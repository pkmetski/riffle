package com.riffle.shared.library

enum class LibrarySortMode(val label: String) {
    ADDED_DESC("Recently added"),
    ADDED_ASC("Oldest first"),
    TITLE_ASC("Title A→Z"),
    TITLE_DESC("Title Z→A"),
    AUTHOR_ASC("Author A→Z"),
    RECENTLY_OPENED("Recently opened"),
}
