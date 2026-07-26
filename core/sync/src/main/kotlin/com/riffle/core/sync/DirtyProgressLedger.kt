package com.riffle.core.sync

/** Enumerates the dirty position rows (localUpdatedAt > lastSyncedAt) the sweep must reconcile. */
interface DirtyProgressLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyEbookItems(sourceId: String): List<String>
    suspend fun dirtyAudioItems(sourceId: String): List<String>
}
