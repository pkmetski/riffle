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
        val session = resolveSession() ?: return false
        val collection = findToReadCollection(session, libraryId) ?: return false
        return collection.items.any { it.id == libraryItemId }
    }

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        val existing = findToReadCollection(session, libraryId)
        val result = if (existing == null) {
            api.createCollection(session.baseUrl, libraryId, TO_READ_COLLECTION_NAME, libraryItemId, session.token, session.insecureAllowed)
        } else {
            api.addBookToCollection(session.baseUrl, existing.id, libraryItemId, session.token, session.insecureAllowed)
        }
        return result is NetworkCollectionWriteResult.Success
    }

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        val existing = findToReadCollection(session, libraryId) ?: return true
        val result = api.removeBookFromCollection(session.baseUrl, existing.id, libraryItemId, session.token, session.insecureAllowed)
        return result is NetworkCollectionWriteResult.Success
    }

    private suspend fun resolveSession(): Session? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        return Session(baseUrl = server.url.value, token = token, insecureAllowed = server.insecureConnectionAllowed)
    }

    private suspend fun findToReadCollection(session: Session, libraryId: String): NetworkCollection? {
        val result = api.getCollections(session.baseUrl, libraryId, session.token, session.insecureAllowed)
        if (result !is NetworkCollectionResult.Success) return null
        return result.collections.firstOrNull { it.name == TO_READ_COLLECTION_NAME }
    }

    private data class Session(val baseUrl: String, val token: String, val insecureAllowed: Boolean)
}
