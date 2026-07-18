package com.riffle.core.data

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.database.PlaylistDao
import com.riffle.core.database.PlaylistEntity
import com.riffle.core.database.PlaylistItemEntity
import com.riffle.core.domain.SourceRepository
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlists for a library, persisted to Room so the Playlists tab is populated on cold start
 * without waiting for [refresh]. Reads are scoped by rootId (the library id); writes fan out to
 * (rootId, sourceId) via the active [SourceRepository] entry. The rootId-only observation
 * matches the pre-Room contract — the same rootId across two sources would still coalesce, but
 * that only happens when a user has two Source rows pointing at the same ABS instance.
 */
@Singleton
class PlaylistsRepositoryImpl @Inject constructor(
    private val catalogRegistry: CatalogRegistry,
    private val sourceRepository: SourceRepository,
    private val dao: PlaylistDao,
    private val logger: Logger,
) : PlaylistsRepository {

    override fun observePlaylists(rootId: String): Flow<List<CatalogPlaylist>> =
        // Reserved-name filter is applied here, once. Every consumer — the Playlists tab, the
        // playlist detail screen, and the "Add to playlist…" picker — reads through this flow, so
        // "To Read" / "To Listen" cannot leak into a user-facing surface even if a future caller
        // forgets the rule. itemIds hydrated per-playlist so the picker/detail surfaces see the
        // same shape they did under the in-memory cache.
        dao.observeByRootId(rootId).map { entities ->
            entities
                .map { it.toDomain(itemIds = dao.itemIds(it.sourceId, it.id)) }
                .filterNot { it.isReservedName() }
        }

    override suspend fun refresh(rootId: String): Boolean {
        val sourceId = sourceRepository.getActive()?.id
        val cap = activePlaylistsCap() ?: run {
            // No capability on the active Source — treat as empty rather than error so the UI
            // (which drives tab visibility off this flow) simply hides the tab. Clear any stale
            // rows so a source switch away from an ABS instance doesn't leave playlists visible.
            if (sourceId != null) dao.replaceAllForRoot(rootId, emptyList(), emptyList())
            return true
        }
        if (sourceId == null) return false
        return runCatching { cap.listPlaylists(rootId) }
            .onSuccess { list -> dao.replaceAllForRoot(rootId, list.toEntities(sourceId), list.toItemEntities(sourceId)) }
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
        val sourceId = sourceRepository.getActive()?.id
            ?: throw IllegalStateException("No active Source")
        val created = cap.createPlaylist(rootId, name.trim(), initialItemId)
        dao.replacePlaylist(
            playlist = created.toEntity(sourceId),
            items = created.toItemEntities(sourceId),
        )
        return created
    }

    override suspend fun addItemToPlaylist(rootId: String, playlistId: String, itemId: String): Boolean {
        val cap = activePlaylistsCap() ?: return false
        val sourceId = sourceRepository.getActive()?.id ?: return false
        // Parent row required: mutating without it would either write a blank-titled row on the
        // next replacePlaylist (violating the name invariant) or dangle join rows against a
        // missing FK. Missing parent means the caller raced with a source-removal / cache-purge;
        // safest is to no-op and let the next refresh() rebuild the row cleanly.
        val parent = dao.getById(sourceId, playlistId) ?: return false
        val current = dao.itemIds(sourceId, playlistId)
        if (itemId in current) return true
        return runCatching { cap.addItemToPlaylist(playlistId, itemId); true }
            .onSuccess {
                val updatedItems = (current + itemId)
                    .mapIndexed { index, id -> PlaylistItemEntity(playlistId, sourceId, id, index) }
                dao.replacePlaylist(
                    playlist = parent.copy(bookCount = updatedItems.size),
                    items = updatedItems,
                )
            }
            .onFailure { logger.d(LogChannel.Playlists) { "addItemToPlaylist($playlistId, $itemId) failed: $it" } }
            .getOrDefault(false)
    }

    override suspend fun removeItemFromPlaylist(rootId: String, playlistId: String, itemId: String): Boolean {
        val cap = activePlaylistsCap() ?: return false
        val sourceId = sourceRepository.getActive()?.id ?: return false
        val parent = dao.getById(sourceId, playlistId) ?: return false
        val current = dao.itemIds(sourceId, playlistId)
        if (itemId !in current) return true
        return runCatching { cap.removeItemFromPlaylist(playlistId, itemId); true }
            .onSuccess {
                val remaining = current - itemId
                if (remaining.isEmpty()) {
                    // ABS auto-deletes an emptied playlist server-side. Mirror that locally so the
                    // tab hides once the last item is removed, without waiting for the next refresh.
                    dao.deletePlaylistItems(sourceId, playlistId)
                    dao.deletePlaylist(sourceId, playlistId)
                } else {
                    val items = remaining.mapIndexed { i, id -> PlaylistItemEntity(playlistId, sourceId, id, i) }
                    dao.replacePlaylist(
                        playlist = parent.copy(bookCount = items.size),
                        items = items,
                    )
                }
            }
            .onFailure { logger.d(LogChannel.Playlists) { "removeItemFromPlaylist($playlistId, $itemId) failed: $it" } }
            .getOrDefault(false)
    }

    private suspend fun activePlaylistsCap(): PlaylistsCapability? {
        val catalog: Catalog = catalogRegistry.forActive() ?: return null
        return catalog as? PlaylistsCapability
    }

    private fun CatalogPlaylist.isReservedName(): Boolean =
        RESERVED_PLAYLIST_NAMES.any { it.equals(name.trim(), ignoreCase = true) }
}

private fun CatalogPlaylist.toEntity(sourceId: String) = PlaylistEntity(
    id = id,
    sourceId = sourceId,
    rootId = rootId,
    name = name,
    bookCount = bookCount,
)

private fun CatalogPlaylist.toItemEntities(sourceId: String): List<PlaylistItemEntity> =
    itemIds.mapIndexed { index, itemId -> PlaylistItemEntity(id, sourceId, itemId, index) }

private fun List<CatalogPlaylist>.toEntities(sourceId: String) = map { it.toEntity(sourceId) }
private fun List<CatalogPlaylist>.toItemEntities(sourceId: String) = flatMap { it.toItemEntities(sourceId) }

private fun PlaylistEntity.toDomain(itemIds: List<String>) = CatalogPlaylist(
    id = id,
    rootId = rootId,
    name = name,
    bookCount = bookCount,
    itemIds = itemIds,
)
