package com.riffle.app.sync

import android.content.Context

/**
 * Schedules the durable playlist sweep. Thin facade over the shared [SyncScheduler].
 *
 * [sweepNow]: enqueue a one-shot CONNECTED-constrained sweep (coalesced via KEEP). Called on every
 * local To Read change so the updated list is pushed to WebDAV as soon as connectivity allows —
 * without waiting for an app restart or the next periodic tick.
 *
 * [ensurePeriodic]: register the 1-hour safety-net sweep. Called once at [com.riffle.app.RiffleApplication]
 * startup so playlists sync periodically even when the app is rarely foregrounded.
 */
object PlaylistSyncScheduler {

    private val impl = SyncScheduler(
        PlaylistSyncWorker::class.java,
        uniqueSweepTag = "playlist-sync-sweep",
        uniquePeriodicTag = "playlist-sync-sweep-periodic",
    )

    fun sweepNow(context: Context) = impl.sweepNow(context)

    fun ensurePeriodic(context: Context) = impl.ensurePeriodic(context)
}
