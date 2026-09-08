package com.riffle.core.data

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory To Read snapshot for a single library.
 *
 * `playlistId == null` means we know the server has no To Read playlist (so next add must create).
 * After [ToReadRepositoryImpl.refresh] succeeds, the snapshot reflects the server's state.
 */
private data class ToReadSnapshot(val playlistId: String?, val itemIds: Set<String>)

/**
 * Dual-path To Read repository. When the active Source's Catalog implements
 * [PlaylistsCapability] (ABS today), reads and writes hit that server-side playlist. Otherwise
 * (Local Files, Chitanka, any future backend-less Source) they fall back to [LocalToReadStore],
 * a plain Preferences DataStore. The rest of the app treats both cases identically — the tab and
 * the detail-page toggle work everywhere.
 */
class ToReadRepositoryImpl constructor(
    private val catalogRegistry: CatalogRegistry,
    private val localStore: LocalToReadStore,
    private val logger: Logger,
) : ToReadRepository {

    private val cache = MutableStateFlow<Map<String, ToReadSnapshot>>(emptyMap())

    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> =
        // Always union server-synced (cache) and locally-stored items. This ensures items added via
        // local-store fallback (when a source lacks readlist permissions) appear alongside items
        // confirmed by the server.
        cache.combine(localStore.observeItemIds(libraryId)) { cacheMap, localIds ->
            (cacheMap[libraryId]?.itemIds ?: emptySet()) + localIds
        }

    override suspend fun refresh(libraryId: String): Boolean {
        // Local backing has nothing to refresh — the DataStore IS the source of truth. Report
        // success so callers don't spin on false and think the source is offline.
        val cap = activePlaylistsCap() ?: return true
        val match = runCatching { cap.findPlaylist(libraryId, TO_READ_PLAYLIST_NAME) }
            .getOrElse {
                logger.d(LogChannel.ToRead) { "refresh($libraryId) findPlaylist failed: $it" }
                return false
            }
        val snapshot = ToReadSnapshot(
            playlistId = match?.id,
            itemIds = match?.itemIds?.toSet() ?: emptySet(),
        )
        cache.value = cache.value + (libraryId to snapshot)
        return true
    }

    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean =
        if (activePlaylistsCap() != null) {
            cache.value[libraryId]?.itemIds?.contains(libraryItemId) == true
        } else {
            localStore.isInToRead(libraryId, libraryItemId)
        }

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        val cap = activePlaylistsCap() ?: run {
            localStore.add(libraryId, libraryItemId)
            return true
        }
        return addWithCap(cap, libraryItemId, libraryId)
    }

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
        val cap = activePlaylistsCap() ?: run {
            localStore.remove(libraryId, libraryItemId)
            return true
        }
        return removeWithCap(cap, libraryItemId, libraryId)
    }

    override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean {
        val cap = (catalogRegistry.forSourceId(sourceId) as? PlaylistsCapability) ?: return true
        val match = runCatching { cap.findPlaylist(libraryId, TO_READ_PLAYLIST_NAME) }
            .getOrElse {
                logger.d(LogChannel.ToRead) { "refreshForSource($sourceId, $libraryId) findPlaylist failed: $it" }
                return false
            }
        val snapshot = ToReadSnapshot(
            playlistId = match?.id,
            itemIds = match?.itemIds?.toSet() ?: emptySet(),
        )
        cache.value = cache.value + (libraryId to snapshot)
        return true
    }

    override suspend fun isInToReadForSource(sourceId: String, libraryItemId: String, libraryId: String): Boolean {
        val cap = capForSource(sourceId)
        return if (cap != null) {
            cache.value[libraryId]?.itemIds?.contains(libraryItemId) == true
        } else {
            localStore.isInToRead(libraryId, libraryItemId)
        }
    }

    override suspend fun addToToReadForSource(sourceId: String, libraryItemId: String, libraryId: String): Boolean {
        val cap = capForSource(sourceId) ?: run {
            localStore.add(libraryId, libraryItemId)
            return true
        }
        return addWithCap(cap, libraryItemId, libraryId)
    }

    override suspend fun removeFromToReadForSource(sourceId: String, libraryItemId: String, libraryId: String): Boolean {
        val cap = capForSource(sourceId) ?: run {
            localStore.remove(libraryId, libraryItemId)
            return true
        }
        return removeWithCap(cap, libraryItemId, libraryId)
    }

    private suspend fun activePlaylistsCap(): PlaylistsCapability? {
        val catalog: Catalog = catalogRegistry.forActive() ?: return null
        return catalog as? PlaylistsCapability
    }

    private suspend fun capForSource(sourceId: String): PlaylistsCapability? =
        catalogRegistry.forSourceId(sourceId) as? PlaylistsCapability

    private suspend fun addWithCap(cap: PlaylistsCapability, libraryItemId: String, libraryId: String): Boolean {
        val before = cache.value[libraryId] ?: ToReadSnapshot(playlistId = null, itemIds = emptySet())
        cache.value = cache.value + (libraryId to before.copy(itemIds = before.itemIds + libraryItemId))
        val playlistId = before.playlistId
        val ok = if (playlistId == null) {
            runCatching {
                val created = cap.createPlaylist(libraryId, TO_READ_PLAYLIST_NAME, initialItemId = libraryItemId)
                cache.value = cache.value + (libraryId to ToReadSnapshot(created.id, before.itemIds + libraryItemId))
                true
            }.getOrElse {
                logger.d(LogChannel.ToRead) { "addWithCap($libraryId, $libraryItemId) createPlaylist failed: $it" }
                false
            }
        } else {
            runCatching { cap.addItemToPlaylist(playlistId, libraryItemId); true }.getOrElse { addErr ->
                logger.d(LogChannel.ToRead) { "addWithCap($libraryId, $libraryItemId) addItemToPlaylist failed, retrying via create: $addErr" }
                runCatching {
                    val created = cap.createPlaylist(libraryId, TO_READ_PLAYLIST_NAME, initialItemId = libraryItemId)
                    cache.value = cache.value + (libraryId to ToReadSnapshot(created.id, before.itemIds + libraryItemId))
                    true
                }.getOrElse { createErr ->
                    logger.d(LogChannel.ToRead) { "addWithCap($libraryId, $libraryItemId) recovery create failed: $createErr" }
                    false
                }
            }
        }
        if (!ok) {
            // Server rejected (e.g. readlist permissions not granted); fall back to local store so
            // the feature still works for this user. Keep the optimistic cache update.
            logger.d(LogChannel.ToRead) { "addWithCap($libraryId, $libraryItemId) server failed, persisting locally" }
            localStore.add(libraryId, libraryItemId)
            return true
        }
        return ok
    }

    private suspend fun removeWithCap(cap: PlaylistsCapability, libraryItemId: String, libraryId: String): Boolean {
        val before = cache.value[libraryId] ?: run {
            // No cache entry — item may still be in the local-store fallback; clean it up.
            localStore.remove(libraryId, libraryItemId)
            return true
        }
        if (libraryItemId !in before.itemIds) {
            // Not in server cache. Clean up any local-store entry (e.g. added via fallback while
            // cap was unavailable but cache was later populated without the item).
            localStore.remove(libraryId, libraryItemId)
            return true
        }
        val playlistId = before.playlistId
        if (playlistId == null) {
            // Item was added via local-store fallback (no server playlist) — remove locally.
            val remaining = before.itemIds - libraryItemId
            cache.value = cache.value + (libraryId to before.copy(itemIds = remaining))
            localStore.remove(libraryId, libraryItemId)
            return true
        }
        val remainingIds = before.itemIds - libraryItemId
        val optimistic = if (remainingIds.isEmpty()) {
            ToReadSnapshot(playlistId = null, itemIds = emptySet())
        } else {
            before.copy(itemIds = remainingIds)
        }
        cache.value = cache.value + (libraryId to optimistic)
        val ok = runCatching { cap.removeItemFromPlaylist(playlistId, libraryItemId); true }.getOrElse {
            logger.d(LogChannel.ToRead) { "removeWithCap($libraryId, $libraryItemId) failed: $it" }
            false
        }
        // Always remove from the local store regardless of server outcome. observeToReadItemIds
        // unions cache + localStore, so skipping this leaves items added via the fallback path
        // permanently visible even after the server successfully removes them.
        localStore.remove(libraryId, libraryItemId)
        if (!ok) {
            logger.d(LogChannel.ToRead) { "removeWithCap($libraryId, $libraryItemId) server failed, keeping optimistic remove" }
        }
        return true
    }
}
