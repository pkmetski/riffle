package com.riffle.app.feature.library

/**
 * Which optional Library-tab-bar entries are shown for the active Source. Home, Annotations and
 * All Books are unconditional and not tracked here. Populated by [LibraryItemsViewModel] from
 * the active Catalog's capabilities (issue #439 / ADR 0041).
 */
data class LibraryTabVisibility(
    val toRead: Boolean,
    val series: Boolean,
    val collections: Boolean,
) {
    companion object {
        /** Default before the active Catalog has been resolved: hide every optional tab. */
        val Empty = LibraryTabVisibility(toRead = false, series = false, collections = false)

        /** Every optional tab present — mirrors what a full ABS Catalog exposes. */
        val All = LibraryTabVisibility(toRead = true, series = true, collections = true)
    }
}
