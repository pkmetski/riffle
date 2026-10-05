package com.riffle.core.data

import com.riffle.core.common.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

/**
 * NSDate-backed millisecond clock for the default [IosLocalToReadStore.clock] parameter.
 * Uses NSDate for sub-second precision (important for last-write-wins timestamp comparisons)
 * rather than [IosSystemClock] which truncates to whole seconds.
 */
private object IosMillisClock : Clock {
    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
    override fun nowNs(): Long = (NSDate().timeIntervalSince1970 * 1_000_000_000.0).toLong()
}

/**
 * NSUserDefaults-backed [LocalToReadStore] for iOS. Each library's item IDs are persisted as a
 * string array; the timestamp is a Double (milliseconds) stored alongside it.
 *
 * [suiteName] is injectable for test isolation (each test uses a unique suite so NSUserDefaults
 * entries don't bleed across test cases). Production callers pass the default `"com.riffle.toread"`.
 *
 * Observe pattern: a MutableStateFlow is created on first access (lazily per libraryId) and
 * pre-seeded from NSUserDefaults so observers always see up-to-date state without polling.
 *
 * Thread safety: [flows] is guarded by [mutex] because [PlaylistSweep] and the UI may call
 * [observeItemIds] concurrently from different coroutines.
 */
class IosLocalToReadStore(
    private val suiteName: String = "com.riffle.toread",
    private val clock: Clock = IosMillisClock,
) : LocalToReadStore {

    private val defaults: NSUserDefaults by lazy { NSUserDefaults(suiteName = suiteName) }

    // Per-library hot flows. Created lazily on first access, seeded from NSUserDefaults.
    // Guarded by [mutex] — Kotlin/Native does not protect mutableMapOf from concurrent mutation.
    private val mutex = Mutex()
    private val flows = mutableMapOf<String, MutableStateFlow<Set<String>>>()

    private suspend fun flowFor(libraryId: String): MutableStateFlow<Set<String>> = mutex.withLock {
        flows.getOrPut(libraryId) {
            MutableStateFlow(readItems(libraryId))
        }
    }

    override fun observeItemIds(libraryId: String): Flow<Set<String>> = flow {
        emitAll(flowFor(libraryId))
    }

    override suspend fun isInToRead(libraryId: String, libraryItemId: String): Boolean =
        readItems(libraryId).contains(libraryItemId)

    override suspend fun add(libraryId: String, libraryItemId: String) {
        val updated = readItems(libraryId) + libraryItemId
        writeItems(libraryId, updated)
        writeTs(libraryId, clock.nowMs())
        flowFor(libraryId).value = updated
    }

    override suspend fun remove(libraryId: String, libraryItemId: String) {
        val updated = readItems(libraryId) - libraryItemId
        writeItems(libraryId, updated)
        writeTs(libraryId, clock.nowMs())
        flowFor(libraryId).value = updated
    }

    override suspend fun lastUpdateMs(libraryId: String): Long =
        defaults.doubleForKey(tsKey(libraryId)).toLong()

    override suspend fun setAll(libraryId: String, itemIds: Set<String>, lastUpdateMs: Long) {
        writeItems(libraryId, itemIds)
        writeTs(libraryId, lastUpdateMs)
        flowFor(libraryId).value = itemIds
    }

    private fun readItems(libraryId: String): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val arr = defaults.arrayForKey(itemKey(libraryId)) as? List<String>
        return arr?.toSet() ?: emptySet()
    }

    private fun writeItems(libraryId: String, items: Set<String>) {
        defaults.setObject(items.toList(), itemKey(libraryId))
    }

    private fun writeTs(libraryId: String, ms: Long) {
        defaults.setDouble(ms.toDouble(), tsKey(libraryId))
    }

    private fun itemKey(libraryId: String) = "to_read_$libraryId"
    private fun tsKey(libraryId: String) = "to_read_ts_$libraryId"
}
