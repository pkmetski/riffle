package com.riffle.core.data

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.PlaylistsCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory To Read snapshot for a single library.
 *
 * `playlistId == null` means we know the server has no To Read playlist (so next add must create).
 * After [ToReadRepositoryImpl.refresh] succeeds, the snapshot reflects the server's state.
 */
private data class ToReadSnapshot(val playlistId: String?, val itemIds: Set<String>)

@Singleton
class ToReadRepositoryImpl @Inject constructor(
    private val catalogRegistry: CatalogRegistry,
) : ToReadRepository {

    private val cache = MutableStateFlow<Map<String, ToReadSnapshot>>(emptyMap())

    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> =
        cache.map { it[libraryId]?.itemIds ?: emptySet() }

    override suspend fun refresh(libraryId: String): Boolean {
        val cap = activePlaylistsCap() ?: return false
        val playlists = runCatching { cap.listPlaylists(libraryId) }.getOrElse { return false }
        val match = playlists.firstOrNull { it.name == TO_READ_PLAYLIST_NAME }
        val snapshot = ToReadSnapshot(
            playlistId = match?.id,
            itemIds = match?.itemIds?.toSet() ?: emptySet(),
        )
        cache.value = cache.value + (libraryId to snapshot)
        return true
    }

    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean =
        cache.value[libraryId]?.itemIds?.contains(libraryItemId) == true

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        val cap = activePlaylistsCap() ?: return false
        val before = cache.value[libraryId] ?: ToReadSnapshot(playlistId = null, itemIds = emptySet())
        // Optimistic update
        cache.value = cache.value + (libraryId to before.copy(itemIds = before.itemIds + libraryItemId))
        val playlistId = before.playlistId
        val ok = if (playlistId == null) {
            runCatching {
                val created = cap.createPlaylist(libraryId, TO_READ_PLAYLIST_NAME)
                cap.addItemToPlaylist(created.id, libraryItemId)
                cache.value = cache.value + (libraryId to ToReadSnapshot(created.id, before.itemIds + libraryItemId))
                true
            }.getOrDefault(false)
        } else {
            runCatching { cap.addItemToPlaylist(playlistId, libraryItemId); true }.getOrDefault(false)
        }
        if (!ok) cache.value = cache.value + (libraryId to before)
        return ok
    }

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
        val cap = activePlaylistsCap() ?: return false
        val before = cache.value[libraryId] ?: return true
        val playlistId = before.playlistId ?: return true
        if (libraryItemId !in before.itemIds) return true
        val remainingIds = before.itemIds - libraryItemId
        // Optimistic update. If we're removing the last item, ABS auto-deletes the playlist
        // server-side — drop our cached playlistId so the next addToToRead creates a fresh one.
        val optimistic = if (remainingIds.isEmpty()) {
            ToReadSnapshot(playlistId = null, itemIds = emptySet())
        } else {
            before.copy(itemIds = remainingIds)
        }
        cache.value = cache.value + (libraryId to optimistic)
        val ok = runCatching { cap.removeItemFromPlaylist(playlistId, libraryItemId); true }.getOrDefault(false)
        if (!ok) cache.value = cache.value + (libraryId to before)
        return ok
    }

    private suspend fun activePlaylistsCap(): PlaylistsCapability? {
        val catalog: Catalog = catalogRegistry.forActive() ?: return null
        return catalog as? PlaylistsCapability
    }
}
