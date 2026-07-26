package com.riffle.core.sync

/**
 * Enumerates the `(sourceId, itemId)` pairs with at least one dirty annotation row
 * (`updatedAt > lastSyncedAt`) for the annotation sweep to push (#321, ADR 0036).
 */
fun interface DirtyAnnotationLedger {
    suspend fun dirtySourceItems(): List<DirtySourceItem>

    data class DirtySourceItem(val sourceId: String, val itemId: String)
}
