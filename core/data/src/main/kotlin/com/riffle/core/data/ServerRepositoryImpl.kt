package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ServerDao
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.READALOUD_MEDIA_TYPE
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerFilesCleaner
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLoginResult
import com.riffle.core.network.NetworkStorytellerLoginResult
import com.riffle.core.network.StorytellerApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class ServerRepositoryImpl @Inject constructor(
    private val dao: ServerDao,
    private val tokenStorage: TokenStorage,
    private val absApiClient: AbsApi,
    private val storytellerApi: StorytellerApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val libraryApi: AbsLibraryApi,
    private val libraryDao: LibraryDao,
    private val libraryItemDao: LibraryItemDao,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
    private val filesCleaner: ServerFilesCleaner,
) : ServerRepository {

    override fun observeAll(): Flow<List<Server>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getActive(): Server? = dao.getActive()?.toDomain()

    override suspend fun getById(serverId: String): Server? = dao.getById(serverId)?.toDomain()

    override suspend fun authenticate(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult = when (serverType) {
        ServerType.AUDIOBOOKSHELF -> authenticateAbs(url, username, password, insecureAllowed)
        ServerType.STORYTELLER -> authenticateStoryteller(url, username, password, insecureAllowed)
    }

    private suspend fun authenticateAbs(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult {
        val loginResult = absApiClient.login(url.value, username, password, insecureAllowed)
        return when (loginResult) {
            is NetworkLoginResult.WrongCredentials -> AuthenticateResult.WrongCredentials(loginResult.message)
            is NetworkLoginResult.NetworkError -> AuthenticateResult.NetworkError(loginResult.cause)
            is NetworkLoginResult.InsecureConnection -> AuthenticateResult.InsecureConnection(loginResult.type)
            is NetworkLoginResult.Success -> {
                when (val libs = libraryApi.getLibraries(url.value, loginResult.token, insecureAllowed)) {
                    is NetworkLibrariesResult.NetworkError -> AuthenticateResult.LibraryFetchFailed(libs.cause)
                    is NetworkLibrariesResult.Success -> AuthenticateResult.Success(
                        PendingServer(
                            url = url,
                            username = loginResult.username,
                            userId = loginResult.userId,
                            token = loginResult.token,
                            insecureConnectionAllowed = insecureAllowed,
                            libraries = libs.libraries
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
            }
        }
    }

    private suspend fun authenticateStoryteller(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult = when (val result = storytellerApi.login(url.value, username, password, insecureAllowed)) {
        is NetworkStorytellerLoginResult.WrongCredentials -> AuthenticateResult.WrongCredentials(result.message)
        is NetworkStorytellerLoginResult.NetworkError -> AuthenticateResult.NetworkError(result.cause)
        is NetworkStorytellerLoginResult.InsecureConnection -> AuthenticateResult.InsecureConnection(result.type)
        is NetworkStorytellerLoginResult.Success -> AuthenticateResult.Success(
            PendingServer(
                url = url,
                username = username,
                userId = "", // Storyteller's auth response doesn't expose a user id; identity is the username + token.
                token = result.token,
                insecureConnectionAllowed = insecureAllowed,
                // Storyteller contributes no browsable Library (ADR 0026) — it is a Settings-only
                // readaloud backend. The local namespace row that hosts its books as matcher input
                // is created in [commit], not surfaced to the user.
                libraries = emptyList(),
                serverType = ServerType.STORYTELLER,
            )
        )
    }

    override suspend fun commit(
        pending: PendingServer,
        hiddenLibraryIds: Set<String>,
    ): CommitServerResult = try {
        val id = UUID.randomUUID().toString()
        val entity = ServerEntity(
            id = id,
            url = pending.url.value,
            isActive = false,                    // overridden inside transaction
            insecureConnectionAllowed = pending.insecureConnectionAllowed,
            username = pending.username,
            serverType = pending.serverType.name,
        )
        // A Storyteller Server is a Settings-only readaloud backend (ADR 0026) — it must never
        // become the active browsable Server, not even as the first server added. ABS servers keep
        // the "first server becomes active" convenience.
        val inserted = if (pending.serverType == ServerType.STORYTELLER) {
            dao.upsert(entity)
            entity
        } else {
            dao.upsertAsFirstIfNoActive(entity)
        }
        tokenStorage.saveToken(id, pending.token)
        val libraryRows = pending.libraries.map {
            LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, serverId = id)
        }.toMutableList()
        // A Storyteller Server contributes no browsable Library (ADR 0026), but its readaloud books
        // are stored in `library_items` as matcher input. They need an owning `libraries` row so the
        // matcher's library→server join resolves their serverType and the server-removal cascade
        // cleans them up. This row is never surfaced in the drawer or any library picker — the
        // Server is never the active browsable Server.
        if (pending.serverType == ServerType.STORYTELLER) {
            libraryRows += LibraryEntity(
                id = readaloudLibraryId(id),
                name = "Readalouds",
                mediaType = READALOUD_MEDIA_TYPE,
                serverId = id,
            )
        }
        libraryDao.replaceAllForServer(serverId = id, libraries = libraryRows)
        hiddenLibraryIds.forEach { hidden -> visibilityStore.hideLibrary(id, hidden) }
        CommitServerResult.Success(inserted.toDomain())
    } catch (t: Throwable) {
        CommitServerResult.Failure(t)
    }

    override suspend fun setActive(serverId: String) {
        // A Storyteller Server is a Settings-only readaloud backend (ADR 0026) — it can never be the
        // active browsable Server. Enforce the invariant here so no caller (server removal, deep
        // links, future UI) can promote one, and a stale DB row can't be re-activated.
        if (dao.getById(serverId)?.serverType == ServerType.STORYTELLER.name) return
        dao.setActiveAtomic(serverId)
    }

    override suspend fun remove(serverId: String) {
        // Cascade: clear per-library items + the libraries themselves + the token + the server row.
        // For Storyteller servers this purges the synthetic Readaloud library and its books;
        // for ABS servers it cleans up real libraries and their items. ReadaloudLinks cross-server
        // cleanup belongs to #36 (matching slice) — until that lands the count of links is 0.
        libraryDao.libraryIdsForServer(serverId).forEach { libraryItemDao.deleteByLibraryId(it) }
        libraryDao.deleteByServerId(serverId)
        dao.deleteById(serverId)
        tokenStorage.deleteToken(serverId)
        // The file stores live outside Room, so the FK cascade above doesn't touch them — purge the
        // Server's downloaded/cached files here so they don't leak on disk after removal.
        filesCleaner.deleteAllForServer(serverId)
    }

    override suspend fun getServerVersion(serverId: String): String? {
        val server = dao.getById(serverId)?.toDomain() ?: return null
        // Storyteller exposes no /server-info endpoint; the UI deliberately shows no version for it.
        if (server.serverType == ServerType.STORYTELLER) return null
        val token = tokenStorage.getToken(serverId) ?: return null
        return serverInfoApi.getServerInfo(
            baseUrl = server.url.value,
            token = token,
            insecureAllowed = server.insecureConnectionAllowed,
        )
    }

    companion object {
        // The local-only library id that namespaces a Storyteller Server's readaloud rows in
        // `library_items` (matcher input; never browsable — ADR 0026). Its mediaType is the shared
        // [READALOUD_MEDIA_TYPE] so consumers can filter it out of browsable surfaces.
        fun readaloudLibraryId(serverId: String): String = "readaloud:$serverId"
    }

    private fun ServerEntity.toDomain(): Server {
        val parsedUrl = ServerUrl.parse(url)
            ?: ServerUrl.parse("https://invalid.example.com")!!
        return Server(
            id = id,
            url = parsedUrl,
            isActive = isActive,
            insecureConnectionAllowed = insecureConnectionAllowed,
            username = username,
            serverType = ServerType.fromStorageString(serverType),
        )
    }
}
