package com.riffle.core.data

import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Set-reconciler for audiobook bookmarks against the Source's [BookmarksCapability] (ADR 0030).
 *
 * Unlike positions (one value per item), bookmarks are a COLLECTION per (sourceId, itemId), so
 * this is a set-reconcile. Source identity for a bookmark is (itemId, time) where time is INTEGER
 * seconds — there is NO per-bookmark id, so we key local<->server matches on
 * `positionSec.roundToInt() == timeSec`.
 *
 * Policy: PUSH local intent first (creates / renames / deletes), then PULL the server set to
 * insert remote additions, accept remote renames onto clean rows, and remove rows deleted on
 * another device. Dirty rows (local intent pending) are never clobbered by the pull.
 */
class AudiobookBookmarkReconciler(
    private val dao: AudiobookBookmarkDao,
    private val catalogRegistry: CatalogRegistry,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    // Hilt can't satisfy the `() -> Long` / `() -> String` parameters (Dagger ignores Kotlin default
    // values), so the injected constructor omits them and delegates to the real implementations — the
    // established pattern in this codebase (e.g. AudiobookPlayerViewModel). Tests use the primary
    // constructor with deterministic fakes.
    @Inject
    constructor(dao: AudiobookBookmarkDao, catalogRegistry: CatalogRegistry) : this(
        dao,
        catalogRegistry,
        System::currentTimeMillis,
        { java.util.UUID.randomUUID().toString() },
    )

    suspend fun reconcile(sourceId: String, itemId: String) {
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return
        val cap = catalog as? BookmarksCapability ?: return

        // --- PUSH: send each dirty row's local intent. Capture ifStamp BEFORE the network call. ---
        val dirty = dao.allForItem(sourceId, itemId).filter { it.localUpdatedAt > it.lastSyncedAt }
        for (row in dirty) {
            val ifStamp = row.localUpdatedAt
            if (row.deleted) {
                val ok = runCatching { cap.deleteBookmark(itemId, row.positionSec.roundToInt()) }.isSuccess
                if (ok) dao.hardDeleteIfUnchanged(row.id, ifLocalUpdatedAt = ifStamp)
                // Any error -> leave the tombstone (retries next sweep).
            } else {
                val ok = runCatching {
                    if (row.lastSyncedAt == 0L) cap.createBookmark(itemId, row.positionSec.roundToInt(), row.title)
                    else cap.renameBookmark(itemId, row.positionSec.roundToInt(), row.title)
                }.isSuccess
                if (ok) dao.confirmPushedIfUnchanged(row.id, serverStamp = now(), ifLocalUpdatedAt = ifStamp)
                // Any error -> leave dirty.
            }
        }

        // --- PULL: only if we can read the server. ---
        val remoteAll = runCatching { cap.listAllBookmarks() }.getOrNull() ?: return
        val serverForItem = remoteAll.filter { it.itemId == itemId }
        val serverTimes = serverForItem.map { it.timeSec }.toSet()
        // Re-read local AFTER push so confirmed deletes are already gone.
        val local = dao.allForItem(sourceId, itemId)

        // Insert / update from server.
        for (sb in serverForItem) {
            val atTime = local.firstOrNull { it.positionSec.roundToInt() == sb.timeSec }
            when {
                atTime == null -> dao.upsert(
                    AudiobookBookmarkEntity(
                        id = newId(),
                        sourceId = sourceId,
                        itemId = itemId,
                        positionSec = sb.timeSec.toDouble(),
                        title = sb.title,
                        createdAt = sb.createdAt,
                        localUpdatedAt = now(),
                        lastSyncedAt = now(),
                        deleted = false,
                    ),
                )
                atTime.localUpdatedAt <= atTime.lastSyncedAt && !atTime.deleted && atTime.title != sb.title ->
                    dao.upsert(atTime.copy(title = sb.title, localUpdatedAt = now(), lastSyncedAt = now()))
                // else: dirty or deleted-tombstone -> SKIP (local intent pending).
            }
        }

        // Remove locally-stale: clean, non-deleted rows whose time is absent server-side
        // were deleted on another device.
        for (lb in local) {
            if (!lb.deleted && lb.localUpdatedAt <= lb.lastSyncedAt && lb.positionSec.roundToInt() !in serverTimes) {
                dao.hardDelete(lb.id)
            }
        }
    }
}
