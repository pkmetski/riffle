package com.riffle.core.data

import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkPlaylistResult
import com.riffle.core.network.NetworkPlaylistWriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory To Read snapshot for a single library.
 *
 * `playlistId == null` means we know the server has no To Read playlist (so next add must create).
 * After [ToReadRepositoryImpl.refresh] succeeds, the snapshot reflects the server's state.
 */
private data class ToReadSnapshot(val playlistId: String?, val itemIds: Set<String>)

@Singleton
class ToReadRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ToReadRepository {

    private val cache = MutableStateFlow<Map<String, ToReadSnapshot>>(emptyMap())

    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> =
        cache.map { it[libraryId]?.itemIds ?: emptySet() }

    override suspend fun refresh(libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        val result = api.getPlaylists(session.baseUrl, libraryId, session.token, session.insecureAllowed)
        if (result !is NetworkPlaylistResult.Success) return false
        val match = result.playlists.firstOrNull { it.name == TO_READ_PLAYLIST_NAME }
        val snapshot = ToReadSnapshot(
            playlistId = match?.id,
            itemIds = match?.bookIds ?: emptySet(),
        )
        cache.value = cache.value + (libraryId to snapshot)
        return true
    }

    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean =
        cache.value[libraryId]?.itemIds?.contains(libraryItemId) == true

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        val before = cache.value[libraryId] ?: ToReadSnapshot(playlistId = null, itemIds = emptySet())
        // Optimistic update
        cache.value = cache.value + (libraryId to before.copy(itemIds = before.itemIds + libraryItemId))
        val playlistId = before.playlistId
        val ok = if (playlistId == null) {
            val r = api.createPlaylist(
                session.baseUrl, libraryId, TO_READ_PLAYLIST_NAME, libraryItemId,
                session.token, session.insecureAllowed,
            )
            if (r is NetworkPlaylistWriteResult.Success) {
                val newId = r.playlist?.id
                if (newId != null) {
                    cache.value = cache.value + (libraryId to ToReadSnapshot(newId, before.itemIds + libraryItemId))
                }
                true
            } else false
        } else {
            val r = api.addBookToPlaylist(
                session.baseUrl, playlistId, libraryItemId, session.token, session.insecureAllowed,
            )
            r is NetworkPlaylistWriteResult.Success
        }
        if (!ok) cache.value = cache.value + (libraryId to before)
        return ok
    }

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        val before = cache.value[libraryId] ?: return true
        val playlistId = before.playlistId ?: return true
        if (libraryItemId !in before.itemIds) return true
        val remainingIds = before.itemIds - libraryItemId
        // Optimistic update. If we're removing the last item, ABS auto-deletes the playlist
        // server-side — drop our cached playlistId so the next addToToRead creates a fresh one.
        val optimistic = if (remainingIds.isEmpty()) {
            ToReadSnapshot(playlistId = null, itemIds = emptySet())
        } else {
            before.copy(itemIds = remainingIds)
        }
        cache.value = cache.value + (libraryId to optimistic)
        val r = api.removeBookFromPlaylist(
            session.baseUrl, playlistId, libraryItemId, session.token, session.insecureAllowed,
        )
        val ok = r is NetworkPlaylistWriteResult.Success
        if (!ok) cache.value = cache.value + (libraryId to before)
        return ok
    }

    private suspend fun resolveSession(): Session? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        return Session(server.url.value, token, server.insecureConnectionAllowed)
    }

    private data class Session(val baseUrl: String, val token: String, val insecureAllowed: Boolean)
}
