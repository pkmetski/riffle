package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Per-server, device-local custom ordering of a server's libraries. Sibling to
 * [LibraryVisibilityPreferencesStore]: order is a personal display preference and is never synced.
 *
 * The stored list holds library ids in the user's chosen order. It may be partial — libraries not
 * present in it (e.g. newly synced ones) fall back to their natural (alphabetical) position after
 * the ordered ones, via [orderLibraries].
 */
interface LibraryOrderPreferencesStore {
    fun libraryOrder(serverId: String): Flow<List<String>>
    suspend fun setLibraryOrder(serverId: String, orderedIds: List<String>)
}
