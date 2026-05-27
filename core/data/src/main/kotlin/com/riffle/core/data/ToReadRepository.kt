package com.riffle.core.data

const val TO_READ_COLLECTION_NAME = "To Read"

/**
 * Manages the per-Library "To Read" Collection on the active ABS server.
 *
 * The list is backed by a normal ABS Collection named [TO_READ_COLLECTION_NAME], looked
 * up by name and find-or-created on first use. See ADR 0018.
 */
interface ToReadRepository {
    /** Returns true if [libraryItemId] is currently in the "To Read" collection of [libraryId]. */
    suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean

    /**
     * Adds [libraryItemId] to the "To Read" collection of [libraryId], creating the
     * collection if it does not yet exist. Returns true on success.
     */
    suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean

    /**
     * Removes [libraryItemId] from the "To Read" collection of [libraryId]. Returns true
     * on success or if the collection / membership did not exist (a no-op success).
     */
    suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean
}
