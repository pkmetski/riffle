package com.riffle.core.data

import com.riffle.core.catalog.CatalogPlaylist
import kotlinx.coroutines.flow.Flow

/**
 * Names of playlists that Riffle treats as internal wishlist surfaces and hides from the
 * user-facing Playlists tab / picker. "To Read" is created by Riffle's own To Read toggle;
 * "To Listen" is the audiobook equivalent surfaced by ABS. Users manage those through the
 * dedicated toggle/tab; exposing them in the general playlists picker would let a user
 * mutate them from two places with different semantics and would fork the find-by-name
 * invariant [ToReadRepository] relies on.
 */
val RESERVED_PLAYLIST_NAMES: Set<String> = setOf("To Read", "To Listen")

/** Raised by [PlaylistsRepository.createPlaylist] when the name is reserved for another surface. */
class ReservedPlaylistNameException(val name: String) : IllegalArgumentException("Playlist name '$name' is reserved")

/**
 * User-facing Playlists surface for a library, backed by the active Source's
 * [com.riffle.core.catalog.PlaylistsCapability].
 *
 * The "To Read" playlist ([TO_READ_PLAYLIST_NAME]) is deliberately filtered out of every
 * user-facing flow — it has its own dedicated affordance ([ToReadRepository]) and tab, and
 * exposing it here would let a user manipulate To Read from two places with different semantics.
 * The filter lives in exactly one place ([observePlaylists]); callers never see it, so a picker
 * or list can never accidentally leak the reserved playlist.
 */
interface PlaylistsRepository {
    /** Playlists for [rootId], with "To Read" filtered out. Empty until [refresh] populates the cache. */
    fun observePlaylists(rootId: String): Flow<List<CatalogPlaylist>>

    /** Fetch playlists for [rootId] from the active Source and refresh the in-memory cache. */
    suspend fun refresh(rootId: String): Boolean

    /** Full playlist ([CatalogPlaylist.itemIds] populated) by id. Null if not found on the Source. */
    suspend fun getPlaylist(rootId: String, playlistId: String): CatalogPlaylist?

    /**
     * Create a playlist named [name] scoped to [rootId], optionally seeded with [initialItemId].
     * Throws [ReservedPlaylistNameException] when [name] matches any [RESERVED_PLAYLIST_NAMES]
     * entry (case-insensitive) — those names are owned by the To Read / To Listen surfaces and a
     * duplicate would fork their find-by-name invariant.
     */
    suspend fun createPlaylist(rootId: String, name: String, initialItemId: String? = null): CatalogPlaylist

    suspend fun addItemToPlaylist(rootId: String, playlistId: String, itemId: String): Boolean

    suspend fun removeItemFromPlaylist(rootId: String, playlistId: String, itemId: String): Boolean
}
