package com.riffle.core.data

import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkCollectionWriteResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToReadRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ToReadRepository {

    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean {
        val collection = findToReadCollection(libraryId) ?: return false
        return collection.items.any { it.id == libraryItemId }
    }

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        val server = serverRepository.getActive() ?: return false
        val token = tokenStorage.getToken(server.id) ?: return false
        val baseUrl = server.url.value
        val existing = findToReadCollection(libraryId)
        val result = if (existing == null) {
            api.createCollection(baseUrl, libraryId, TO_READ_COLLECTION_NAME, libraryItemId, token, server.insecureConnectionAllowed)
        } else {
            api.addBookToCollection(baseUrl, existing.id, libraryItemId, token, server.insecureConnectionAllowed)
        }
        return result is NetworkCollectionWriteResult.Success
    }

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean =
        TODO("Task 7")

    private suspend fun findToReadCollection(libraryId: String): NetworkCollection? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        val result = api.getCollections(server.url.value, libraryId, token, server.insecureConnectionAllowed)
        if (result !is NetworkCollectionResult.Success) return null
        return result.collections.firstOrNull { it.name == TO_READ_COLLECTION_NAME }
    }
}
