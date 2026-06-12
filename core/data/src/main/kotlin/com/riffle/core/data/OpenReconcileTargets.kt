package com.riffle.core.data

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The set of (serverId, itemId) a **live surface** (reader / audiobook player) is currently driving
 * (ADR 0030). The durable sweep skips these: the open book's own ~30s cycle owns its inbound jumps,
 * so the headless worker must not silently absorb a cross-device server-win into the open book's row —
 * that would leave the visible reader behind the server and let the next page-turn overwrite the newer
 * server position (a cross-device erase). Durability is unaffected: while open the live cycle pushes
 * the book, and once it closes its dirty row is reconciled by the next sweep.
 *
 * A matched book registers **both** its ebook and audiobook item ids, since the open surface's cycle
 * reconciles both ABS records.
 */
@Singleton
class OpenReconcileTargets @Inject constructor() {
    private val open = ConcurrentHashMap.newKeySet<String>()

    private fun key(serverId: String, itemId: String) = "$serverId $itemId"

    fun markOpen(serverId: String, itemId: String) { open.add(key(serverId, itemId)) }
    fun markClosed(serverId: String, itemId: String) { open.remove(key(serverId, itemId)) }
    fun isOpen(serverId: String, itemId: String): Boolean = open.contains(key(serverId, itemId))
}
