package com.riffle.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The set of (sourceId, itemId) a live surface (reader / audiobook player) is currently driving
 * (ADR 0030). The durable sweep skips these: the open book's own ~30s cycle owns its inbound
 * jumps, so the headless worker must not silently absorb a cross-device server-win into the open
 * book's row.
 */
class OpenReconcileTargets {
    private val open = MutableStateFlow<Set<String>>(emptySet())

    private fun key(sourceId: String, itemId: String) = "$sourceId $itemId"

    fun markOpen(sourceId: String, itemId: String) {
        val target = key(sourceId, itemId)
        open.update { it + target }
    }

    fun markClosed(sourceId: String, itemId: String) {
        val target = key(sourceId, itemId)
        open.update { it - target }
    }

    fun isOpen(sourceId: String, itemId: String): Boolean = key(sourceId, itemId) in open.value
}
