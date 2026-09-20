package com.riffle.feature.settings

import com.riffle.core.models.Library

data class LibraryUiItem(
    val library: Library,
    val isVisible: Boolean,
    val switchEnabled: Boolean,
)

/**
 * The list's library ids with positions [i] and [j] swapped — the new full order to hand
 * `SettingsViewModel.setLibraryOrder`.
 *
 * Shared by both settings screens so a move means the same thing on either platform. An
 * out-of-range target (the caller asked to move the first row up, or the last row down) returns
 * the order unchanged rather than throwing, so callers may render the buttons unconditionally.
 */
fun List<LibraryUiItem>.idsWithSwap(i: Int, j: Int): List<String> {
    val ids = map { it.library.id }
    if (i !in ids.indices || j !in ids.indices || i == j) return ids
    val swapped = ids.toMutableList()
    swapped[i] = ids[j]
    swapped[j] = ids[i]
    return swapped
}
