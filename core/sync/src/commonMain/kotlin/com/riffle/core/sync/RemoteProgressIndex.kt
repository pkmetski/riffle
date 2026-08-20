package com.riffle.core.sync

/**
 * Enumerates items the sweep should reconcile beyond what the dirty-row ledger knows about.
 *
 * The dirty-row ledger only surfaces rows where `localUpdatedAt > lastSyncedAt`. For WebDAV-backed
 * sources a clean row (perfectly synced) will never be revisited even if a second device wrote a
 * newer position to the server. This index lets the sweep also reconcile those clean rows by
 * providing all remote items the server has, not just locally-dirty ones.
 *
 * Server sources (ABS, Komga) are not affected — their [ProgressRemote] implementations handle
 * full-library pulls via the [com.riffle.core.domain.ProgressPeerCapability] path; they never
 * appear in [sourcesWithRemote].
 */
interface RemoteProgressIndex {
    /** Source IDs for which this index has remote items to contribute. */
    suspend fun sourcesWithRemote(): List<String>
    /** itemIds whose ebook progress file exists on the remote for this source. */
    suspend fun remoteEbookItems(sourceId: String): List<String>
    /** itemIds whose audio progress file exists on the remote for this source. */
    suspend fun remoteAudioItems(sourceId: String): List<String>

    companion object {
        val EMPTY: RemoteProgressIndex = object : RemoteProgressIndex {
            override suspend fun sourcesWithRemote() = emptyList<String>()
            override suspend fun remoteEbookItems(sourceId: String) = emptyList<String>()
            override suspend fun remoteAudioItems(sourceId: String) = emptyList<String>()
        }
    }
}
