package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey

// Tracks when a web-source item's persisted detail was last successfully refetched.
// Powers the WebSourceItemGate freshness check (ADR 0043): within TTL → serve the
// existing library_items row without hitting the network; expired + fetch fails →
// serve the stale row as an offline fallback. Only web sources write here (chitanka
// today, Gutenberg etc. later); ABS/Storyteller have their own sync semantics.
@Entity(
    tableName = "remote_item_freshness",
    primaryKeys = ["sourceId", "sourceItemId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RemoteItemFreshnessEntity(
    val sourceId: String,
    val sourceItemId: String,
    val lastFetchedAt: Long,
)
