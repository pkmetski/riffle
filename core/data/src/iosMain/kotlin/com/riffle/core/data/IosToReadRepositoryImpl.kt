package com.riffle.core.data

import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

private data class ToReadSnapshot(val playlistId: String?, val itemIds: Set<String>)

class IosToReadRepositoryImpl(
    private val absLibraryApi: AbsLibraryApi,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val logger: Logger,
) : ToReadRepository {

    private val cache = MutableStateFlow<Map<String, ToReadSnapshot>>(emptyMap())

    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> =
        cache.map { it[libraryId]?.itemIds ?: emptySet() }

    override suspend fun refresh(libraryId: String): Boolean {
        val (baseUrl, token, insecureAllowed) = credentials() ?: return true
        val result = absLibraryApi.getPlaylists(baseUrl, libraryId, token, insecureAllowed)
        return when (result) {
            is NetworkResult.Success -> {
                val match = result.value.firstOrNull { it.name == TO_READ_PLAYLIST_NAME }
                val snapshot = ToReadSnapshot(
                    playlistId = match?.id,
                    itemIds = match?.bookIds ?: emptySet(),
                )
                cache.value = cache.value + (libraryId to snapshot)
                true
            }
            else -> {
                logger.d(LogChannel.ToRead) { "refresh($libraryId) failed: $result" }
                false
            }
        }
    }

    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean =
        cache.value[libraryId]?.itemIds?.contains(libraryItemId) == true

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        val (baseUrl, token, insecureAllowed) = credentials() ?: return false
        val before = cache.value[libraryId] ?: ToReadSnapshot(playlistId = null, itemIds = emptySet())
        cache.value = cache.value + (libraryId to before.copy(itemIds = before.itemIds + libraryItemId))
        val playlistId = before.playlistId
        val ok = if (playlistId == null) {
            runCatching {
                val result = absLibraryApi.createPlaylist(
                    baseUrl, libraryId, TO_READ_PLAYLIST_NAME, libraryItemId, token, insecureAllowed,
                )
                when (result) {
                    is NetworkResult.Success -> {
                        val created = result.value
                        cache.value = cache.value + (libraryId to ToReadSnapshot(
                            playlistId = created?.id,
                            itemIds = before.itemIds + libraryItemId,
                        ))
                        true
                    }
                    else -> false
                }
            }.getOrElse {
                logger.d(LogChannel.ToRead) { "addToToRead($libraryId, $libraryItemId) createPlaylist failed: $it" }
                false
            }
        } else {
            val result = runCatching {
                absLibraryApi.addBookToPlaylist(baseUrl, playlistId, libraryItemId, token, insecureAllowed)
            }.getOrElse { addErr ->
                logger.d(LogChannel.ToRead) { "addToToRead($libraryId, $libraryItemId) addBookToPlaylist failed, retrying via create: $addErr" }
                // Stale playlistId — recreate
                return runCatching {
                    val res = absLibraryApi.createPlaylist(
                        baseUrl, libraryId, TO_READ_PLAYLIST_NAME, libraryItemId, token, insecureAllowed,
                    )
                    when (res) {
                        is NetworkResult.Success -> {
                            cache.value = cache.value + (libraryId to ToReadSnapshot(
                                playlistId = res.value?.id,
                                itemIds = before.itemIds + libraryItemId,
                            ))
                            true
                        }
                        else -> {
                            cache.value = cache.value + (libraryId to before)
                            false
                        }
                    }
                }.getOrElse { createErr ->
                    logger.d(LogChannel.ToRead) { "addToToRead($libraryId, $libraryItemId) recovery create failed: $createErr" }
                    cache.value = cache.value + (libraryId to before)
                    false
                }
            }
            result is NetworkResult.Success
        }
        if (!ok) cache.value = cache.value + (libraryId to before)
        return ok
    }

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
        val (baseUrl, token, insecureAllowed) = credentials() ?: return false
        val before = cache.value[libraryId] ?: return true
        val playlistId = before.playlistId ?: return true
        if (libraryItemId !in before.itemIds) return true
        val remaining = before.itemIds - libraryItemId
        val optimistic = if (remaining.isEmpty()) {
            ToReadSnapshot(playlistId = null, itemIds = emptySet())
        } else {
            before.copy(itemIds = remaining)
        }
        cache.value = cache.value + (libraryId to optimistic)
        val result = runCatching {
            absLibraryApi.removeBookFromPlaylist(baseUrl, playlistId, libraryItemId, token, insecureAllowed)
        }.getOrElse {
            logger.d(LogChannel.ToRead) { "removeFromToRead($libraryId, $libraryItemId) failed: $it" }
            cache.value = cache.value + (libraryId to before)
            return false
        }
        val ok = result is NetworkResult.Success
        if (!ok) cache.value = cache.value + (libraryId to before)
        return ok
    }

    private data class Creds(val baseUrl: String, val token: String, val insecureAllowed: Boolean)

    private suspend fun credentials(): Creds? {
        val source = sourceRepository.getActive() ?: return null
        if (source.type != SourceType.ABS) return null
        val token = tokenStorage.getToken(source.id) ?: return null
        return Creds(source.url.value, token, source.insecureConnectionAllowed)
    }
}
