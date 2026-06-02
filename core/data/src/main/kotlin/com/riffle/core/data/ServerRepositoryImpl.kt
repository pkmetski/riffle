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
import com.riffle.core.domain.Server
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
                libraries = listOf(syntheticReadaloudLibrary()),
                serverType = ServerType.STORYTELLER,
            )
        )
    }

    /**
     * Storyteller has no Library concept on the server — Riffle synthesizes one local Library
     * to host every readaloud (ADR 0020). The id is generated per-server at commit time; see
     * [commit].
     */
    private fun syntheticReadaloudLibrary(): Library =
        Library(
            id = SYNTHETIC_READALOUD_LIBRARY_PLACEHOLDER_ID,
            name = "Readalouds",
            mediaType = READALOUD_MEDIA_TYPE,
            isUnsupported = false,
        )

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
        val inserted = dao.upsertAsFirstIfNoActive(entity)
        tokenStorage.saveToken(id, pending.token)
        libraryDao.replaceAllForServer(
            serverId = id,
            libraries = pending.libraries.map {
                LibraryEntity(
                    // Materialise the synthetic Readaloud library with a server-scoped id so multiple
                    // Storyteller Servers can coexist (each contributes its own Readaloud Library).
                    id = if (it.id == SYNTHETIC_READALOUD_LIBRARY_PLACEHOLDER_ID) readaloudLibraryId(id) else it.id,
                    name = it.name,
                    mediaType = it.mediaType,
                    serverId = id,
                )
            },
        )
        // Hidden ids arriving from the picker may still reference the synthetic placeholder
        // for the Readaloud library; remap them to the materialised server-scoped id so the
        // visibility preference attaches to the row that actually exists.
        hiddenLibraryIds.forEach { hidden ->
            val materialisedId = if (hidden == SYNTHETIC_READALOUD_LIBRARY_PLACEHOLDER_ID) readaloudLibraryId(id) else hidden
            visibilityStore.hideLibrary(id, materialisedId)
        }
        CommitServerResult.Success(inserted.toDomain())
    } catch (t: Throwable) {
        CommitServerResult.Failure(t)
    }

    override suspend fun setActive(serverId: String) {
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
        const val SYNTHETIC_READALOUD_LIBRARY_PLACEHOLDER_ID = "readaloud:pending"
        const val READALOUD_MEDIA_TYPE = "readaloud"
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
