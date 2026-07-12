package com.riffle.core.catalog

interface PlaylistsCapability : CatalogCapability {
    suspend fun listPlaylists(rootId: String): List<CatalogPlaylist>

    /**
     * Create a playlist named [name] scoped to [rootId]. If [initialItemId] is non-null, the
     * returned playlist already contains that item (single request on sources that support it —
     * ABS's `POST /playlists` takes an initial bookId, Komga's `POST /readlists` REQUIRES at least
     * one bookId in the request body, so this parameter is how a caller sidesteps a mandatory
     * seed on backends that don't allow empty lists).
     */
    suspend fun createPlaylist(
        rootId: String,
        name: String,
        initialItemId: String? = null,
    ): CatalogPlaylist
    suspend fun addItemToPlaylist(playlistId: String, itemId: String)
    suspend fun removeItemFromPlaylist(playlistId: String, itemId: String)

    /**
     * Return the playlist named [name] scoped to [rootId], with `itemIds` FULLY populated. This
     * is deliberately abstract (no default) — an earlier version defaulted to
     * `listPlaylists(rootId).firstOrNull { it.name == name }`, which is broken for any source
     * that treats `listPlaylists` as a summary-only projection (e.g. Komga, where enumerating
     * every readlist's per-library bookIds is an extra HTTP round-trip per list and
     * [listPlaylists] deliberately returns `itemIds = emptyList()`). A source that copies that
     * optimisation without overriding [findPlaylist] would ship a broken To-Read sync with a
     * green build. Making this abstract puts the summary/full distinction in the type system so
     * the compiler enforces the decision.
     */
    suspend fun findPlaylist(rootId: String, name: String): CatalogPlaylist?
}
