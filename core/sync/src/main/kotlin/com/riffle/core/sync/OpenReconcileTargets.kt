package com.riffle.core.sync

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The set of (sourceId, itemId) a live surface (reader / audiobook player) is currently driving
 * (ADR 0030). The durable sweep skips these: the open book's own ~30s cycle owns its inbound
 * jumps, so the headless worker must not silently absorb a cross-device server-win into the open
 * book's row.
 */
@Singleton
class OpenReconcileTargets @Inject constructor() {
    private val open = ConcurrentHashMap.newKeySet<String>()

    private fun key(sourceId: String, itemId: String) = "$sourceId $itemId"

    fun markOpen(sourceId: String, itemId: String) { open.add(key(sourceId, itemId)) }
    fun markClosed(sourceId: String, itemId: String) { open.remove(key(sourceId, itemId)) }
    fun isOpen(sourceId: String, itemId: String): Boolean = open.contains(key(sourceId, itemId))
}
