package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class ServerRepositoryImpl @Inject constructor(
    private val dao: ServerDao,
    private val tokenStorage: TokenStorage,
    private val absApiClient: AbsApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val libraryApi: AbsLibraryApi,
    private val libraryDao: LibraryDao,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
) : ServerRepository {

    override fun observeAll(): Flow<List<Server>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getActive(): Server? = dao.getActive()?.toDomain()

    override suspend fun authenticate(
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
                            displayName = displayNameFrom(url.value),
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

    override suspend fun commit(
        pending: PendingServer,
        hiddenLibraryIds: Set<String>,
    ): CommitServerResult = try {
        val id = UUID.randomUUID().toString()
        val entity = ServerEntity(
            id = id,
            url = pending.url.value,
            displayName = pending.displayName,
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
                    id = it.id,
                    name = it.name,
                    mediaType = it.mediaType,
                    serverId = id,
                )
            },
        )
        hiddenLibraryIds.forEach { visibilityStore.hideLibrary(id, it) }
        CommitServerResult.Success(inserted.toDomain())
    } catch (t: Throwable) {
        CommitServerResult.Failure(t)
    }

    override suspend fun setActive(serverId: String) {
        dao.setActiveAtomic(serverId)
    }

    override suspend fun remove(serverId: String) {
        dao.deleteById(serverId)
        tokenStorage.deleteToken(serverId)
    }

    override suspend fun getServerVersion(serverId: String): String? {
        val server = dao.getById(serverId)?.toDomain() ?: return null
        val token = tokenStorage.getToken(serverId) ?: return null
        return serverInfoApi.getServerInfo(
            baseUrl = server.url.value,
            token = token,
            insecureAllowed = server.insecureConnectionAllowed,
        )
    }

    private fun displayNameFrom(url: String): String =
        try {
            java.net.URI(url).host ?: url.substringAfter("://").substringBefore("/")
        } catch (_: Exception) {
            url.substringAfter("://").substringBefore("/")
        }

    private fun ServerEntity.toDomain(): Server {
        val parsedUrl = ServerUrl.parse(url)
            ?: ServerUrl.parse("https://invalid.example.com")!!
        return Server(
            id = id,
            url = parsedUrl,
            displayName = displayName,
            isActive = isActive,
            insecureConnectionAllowed = insecureConnectionAllowed,
            username = username,
            serverType = ServerType.fromStorageString(serverType),
        )
    }
}
