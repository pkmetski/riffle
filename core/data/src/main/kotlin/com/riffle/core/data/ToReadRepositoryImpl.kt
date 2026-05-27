package com.riffle.core.data

import com.riffle.core.domain.Collection
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkCollectionWriteResult
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToReadRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val libraryRepository: LibraryRepository,
) : ToReadRepository {

    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean {
        val toRead = cachedToRead(libraryId) ?: return false
        return libraryRepository.observeCollectionItems(toRead.id).first()
            .any { it.id == libraryItemId }
    }

    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
        val session = resolveSession() ?: return false
        // Lookup order: prefer the local cache (instant, offline-tolerant). Only fall back to the
        // network when the cache has no "To Read" — otherwise a stale cache could cause us to
        // POST a duplicate To Read collection that already exists on the server.
        val toReadId = cachedToRead(libraryId)?.id
            ?: findToReadOnServer(session, libraryId)?.id
        val result = if (toReadId == null) {
            api.createCollection(
                session.baseUrl, libraryId, TO_READ_COLLECTION_NAME, libraryItemId,
                session.token, session.insecureAllowed,
            )
        } else {
            api.addBookToCollection(
                session.baseUrl, toReadId, libraryItemId, session.token, session.insecureAllowed,
            )
        }
        val ok = result is NetworkCollectionWriteResult.Success
        if (ok) libraryRepository.refreshCollections(libraryId)
        return ok
    }

    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
        val toRead = cachedToRead(libraryId) ?: return true
        val session = resolveSession() ?: return false
        val result = api.removeBookFromCollection(
            session.baseUrl, toRead.id, libraryItemId, session.token, session.insecureAllowed,
        )
        val ok = result is NetworkCollectionWriteResult.Success
        if (ok) libraryRepository.refreshCollections(libraryId)
        return ok
    }

    private suspend fun resolveSession(): Session? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        return Session(baseUrl = server.url.value, token = token, insecureAllowed = server.insecureConnectionAllowed)
    }

    private suspend fun cachedToRead(libraryId: String): Collection? =
        libraryRepository.observeCollections(libraryId).first()
            .firstOrNull { it.name == TO_READ_COLLECTION_NAME }

    private suspend fun findToReadOnServer(session: Session, libraryId: String): Collection? {
        val result = api.getCollections(session.baseUrl, libraryId, session.token, session.insecureAllowed)
        if (result !is NetworkCollectionResult.Success) return null
        val match = result.collections.firstOrNull { it.name == TO_READ_COLLECTION_NAME } ?: return null
        return Collection(id = match.id, libraryId = match.libraryId, name = match.name, bookCount = match.items.size)
    }

    private data class Session(val baseUrl: String, val token: String, val insecureAllowed: Boolean)
}
