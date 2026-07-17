package com.riffle.app.feature.library

/**
 * Which optional Library-tab-bar entries are shown for the active Source. Home and All Books are
 * unconditional and not tracked here. Every other tab is visible iff its underlying data is
 * non-empty — no source-type or Catalog-capability gating.
 */
data class LibraryTabVisibility(
    val toRead: Boolean,
    val series: Boolean,
    val collections: Boolean,
    val annotations: Boolean,
    val playlists: Boolean = false,
) {
    companion object {
        /** Default before the library has settled: hide every optional tab. */
        val Empty = LibraryTabVisibility(toRead = false, series = false, collections = false, annotations = false, playlists = false)

        /** Every optional tab present — used as the "still loading" fallback in the UI. */
        val All = LibraryTabVisibility(toRead = true, series = true, collections = true, annotations = true, playlists = true)
    }
}
