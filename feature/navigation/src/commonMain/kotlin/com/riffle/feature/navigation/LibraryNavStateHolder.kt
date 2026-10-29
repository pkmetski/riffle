package com.riffle.feature.navigation

// Survives Activity recreation (Samsung Z Flip display-move) because Koin singletons are Application-scoped.
class LibraryNavStateHolder {
    var libraryId: String? = null
    var stack: List<LibraryNav> = listOf(LibraryNav.Items)
}
