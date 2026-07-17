package com.riffle.core.data

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlists for a library, cached in-memory. The cache is per-rootId — the same source-wide
 * capability serves every root, but we never coalesce results across roots because ABS scopes
 * playlists per-library.
 */
@Singleton
class PlaylistsRepositoryImpl @Inject constructor(
    private val catalogRegistry: CatalogRegistry,
    private val logger: Logger,
) : PlaylistsRepository {

    private val cache = MutableStateFlow<Map<String, List<CatalogPlaylist>>>(emptyMap())

    override fun observePlaylists(rootId: String): Flow<List<CatalogPlaylist>> =
        // Reserved-name filter is applied here, once. Every consumer — the Playlists tab, the
        // playlist detail screen, and the "Add to playlist…" picker — reads through this flow, so
        // "To Read" / "To Listen" cannot leak into a user-facing surface even if a future caller
        // forgets the rule.
        cache.map { byRoot -> byRoot[rootId].orEmpty().filterNot { it.isReservedName() } }

    override suspend fun refresh(rootId: String): Boolean {
        val cap = activePlaylistsCap() ?: run {
            // No capability on the active Source — treat as empty rather than error so the UI
            // (which drives tab visibility off this flow) simply hides the tab.
            cache.value = cache.value + (rootId to emptyList())
            return true
        }
        return runCatching { cap.listPlaylists(rootId) }
            .onSuccess { cache.value = cache.value + (rootId to it) }
            .onFailure { logger.d(LogChannel.Playlists) { "refresh($rootId) failed: $it" } }
            .isSuccess
    }

    override suspend fun getPlaylist(rootId: String, playlistId: String): CatalogPlaylist? {
        val cap = activePlaylistsCap() ?: return null
        // findPlaylist is by-name only; we want by-id and need itemIds populated. listPlaylists
        // on ABS returns full itemIds already; if we ever move to a summary-only source we'll
        // need a per-id fetch here.
        return runCatching { cap.listPlaylists(rootId).firstOrNull { it.id == playlistId } }
            .onFailure { logger.d(LogChannel.Playlists) { "getPlaylist($rootId, $playlistId) failed: $it" } }
            .getOrNull()
    }

    override suspend fun createPlaylist(
        rootId: String,
        name: String,
        initialItemId: String?,
    ): CatalogPlaylist {
        if (RESERVED_PLAYLIST_NAMES.any { it.equals(name.trim(), ignoreCase = true) }) {
            throw ReservedPlaylistNameException(name)
        }
        val cap = activePlaylistsCap()
            ?: throw IllegalStateException("Active Source has no PlaylistsCapability")
        val created = cap.createPlaylist(rootId, name.trim(), initialItemId)
        cache.value = cache.value + (rootId to (cache.value[rootId].orEmpty() + created))
        return created
    }

    override suspend fun addItemToPlaylist(rootId: String, playlistId: String, itemId: String): Boolean {
        val cap = activePlaylistsCap() ?: return false
        val before = cache.value[rootId].orEmpty()
        val target = before.firstOrNull { it.id == playlistId } ?: return false
        if (itemId in target.itemIds) return true
        val updated = target.copy(
            itemIds = target.itemIds + itemId,
            bookCount = target.bookCount + 1,
        )
        cache.value = cache.value + (rootId to before.map { if (it.id == playlistId) updated else it })
        return runCatching { cap.addItemToPlaylist(playlistId, itemId); true }
            .onFailure {
                logger.d(LogChannel.Playlists) { "addItemToPlaylist($playlistId, $itemId) failed: $it" }
                cache.value = cache.value + (rootId to before)
            }
            .getOrDefault(false)
    }

    override suspend fun removeItemFromPlaylist(rootId: String, playlistId: String, itemId: String): Boolean {
        val cap = activePlaylistsCap() ?: return false
        val before = cache.value[rootId].orEmpty()
        val target = before.firstOrNull { it.id == playlistId } ?: return false
        if (itemId !in target.itemIds) return true
        val remaining = target.itemIds - itemId
        val updated = target.copy(
            itemIds = remaining,
            bookCount = (target.bookCount - 1).coerceAtLeast(0),
        )
        // ABS auto-deletes an emptied playlist server-side. Mirror that locally so the tab hides
        // once the last item is removed, without waiting for the next refresh.
        val next = if (remaining.isEmpty()) {
            before.filterNot { it.id == playlistId }
        } else {
            before.map { if (it.id == playlistId) updated else it }
        }
        cache.value = cache.value + (rootId to next)
        return runCatching { cap.removeItemFromPlaylist(playlistId, itemId); true }
            .onFailure {
                logger.d(LogChannel.Playlists) { "removeItemFromPlaylist($playlistId, $itemId) failed: $it" }
                cache.value = cache.value + (rootId to before)
            }
            .getOrDefault(false)
    }

    private suspend fun activePlaylistsCap(): PlaylistsCapability? {
        val catalog: Catalog = catalogRegistry.forActive() ?: return null
        return catalog as? PlaylistsCapability
    }

    private fun CatalogPlaylist.isReservedName(): Boolean =
        RESERVED_PLAYLIST_NAMES.any { it.equals(name.trim(), ignoreCase = true) }
}
