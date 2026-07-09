package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.READALOUD_MEDIA_TYPE
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceFilesCleaner
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerApi
import com.riffle.core.network.errorAsThrowable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class SourceRepositoryImpl @Inject constructor(
    private val dao: SourceDao,
    private val tokenStorage: TokenStorage,
    private val absApiClient: AbsApi,
    private val storytellerApi: StorytellerApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val libraryApi: AbsLibraryApi,
    private val libraryDao: LibraryDao,
    private val libraryItemDao: LibraryItemDao,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
    private val filesCleaner: SourceFilesCleaner,
) : SourceRepository {

    override fun observeAll(): Flow<List<Source>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getActive(): Source? = dao.getActive()?.toDomain()

    override suspend fun getById(sourceId: String): Source? = dao.getById(sourceId)?.toDomain()

    override suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult = when (serverType) {
        ServerType.AUDIOBOOKSHELF -> authenticateAbs(url, username, password, insecureAllowed)
        ServerType.STORYTELLER -> authenticateStoryteller(url, username, password, insecureAllowed)
    }

    private suspend fun authenticateAbs(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult {
        val loginResult = absApiClient.login(url.value, username, password, insecureAllowed)
        return when (loginResult) {
            NetworkResult.Auth -> AuthenticateResult.WrongCredentials(WRONG_CREDENTIALS_MESSAGE)
            is NetworkResult.InsecureConnection -> AuthenticateResult.InsecureConnection(loginResult.type)
            is NetworkResult.Success -> {
                val libs = libraryApi.getLibraries(url.value, loginResult.value.token, insecureAllowed)
                if (libs !is NetworkResult.Success) {
                    AuthenticateResult.LibraryFetchFailed(libs.errorAsThrowable())
                } else AuthenticateResult.Success(
                    PendingSource(
                        url = url,
                        username = loginResult.value.username,
                        userId = loginResult.value.userId,
                        token = loginResult.value.token,
                        password = password,
                        insecureConnectionAllowed = insecureAllowed,
                        libraries = libs.value
                            .filter { it.mediaType == "book" }
                            .map {
                                Library(
                                    id = it.id,
                                    name = it.name,
                                    mediaType = it.mediaType,
                                    isUnsupported = false,
                                )
                            },
                    )
                )
            }
            else -> AuthenticateResult.NetworkError(loginResult.errorAsThrowable())
        }
    }

    private suspend fun authenticateStoryteller(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult = when (val result = storytellerApi.login(url.value, username, password, insecureAllowed)) {
        NetworkResult.Auth -> AuthenticateResult.WrongCredentials(WRONG_CREDENTIALS_MESSAGE)
        is NetworkResult.InsecureConnection -> AuthenticateResult.InsecureConnection(result.type)
        is NetworkResult.Success -> AuthenticateResult.Success(
            PendingSource(
                url = url,
                username = username,
                userId = "", // Storyteller's auth response doesn't expose a user id; identity is the username + token.
                token = result.value,
                password = password,
                insecureConnectionAllowed = insecureAllowed,
                // Storyteller contributes no browsable Library (ADR 0026) — it is a Settings-only
                // readaloud backend. The local namespace row that hosts its books as matcher input
                // is created in [commit], not surfaced to the user.
                libraries = emptyList(),
                serverType = ServerType.STORYTELLER,
            )
        )
        else -> AuthenticateResult.NetworkError(result.errorAsThrowable())
    }

    override suspend fun commit(
        pending: PendingSource,
        hiddenLibraryIds: Set<String>,
    ): CommitSourceResult = try {
        val id = UUID.randomUUID().toString()
        val entity = SourceEntity(
            id = id,
            url = pending.url.value,
            isActive = false,                    // overridden inside transaction
            insecureConnectionAllowed = pending.insecureConnectionAllowed,
            username = pending.username,
            serverType = pending.serverType.name,
            // ABS exposes `user.id` on the login response — persist it now so annotation sync has
            // a cross-device-stable namespace from first open. Storyteller's login response
            // carries no equivalent identity (auth is username + token), so leave it null.
            absUserId = pending.userId.takeIf { it.isNotBlank() && pending.serverType == ServerType.AUDIOBOOKSHELF },
            type = "ABS",
        )
        // A Storyteller Source is a Settings-only readaloud backend (ADR 0026) — it must never
        // become the active browsable Source, not even as the first source added. ABS sources keep
        // the "first source becomes active" convenience.
        val inserted = if (pending.serverType == ServerType.STORYTELLER) {
            dao.upsert(entity)
            entity
        } else {
            dao.upsertAsFirstIfNoActive(entity)
        }
        tokenStorage.saveToken(id, pending.token)
        tokenStorage.savePassword(id, pending.password)
        val libraryRows = pending.libraries.map {
            LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, sourceId = id)
        }.toMutableList()
        // A Storyteller Source contributes no browsable Library (ADR 0026), but its readaloud books
        // are stored in `library_items` as matcher input. They need an owning `libraries` row so the
        // matcher's library→source join resolves their serverType and the source-removal cascade
        // cleans them up. This row is never surfaced in the drawer or any library picker — the
        // Source is never the active browsable Source.
        if (pending.serverType == ServerType.STORYTELLER) {
            libraryRows += LibraryEntity(
                id = readaloudLibraryId(id),
                name = "Readalouds",
                mediaType = READALOUD_MEDIA_TYPE,
                sourceId = id,
            )
        }
        libraryDao.replaceAllForSource(sourceId = id, libraries = libraryRows)
        hiddenLibraryIds.forEach { hidden -> visibilityStore.hideLibrary(id, hidden) }
        CommitSourceResult.Success(inserted.toDomain())
    } catch (t: Throwable) {
        CommitSourceResult.Failure(t)
    }

    override suspend fun setActive(sourceId: String) {
        // A Storyteller Source is a Settings-only readaloud backend (ADR 0026) — it can never be the
        // active browsable Source. Enforce the invariant here so no caller (source removal, deep
        // links, future UI) can promote one, and a stale DB row can't be re-activated.
        if (dao.getById(sourceId)?.serverType == ServerType.STORYTELLER.name) return
        dao.setActiveAtomic(sourceId)
    }

    override suspend fun remove(sourceId: String) {
        // Cascade: clear per-library items + the libraries themselves + the token + the source row.
        // For Storyteller sources this purges the synthetic Readaloud library and its books;
        // for ABS sources it cleans up real libraries and their items. ReadaloudLinks cross-source
        // cleanup belongs to #36 (matching slice) — until that lands the count of links is 0.
        libraryDao.libraryIdsForSource(sourceId).forEach { libraryItemDao.deleteByLibraryId(sourceId, it) }
        libraryDao.deleteBySourceId(sourceId)
        dao.deleteById(sourceId)
        tokenStorage.deleteToken(sourceId)
        tokenStorage.deletePassword(sourceId)
        // The file stores live outside Room, so the FK cascade above doesn't touch them — purge the
        // Source's downloaded/cached files here so they don't leak on disk after removal.
        filesCleaner.deleteAllForSource(sourceId)
    }

    override suspend fun getSourceVersion(sourceId: String): String? {
        val source = dao.getById(sourceId)?.toDomain() ?: return null
        // Storyteller exposes no /server-info endpoint; the UI deliberately shows no version for it.
        if (source.serverType == ServerType.STORYTELLER) return null
        val token = tokenStorage.getToken(sourceId) ?: return null
        return serverInfoApi.getServerInfo(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
    }

    companion object {
        // The local-only library id that namespaces a Storyteller Source's readaloud rows in
        // `library_items` (matcher input; never browsable — ADR 0026). Its mediaType is the shared
        // [READALOUD_MEDIA_TYPE] so consumers can filter it out of browsable surfaces.
        fun readaloudLibraryId(sourceId: String): String = "readaloud:$sourceId"

        // Surfaced when the network layer maps 401 to [NetworkResult.Auth] — the unified result type
        // drops the server-provided string, so the user-facing message is owned here.
        private const val WRONG_CREDENTIALS_MESSAGE = "Invalid username or password"
    }

    override suspend fun ensureAbsUserId(sourceId: String): String? {
        val row = dao.getById(sourceId) ?: return null
        if (row.serverType == ServerType.STORYTELLER.name) return null
        row.absUserId?.takeIf { it.isNotBlank() }?.let { return it }
        // Legacy row (added before the column existed) — backfill from /api/me.
        val token = tokenStorage.getToken(sourceId) ?: return null
        val fetched = serverInfoApi.getCurrentUserId(
            baseUrl = row.url,
            token = token,
            insecureAllowed = row.insecureConnectionAllowed,
        ) ?: return null
        dao.setAbsUserId(sourceId, fetched)
        return fetched
    }

    private fun SourceEntity.toDomain(): Source {
        val parsedUrl = SourceUrl.parse(url)
            ?: SourceUrl.parse("https://invalid.example.com")!!
        return Source(
            id = id,
            url = parsedUrl,
            isActive = isActive,
            insecureConnectionAllowed = insecureConnectionAllowed,
            username = username,
            type = runCatching { SourceType.valueOf(type) }.getOrDefault(SourceType.ABS),
            serverType = ServerType.fromStorageString(serverType),
            absUserId = absUserId,
        )
    }
}
