package com.riffle.app.feature.source.common

/**
 * Toolbar Search/X toggle: collapsing must also clear the query so the result set
 * returns to the full catalog instead of staying pinned to the last search.
 */
internal fun toggleSearchOpen(currentlyOpen: Boolean, clearQuery: () -> Unit): Boolean {
    if (currentlyOpen) clearQuery()
    return !currentlyOpen
}
