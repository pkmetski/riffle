package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.NetworkResult
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Set-reconciler for audiobook bookmarks against Audiobookshelf (ADR 0030).
 *
 * Unlike positions (one value per item), bookmarks are a COLLECTION per (sourceId, itemId),
 * so this is a set-reconcile. ABS identity for a bookmark is (libraryItemId, time) where time
 * is INTEGER seconds — there is NO per-bookmark id, so we key local<->server matches on
 * `positionSec.roundToInt() == timeSec`.
 *
 * Policy: PUSH local intent first (creates / renames / deletes), then PULL the server set to
 * insert remote additions, accept remote renames onto clean rows, and remove rows deleted on
 * another device. Dirty rows (local intent pending) are never clobbered by the pull.
 */
class AudiobookBookmarkReconciler(
    private val dao: AudiobookBookmarkDao,
    private val api: AbsBookmarkApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    // Hilt can't satisfy the `() -> Long` / `() -> String` parameters (Dagger ignores Kotlin default
    // values), so the injected constructor omits them and delegates to the real implementations — the
    // established pattern in this codebase (e.g. AudiobookPlayerViewModel). Tests use the primary
    // constructor with deterministic fakes.
    @Inject
    constructor(dao: AudiobookBookmarkDao, api: AbsBookmarkApi) : this(
        dao,
        api,
        System::currentTimeMillis,
        { java.util.UUID.randomUUID().toString() },
    )

    suspend fun reconcile(
        sourceId: String,
        itemId: String,
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ) {
        // --- PUSH: send each dirty row's local intent. Capture ifStamp BEFORE the network call. ---
        val dirty = dao.allForItem(sourceId, itemId).filter { it.localUpdatedAt > it.lastSyncedAt }
        for (row in dirty) {
            val ifStamp = row.localUpdatedAt
            if (row.deleted) {
                val res = api.deleteBookmark(baseUrl, itemId, row.positionSec.roundToInt(), token, insecureAllowed)
                if (res is NetworkResult.Success) {
                    dao.hardDeleteIfUnchanged(row.id, ifLocalUpdatedAt = ifStamp)
                }
                // Any error -> leave the tombstone (retries next sweep).
            } else {
                val res = if (row.lastSyncedAt == 0L) {
                    api.createBookmark(baseUrl, itemId, row.positionSec.roundToInt(), row.title, token, insecureAllowed)
                } else {
                    api.updateBookmark(baseUrl, itemId, row.positionSec.roundToInt(), row.title, token, insecureAllowed)
                }
                if (res is NetworkResult.Success) {
                    dao.confirmPushedIfUnchanged(row.id, serverStamp = now(), ifLocalUpdatedAt = ifStamp)
                }
                // Any error -> leave dirty.
            }
        }

        // --- PULL: only if we can read the server. ---
        val listResult = api.listBookmarks(baseUrl, token, insecureAllowed)
        val serverBookmarks = (listResult as? NetworkResult.Success)?.value ?: return

        val serverForItem = serverBookmarks.filter { it.libraryItemId == itemId }
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
