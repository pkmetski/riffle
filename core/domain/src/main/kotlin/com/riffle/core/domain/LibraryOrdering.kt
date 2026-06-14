package com.riffle.core.domain

/**
 * Applies a saved per-server [orderedIds] preference to [libraries]: libraries named in
 * [orderedIds] come first, in that order; any library not named (e.g. newly synced) keeps its
 * natural position by being appended afterwards, preserving the incoming relative order.
 *
 * Ids in [orderedIds] that no longer match a library are skipped.
 */
fun orderLibraries(libraries: List<Library>, orderedIds: List<String>): List<Library> {
    if (orderedIds.isEmpty()) return libraries
    val byId = libraries.associateBy { it.id }
    val orderedSet = orderedIds.toHashSet()
    val ordered = orderedIds.mapNotNull { byId[it] }
    val remaining = libraries.filterNot { it.id in orderedSet }
    return ordered + remaining
}
