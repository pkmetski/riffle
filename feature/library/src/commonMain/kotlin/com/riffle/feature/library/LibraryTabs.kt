package com.riffle.feature.library

/**
 * The Library tab bar's index vocabulary and visibility rules.
 *
 * Android (`app/.../LibraryItemsScreen.kt`) and iOS (`shared/.../LibraryItemsScreen.kt`) each
 * carried a private, byte-identical copy of these four functions — including the index literals
 * the doc comments call "the single source of truth". Identical copies drift the moment one tab
 * moves, and the tab switch that renders content and the tab bar that draws the chips read from
 * different files. They live here once now; both screens call these.
 */

/**
 * Index of the Annotations tab — the 6th tab, positioned between "To Read" (1) and "Series" (3).
 * Referenced by both the tab bar's selected-check and the content `when (selectedTab)`.
 */
fun tabIndexForAnnotations(): Int = 2

/**
 * Index of the Playlists tab — the 7th tab, positioned between Collections (4) and All Books (5).
 * Only visible on ABS audiobook roots ([LibraryTabVisibility.playlists] gate).
 */
fun tabIndexForPlaylists(): Int = 6

/**
 * True when the tab-clamp effect should reset the selected tab to Home.
 *
 * Returns false while the user is searching ([searchQuery] non-empty) — `LibraryFilterEngine`
 * filters `projection.series/collections` by the active query, so an unmatched search would
 * otherwise flip a visibility flag off, clamp the tab, and the clamp would survive clearing the
 * query. Also false while [visibility] is null (still resolving) so a restored tab survives the
 * initial load window.
 */
fun shouldClampSelectedTab(
    searchQuery: String,
    visibility: LibraryTabVisibility?,
    selectedTab: Int,
): Boolean {
    if (searchQuery.isNotEmpty()) return false
    if (visibility == null) return false
    return !isTabVisible(selectedTab, visibility)
}

/**
 * True when the tab at [selectedTab] still has data to show under the current [visibility].
 * Home (0) and All Books (5) are unconditional.
 */
fun isTabVisible(selectedTab: Int, visibility: LibraryTabVisibility): Boolean =
    when (selectedTab) {
        1 -> visibility.toRead
        tabIndexForAnnotations() -> visibility.annotations
        3 -> visibility.series
        4 -> visibility.collections
        tabIndexForPlaylists() -> visibility.playlists
        else -> true
    }
