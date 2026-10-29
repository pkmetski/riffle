package com.riffle.feature.navigation

/**
 * In-memory singleton that preserves the library navigation stack across Activity recreations
 * within the same process. On Android foldables (e.g. Samsung Z Flip), switching between the
 * cover and main screens causes Android to recreate the Activity (display-move is not covered
 * by configChanges), discarding all Compose `remember` state including the nav stack. This holder
 * survives because Koin singletons live in the Application's scope, not the Activity's.
 *
 * The saved stack is associated with a [libraryId] so that switching to a different library
 * still resets navigation to the root, matching the existing `remember(activeLibraryId)` behaviour.
 */
class LibraryNavStateHolder {
    var libraryId: String? = null
    var stack: List<LibraryNav> = listOf(LibraryNav.Items)
}
