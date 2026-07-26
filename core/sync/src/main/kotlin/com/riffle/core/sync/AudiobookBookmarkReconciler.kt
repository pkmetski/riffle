package com.riffle.core.sync

import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.SyncableAudiobookBookmark
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Set-reconciler for audiobook bookmarks against the Source's [BookmarksCapability] (ADR 0030).
 *
 * Policy: PUSH local intent first (creates / renames / deletes), then PULL the server set to
 * insert remote additions, accept remote renames onto clean rows, and remove rows deleted on
 * another device. Dirty rows (local intent pending) are never clobbered by the pull.
 */
class AudiobookBookmarkReconciler(
    private val store: AudiobookBookmarkSyncStore,
    private val catalogRegistry: CatalogRegistry,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    @Inject
    constructor(store: AudiobookBookmarkSyncStore, catalogRegistry: CatalogRegistry) : this(
        store,
        catalogRegistry,
        System::currentTimeMillis,
        { java.util.UUID.randomUUID().toString() },
    )

    suspend fun reconcile(sourceId: String, itemId: String) {
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return
        val cap = catalog as? BookmarksCapability ?: return

        val dirty = store.allForItemIncludingDeleted(sourceId, itemId)
            .filter { it.localUpdatedAt > it.lastSyncedAt }
        for (row in dirty) {
            val ifStamp = row.localUpdatedAt
            if (row.deleted) {
                val ok = runCatching { cap.deleteBookmark(itemId, row.positionSec.roundToInt()) }.isSuccess
                if (ok) store.hardDeleteIfUnchanged(row.id, ifLocalUpdatedAt = ifStamp)
            } else {
                val ok = runCatching {
                    if (row.lastSyncedAt == 0L) cap.createBookmark(itemId, row.positionSec.roundToInt(), row.title)
                    else cap.renameBookmark(itemId, row.positionSec.roundToInt(), row.title)
                }.isSuccess
                if (ok) store.confirmPushedIfUnchanged(row.id, serverStamp = now(), ifLocalUpdatedAt = ifStamp)
            }
        }

        val remoteAll = runCatching { cap.listAllBookmarks() }.getOrNull() ?: return
        val serverForItem = remoteAll.filter { it.itemId == itemId }
        val serverTimes = serverForItem.map { it.timeSec }.toSet()
        val local = store.allForItemIncludingDeleted(sourceId, itemId)

        for (sb in serverForItem) {
            val atTime = local.firstOrNull { it.positionSec.roundToInt() == sb.timeSec }
            when {
                atTime == null -> store.upsert(
                    SyncableAudiobookBookmark(
                        id = newId(), sourceId = sourceId, itemId = itemId,
                        positionSec = sb.timeSec.toDouble(), title = sb.title,
                        createdAt = sb.createdAt, localUpdatedAt = now(), lastSyncedAt = now(),
                        deleted = false,
                    ),
                )
                atTime.localUpdatedAt <= atTime.lastSyncedAt && !atTime.deleted && atTime.title != sb.title ->
                    store.upsert(atTime.copy(title = sb.title, localUpdatedAt = now(), lastSyncedAt = now()))
            }
        }

        for (lb in local) {
            if (!lb.deleted && lb.localUpdatedAt <= lb.lastSyncedAt && lb.positionSec.roundToInt() !in serverTimes) {
                store.hardDelete(lb.id)
            }
        }
    }
}
