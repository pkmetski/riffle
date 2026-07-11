package com.riffle.app.feature.library

/**
 * Which optional Library-tab-bar entries are shown for the active Source. Home and All Books are
 * unconditional and not tracked here. Populated by [LibraryItemsViewModel] from the active
 * Catalog's capabilities (issue #439 / ADR 0041) and, for [annotations], from whether the current
 * library contains any readable items.
 */
data class LibraryTabVisibility(
    val toRead: Boolean,
    val series: Boolean,
    val collections: Boolean,
    // Annotations are anchored to ebook text — an audiobook-only library can never have any. Hide
    // the tab when the current library contains no readable items so the surface isn't dead UI.
    // Reactive: adding a readable item later flips this back on live.
    val annotations: Boolean,
) {
    companion object {
        /** Default before the active Catalog has been resolved: hide every optional tab. */
        val Empty = LibraryTabVisibility(toRead = false, series = false, collections = false, annotations = false)

        /** Every optional tab present — mirrors what a full ABS Catalog exposes. */
        val All = LibraryTabVisibility(toRead = true, series = true, collections = true, annotations = true)
    }
}
