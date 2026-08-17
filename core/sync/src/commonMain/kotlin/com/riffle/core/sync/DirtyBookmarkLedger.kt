package com.riffle.core.sync

/** Enumerates the (source, item) pairs with at least one dirty audiobook-bookmark row. */
interface DirtyBookmarkLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyItems(sourceId: String): List<String>
}
