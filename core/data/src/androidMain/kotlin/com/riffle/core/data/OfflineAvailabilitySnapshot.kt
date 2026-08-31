package com.riffle.core.data

import com.riffle.core.domain.ApplicationScope
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicReference

/**
 * Synchronous, eventually-consistent view onto a [Flow] of `Map<K, V>` — the seam behind
 * "is this item available offline?" checks called from Compose composition (which cannot suspend).
 *
 * On construction the snapshot launches a survivable collector on [applicationScope] that writes
 * each emission to an [AtomicReference]; reads ([get] / [snapshot]) return the latest value without
 * suspending. Until the first upstream emission lands, reads return [initial] (defaults to empty).
 *
 * Callers should compose their domain answer on top: e.g. "is offline" = "the snapshot has a link
 * AND the bundle file exists on disk". The snapshot only maintains the map.
 */
class OfflineAvailabilitySnapshot<K, V>(
    applicationScope: ApplicationScope,
    source: Flow<Map<K, V>>,
    initial: Map<K, V> = emptyMap(),
) {
    private val ref = AtomicReference(initial)

    init {
        applicationScope.launchSurvivable {
            source.collect { ref.set(it) }
        }
    }

    fun snapshot(): Map<K, V> = ref.get()

    operator fun get(key: K): V? = ref.get()[key]
}
