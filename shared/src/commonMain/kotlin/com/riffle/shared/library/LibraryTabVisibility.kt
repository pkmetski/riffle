package com.riffle.shared.library

/**
 * Which optional Library-tab-bar entries are shown for the active Source. Home and All Books are
 * unconditional and not tracked here. Every other tab is visible iff its underlying data is
 * non-empty.
 */
data class LibraryTabVisibility(
    val toRead: Boolean,
    val series: Boolean,
    val collections: Boolean,
    val annotations: Boolean,
    val playlists: Boolean = false,
) {
    companion object {
        val Empty = LibraryTabVisibility(toRead = false, series = false, collections = false, annotations = false, playlists = false)
        val All = LibraryTabVisibility(toRead = true, series = true, collections = true, annotations = true, playlists = true)
    }
}
