package com.riffle.core.network

/**
 * Result of a Collection write operation (create, add book, remove book).
 *
 * On success, `collection` is the resulting collection state (for create + add).
 * For remove, the server may or may not return the updated collection — callers should
 * not rely on its presence.
 */
sealed class NetworkCollectionWriteResult {
    data class Success(val collection: NetworkCollection?) : NetworkCollectionWriteResult()
    data class NetworkError(val cause: Throwable) : NetworkCollectionWriteResult()
}
