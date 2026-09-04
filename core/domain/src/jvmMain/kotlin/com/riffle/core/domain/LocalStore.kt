package com.riffle.core.domain

import java.io.File
import java.io.InputStream

// Files are keyed by (sourceId, itemId) — item ids are only unique within a Server (ADR 0031).
// On disk they live under dir/<sourceId>/<itemId><ext> so two Servers' colliding ids never
// overwrite each other.
interface LocalStore {
    fun get(sourceId: String, itemId: String): File?
    suspend fun save(sourceId: String, itemId: String, stream: InputStream): File
    fun delete(sourceId: String, itemId: String)

    /** Deletes every file owned by [sourceId] (its whole `dir/<sourceId>/` subtree). */
    fun deleteSource(sourceId: String)

    fun clear()
    fun listItems(): List<StoredItemRef>
}
