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
     * Return the playlist named [name] scoped to [rootId], with `itemIds` fully populated.
     * Sources whose native "server-wide list" model makes [listPlaylists] cheap can inherit the
     * default (list + filter by name). Sources where enumerating every playlist's items is
     * expensive (e.g. Komga readlists, which require one extra request per list to filter book
     * ids by library) should override this to avoid the O(N) blow-up.
     */
    suspend fun findPlaylist(rootId: String, name: String): CatalogPlaylist? =
        listPlaylists(rootId).firstOrNull { it.name == name }
}
