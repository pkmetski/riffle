package com.riffle.core.data.websource

import com.riffle.core.database.RemoteItemFreshnessDao
import com.riffle.core.database.RemoteItemFreshnessEntity
import com.riffle.core.domain.Clock
import javax.inject.Inject

/**
 * Records when a web-source item's persisted detail was last successfully refetched
 * (ADR 0043). Used by [WebSourceItemGate] to short-circuit item-open flows within TTL
 * and to skip stamping when a fetch fails so the next open retries.
 *
 * TTL semantics are the caller's — the service just answers "how old is this row".
 */
class RemoteItemFreshness @Inject constructor(
    private val dao: RemoteItemFreshnessDao,
    private val clock: Clock,
) {

    /**
     * True when a stamp exists for `(sourceId, sourceItemId)` and it was written
     * less than [ttlMs] ago.
     */
    suspend fun withinTtl(sourceId: String, sourceItemId: String, ttlMs: Long): Boolean {
        val stamped = dao.lastFetchedAt(sourceId, sourceItemId) ?: return false
        return clock.nowMs() - stamped < ttlMs
    }

    /** Record a successful refetch. Upsert-replaces any existing row. */
    suspend fun stamp(sourceId: String, sourceItemId: String) {
        dao.upsert(
            RemoteItemFreshnessEntity(
                sourceId = sourceId,
                sourceItemId = sourceItemId,
                lastFetchedAt = clock.nowMs(),
            )
        )
    }

    /** Drop the stamp so the next open forces a refetch. Used by pull-to-refresh. */
    suspend fun clear(sourceId: String, sourceItemId: String) {
        dao.clear(sourceId, sourceItemId)
    }
}
