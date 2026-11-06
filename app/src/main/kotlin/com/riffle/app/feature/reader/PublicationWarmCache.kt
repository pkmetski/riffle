package com.riffle.app.feature.reader

import org.readium.r2.shared.publication.Publication
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot in-memory cache for the last-opened [Publication].
 *
 * Keeps the Readium [Publication] alive after the reader closes so the next cold open of the
 * same book can skip the EPUB ZIP parse entirely (the dominant 1-3 s bottleneck on reopen).
 *
 * The caller takes ownership on [take] and is responsible for closing the publication when
 * the reader eventually closes again. If a different book is opened before the cached one is
 * taken, [put] closes the evicted publication immediately.
 *
 * Thread-safe via [AtomicReference] compare-and-set; no locks held across suspension points.
 */
class PublicationWarmCache {

    private data class Slot(val itemId: String, val pub: Publication)

    private val slot = AtomicReference<Slot?>()

    fun put(itemId: String, pub: Publication) {
        val old = slot.getAndSet(Slot(itemId, pub))
        // Always close the displaced publication — even when replacing with the same itemId,
        // e.g. when two VMs for the same book race through onCleared().
        if (old != null) old.pub.close()
    }

    fun take(itemId: String): Publication? {
        while (true) {
            val current = slot.get() ?: return null
            if (current.itemId != itemId) return null
            if (slot.compareAndSet(current, null)) return current.pub
        }
    }

    fun clear() {
        slot.getAndSet(null)?.pub?.close()
    }
}
