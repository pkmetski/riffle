package com.riffle.core.domain

import java.io.File
import java.io.InputStream

/** A locally-stored item, identified by its owning Server and item id (ADR 0025). */
data class StoredItemRef(val serverId: String, val itemId: String)

// Files are keyed by (serverId, itemId) — item ids are only unique within a Server (ADR 0025).
// On disk they live under dir/<serverId>/<itemId><ext> so two Servers' colliding ids never
// overwrite each other.
interface LocalStore {
    fun get(serverId: String, itemId: String): File?
    suspend fun save(serverId: String, itemId: String, stream: InputStream): File
    fun delete(serverId: String, itemId: String)
    fun clear()
    fun listItems(): List<StoredItemRef>
}
