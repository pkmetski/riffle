package com.riffle.core.sync

import com.riffle.core.common.Clock
import com.riffle.core.common.RandomProvider
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.SyncableAudiobookBookmark
import kotlin.math.roundToInt

/**
 * Set-reconciler for audiobook bookmarks against the Source's [BookmarkRemote] (ADR 0030).
 *
 * Policy: PUSH local intent first (creates / renames / deletes), then PULL the server set to
 * insert remote additions, accept remote renames onto clean rows, and remove rows deleted on
 * another device. Dirty rows (local intent pending) are never clobbered by the pull.
 */
class AudiobookBookmarkReconciler(
    private val store: AudiobookBookmarkSyncStore,
    private val sourceResolver: SyncSourceResolver,
    private val clock: Clock,
    private val random: RandomProvider,
) {
    suspend fun reconcile(sourceId: String, itemId: String) {
        val remote = sourceResolver.resolve(sourceId)?.bookmarks ?: return

        val dirty = store.allForItemIncludingDeleted(sourceId, itemId)
            .filter { it.localUpdatedAt > it.lastSyncedAt }
        for (row in dirty) {
            val ifStamp = row.localUpdatedAt
            if (row.deleted) {
                val ok = runCatching { remote.delete(itemId, row.positionSec.roundToInt()) }.isSuccess
                if (ok) store.hardDeleteIfUnchanged(row.id, ifLocalUpdatedAt = ifStamp)
            } else {
                val ok = runCatching {
                    if (row.lastSyncedAt == 0L) {
                        remote.create(itemId, row.positionSec.roundToInt(), row.title)
                    } else {
                        remote.rename(itemId, row.positionSec.roundToInt(), row.title)
                    }
                }.isSuccess
                if (ok) {
                    store.confirmPushedIfUnchanged(
                        row.id,
                        serverStamp = clock.nowMs(),
                        ifLocalUpdatedAt = ifStamp,
                    )
                }
            }
        }

        val remoteAll = runCatching { remote.listAll() }.getOrNull() ?: return
        val serverForItem = remoteAll.filter { it.itemId == itemId }
        val serverTimes = serverForItem.map { it.timeSec }.toSet()
        val local = store.allForItemIncludingDeleted(sourceId, itemId)

        for (sb in serverForItem) {
            val atTime = local.firstOrNull { it.positionSec.roundToInt() == sb.timeSec }
            when {
                atTime == null -> store.upsert(
                    SyncableAudiobookBookmark(
                        id = random.newId(), sourceId = sourceId, itemId = itemId,
                        positionSec = sb.timeSec.toDouble(), title = sb.title,
                        createdAt = sb.createdAt,
                        localUpdatedAt = clock.nowMs(),
                        lastSyncedAt = clock.nowMs(),
                        deleted = false,
                    ),
                )
                atTime.localUpdatedAt <= atTime.lastSyncedAt && !atTime.deleted && atTime.title != sb.title ->
                    store.upsert(
                        atTime.copy(
                            title = sb.title,
                            localUpdatedAt = clock.nowMs(),
                            lastSyncedAt = clock.nowMs(),
                        ),
                    )
            }
        }

        for (lb in local) {
            if (!lb.deleted && lb.localUpdatedAt <= lb.lastSyncedAt && lb.positionSec.roundToInt() !in serverTimes) {
                store.hardDelete(lb.id)
            }
        }
    }
}
