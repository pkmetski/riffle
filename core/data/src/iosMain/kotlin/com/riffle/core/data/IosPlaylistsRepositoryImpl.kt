package com.riffle.core.data

import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkPlaylist
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class IosPlaylistsRepositoryImpl(
    private val absLibraryApi: AbsLibraryApi,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val logger: Logger,
) : PlaylistsRepository {

    private val cache = MutableStateFlow<Map<String, List<CatalogPlaylist>>>(emptyMap())

    override fun observePlaylists(rootId: String): Flow<List<CatalogPlaylist>> =
        cache.map { all ->
            (all[rootId] ?: emptyList()).filterNot { it.isReservedName() }
        }

    override suspend fun refresh(rootId: String): Boolean {
        val (baseUrl, token, insecureAllowed) = credentials() ?: return true
        val result = absLibraryApi.getPlaylists(baseUrl, rootId, token, insecureAllowed)
        return when (result) {
            is NetworkResult.Success -> {
                cache.value = cache.value + (rootId to result.value.map { it.toDomain() })
                true
            }
            else -> {
                logger.d(LogChannel.Playlists) { "refresh($rootId) failed: $result" }
                false
            }
        }
    }

    override suspend fun getPlaylist(rootId: String, playlistId: String): CatalogPlaylist? {
        val (baseUrl, token, insecureAllowed) = credentials() ?: return null
        val result = absLibraryApi.getPlaylists(baseUrl, rootId, token, insecureAllowed)
        return when (result) {
            is NetworkResult.Success -> result.value.firstOrNull { it.id == playlistId }?.toDomain()
            else -> {
                logger.d(LogChannel.Playlists) { "getPlaylist($rootId, $playlistId) failed: $result" }
                null
            }
        }
    }

    override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist {
        if (RESERVED_PLAYLIST_NAMES.any { it.equals(name.trim(), ignoreCase = true) }) {
            throw ReservedPlaylistNameException(name)
        }
        val (baseUrl, token, insecureAllowed) = credentials()
            ?: throw IllegalStateException("No active ABS source")
        val result = absLibraryApi.createPlaylist(baseUrl, rootId, name.trim(), initialItemId, token, insecureAllowed)
        val created = when (result) {
            is NetworkResult.Success -> result.value?.toDomain()
                ?: throw IllegalStateException("createPlaylist returned null")
            else -> throw IllegalStateException("createPlaylist failed: $result")
        }
        val updated = (cache.value[rootId] ?: emptyList()) + created
        cache.value = cache.value + (rootId to updated)
        return created
    }

    override suspend fun addItemToPlaylist(rootId: String, playlistId: String, itemId: String): Boolean {
        val (baseUrl, token, insecureAllowed) = credentials() ?: return false
        val current = cache.value[rootId] ?: emptyList()
        val target = current.firstOrNull { it.id == playlistId }
        if (target != null && itemId in target.itemIds) return true
        val result = absLibraryApi.addBookToPlaylist(baseUrl, playlistId, itemId, token, insecureAllowed)
        return when (result) {
            is NetworkResult.Success -> {
                val updated = result.value?.toDomain()
                if (updated != null) {
                    cache.value = cache.value + (rootId to current.replaceOrAppend(updated))
                }
                true
            }
            else -> {
                logger.d(LogChannel.Playlists) { "addItemToPlaylist($playlistId, $itemId) failed: $result" }
                false
            }
        }
    }

    override suspend fun removeItemFromPlaylist(rootId: String, playlistId: String, itemId: String): Boolean {
        val (baseUrl, token, insecureAllowed) = credentials() ?: return false
        val current = cache.value[rootId] ?: emptyList()
        val target = current.firstOrNull { it.id == playlistId }
        if (target != null && itemId !in target.itemIds) return true
        val result = absLibraryApi.removeBookFromPlaylist(baseUrl, playlistId, itemId, token, insecureAllowed)
        return when (result) {
            is NetworkResult.Success -> {
                val updated = result.value?.toDomain()
                val newList = if (updated == null || updated.itemIds.isEmpty()) {
                    current.filter { it.id != playlistId }
                } else {
                    current.replaceOrAppend(updated)
                }
                cache.value = cache.value + (rootId to newList)
                true
            }
            else -> {
                logger.d(LogChannel.Playlists) { "removeItemFromPlaylist($playlistId, $itemId) failed: $result" }
                false
            }
        }
    }

    private data class Creds(val baseUrl: String, val token: String, val insecureAllowed: Boolean)

    private suspend fun credentials(): Creds? {
        val source = sourceRepository.getActive() ?: return null
        if (source.type != SourceType.ABS) return null
        val token = tokenStorage.getToken(source.id) ?: return null
        return Creds(source.url.value, token, source.insecureConnectionAllowed)
    }

    private fun CatalogPlaylist.isReservedName(): Boolean =
        RESERVED_PLAYLIST_NAMES.any { it.equals(name.trim(), ignoreCase = true) }

    private fun List<CatalogPlaylist>.replaceOrAppend(updated: CatalogPlaylist): List<CatalogPlaylist> {
        val idx = indexOfFirst { it.id == updated.id }
        return if (idx >= 0) toMutableList().also { it[idx] = updated } else this + updated
    }
}

private fun NetworkPlaylist.toDomain() = CatalogPlaylist(
    id = id,
    rootId = libraryId,
    name = name,
    bookCount = bookCount,
    itemIds = bookIds.toList(),
)
