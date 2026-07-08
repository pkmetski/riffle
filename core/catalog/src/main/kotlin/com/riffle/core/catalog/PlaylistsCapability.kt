package com.riffle.core.catalog

interface PlaylistsCapability : CatalogCapability {
    suspend fun listPlaylists(rootId: String): List<CatalogPlaylist>
    suspend fun createPlaylist(rootId: String, name: String): CatalogPlaylist
    suspend fun addItemToPlaylist(playlistId: String, itemId: String)
    suspend fun removeItemFromPlaylist(playlistId: String, itemId: String)
}
