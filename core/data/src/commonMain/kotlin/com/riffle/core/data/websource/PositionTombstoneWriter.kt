package com.riffle.core.data.websource

/**
 * Marks a web-source item as explicitly removed by the user by writing soft-delete tombstones to
 * both position tables (`reading_positions` and `audiobook_positions`). The tombstoned rows remain
 * dirty so the next sweep propagates the deletion to the WebDAV progress files, and
 * [WebSourceLibraryItemMaterializer] / [com.riffle.core.data.LibraryItemUiProgressSink] skip
 * re-inserting the item into the library shelf.
 */
fun interface PositionTombstoneWriter {
    suspend fun markDeleted(sourceId: String, itemId: String)
}
