@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.readium.r2.shared.publication.Locator

/**
 * Owns the server-win jump seam: the channel that carries remote-win Locators to the
 * EpubNavigatorFragment, the [suppressNextServerLocator] latch that lets an explicit openAtCfi
 * survive the first sync round, and the [pendingServerJumpStamp] that lets a resulting
 * onPositionChanged persist the adopted server timestamp instead of `now`.
 *
 * Split out of [PositionOrchestrator] as part of #376 to separate remote-win jump policy from the
 * canonical position stream and from the resume-restore watcher.
 */
class ServerJumpCoordinator {

    /**
     * Carries server-win jumps (sync cycle + Storyteller loop + background-return restore) to the
     * EpubNavigatorFragment. A conflated channel so a request survives until the screen's collector
     * receives it — a reopen can emit before the collector re-subscribes after a config change.
     */
    private val channel = Channel<Locator>(Channel.CONFLATED)
    val serverLocatorEvents: Flow<Locator> = channel.receiveAsFlow()

    /**
     * True when this reader was opened with an explicit openAtCfi (e.g. a library annotation tap or
     * a search-result jump). Consumed (and cleared) by [requestJumpWithSuppressCheck].
     */
    @Volatile private var suppressNextServerLocator: Boolean = false

    /**
     * Set to the winning server timestamp when the cycle drives a remote-win jump; consumed by the
     * jump's resulting onPositionChanged. That emission persists the CFI but keeps this server
     * timestamp instead of stamping `now`.
     */
    @Volatile private var pendingServerJumpStamp: Long? = null

    /**
     * Send [locator] through the server-locator channel unconditionally. Used by sync cycles that
     * want to jump the reader to a remote-win position.
     */
    fun requestJump(locator: Locator) {
        channel.trySend(locator)
    }

    /**
     * Variant used by the progressSyncController observer: honours [suppressNextServerLocator]
     * and clears it once consumed, so an explicit openAtCfi navigation is not overridden.
     */
    fun requestJumpWithSuppressCheck(locator: Locator) {
        if (suppressNextServerLocator) {
            suppressNextServerLocator = false
            return
        }
        channel.trySend(locator)
    }

    /**
     * Mark that the next server-locator event should be suppressed. Called from openBook when
     * openAtCfi is resolved, before the sync starts.
     */
    fun markSuppressNext() {
        suppressNextServerLocator = true
    }

    /** Called when [pendingServerJumpStamp] should be set from a sync cycle result. */
    fun setPendingStamp(stamp: Long) {
        pendingServerJumpStamp = stamp
    }

    /**
     * Consume and clear [pendingServerJumpStamp]. Called from the position intake right before it
     * persists a settled locator, so a remote-win jump adopts the server timestamp.
     */
    fun consumePendingStamp(): Long? {
        val stamp = pendingServerJumpStamp
        pendingServerJumpStamp = null
        return stamp
    }

    /** Reset all per-book state. Called from [PositionOrchestrator.bindBook]. */
    fun reset() {
        suppressNextServerLocator = false
        pendingServerJumpStamp = null
    }
}
