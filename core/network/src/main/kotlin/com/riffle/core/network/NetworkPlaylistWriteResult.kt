package com.riffle.core.network

/**
 * Result of a Playlist write operation (create, add item, remove item).
 *
 * On success, `playlist` is the resulting playlist state (for create + add).
 * For remove, the server may or may not return the updated playlist — callers should
 * not rely on its presence.
 */
sealed class NetworkPlaylistWriteResult {
    data class Success(val playlist: NetworkPlaylist?) : NetworkPlaylistWriteResult()
    data class NetworkError(val cause: Throwable) : NetworkPlaylistWriteResult()
}
