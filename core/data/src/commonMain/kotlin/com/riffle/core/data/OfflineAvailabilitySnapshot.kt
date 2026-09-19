package com.riffle.core.data

import com.riffle.core.domain.ApplicationScope
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile

/**
 * Synchronous, eventually-consistent view onto a [Flow] of `Map<K, V>` — the seam behind
 * "is this item available offline?" checks called from Compose composition (which cannot suspend).
 *
 * On construction the snapshot launches a survivable collector on [applicationScope] that writes
 * each emission to a volatile field; reads ([get] / [snapshot]) return the latest value without
 * suspending. Until the first upstream emission lands, reads return [initial] (defaults to empty).
 *
 * Was a java.util.concurrent AtomicReference while this class was Android-only. A @Volatile field
 * gives the same guarantee for this usage — exactly one writer (the collector) and many readers,
 * with no read-modify-write — and works on Kotlin/Native.
 *
 * Callers should compose their domain answer on top: e.g. "is offline" = "the snapshot has a link
 * AND the bundle file exists on disk". The snapshot only maintains the map.
 */
class OfflineAvailabilitySnapshot<K, V>(
    applicationScope: ApplicationScope,
    source: Flow<Map<K, V>>,
    initial: Map<K, V> = emptyMap(),
) {
    @Volatile
    private var ref: Map<K, V> = initial

    init {
        applicationScope.launchSurvivable {
            source.collect { ref = it }
        }
    }

    fun snapshot(): Map<K, V> = ref

    operator fun get(key: K): V? = ref[key]
}
