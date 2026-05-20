package com.riffle.core.data

import com.riffle.core.database.ServerDao
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.NetworkLoginResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class ServerRepositoryImpl @Inject constructor(
    private val dao: ServerDao,
    private val tokenStorage: TokenStorage,
    private val absApiClient: AbsApi,
) : ServerRepository {

    override fun observeAll(): Flow<List<Server>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getActive(): Server? = dao.getActive()?.toDomain()

    override suspend fun addServer(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AddServerResult {
        val networkResult = absApiClient.login(url.value, username, password, insecureAllowed)
        return when (networkResult) {
            is NetworkLoginResult.Success -> {
                val id = UUID.randomUUID().toString()
                val entity = ServerEntity(
                    id = id,
                    url = url.value,
                    displayName = displayNameFrom(url.value),
                    isActive = false,                    // overridden inside transaction
                    insecureConnectionAllowed = insecureAllowed,
                )
                val inserted = dao.upsertAsFirstIfNoActive(entity)
                tokenStorage.saveToken(id, networkResult.token)
                AddServerResult.Success(inserted.toDomain())
            }
            is NetworkLoginResult.WrongCredentials ->
                AddServerResult.WrongCredentials(networkResult.message)
            is NetworkLoginResult.NetworkError ->
                AddServerResult.NetworkError(networkResult.cause)
            is NetworkLoginResult.InsecureConnection ->
                AddServerResult.InsecureConnection(networkResult.type)
        }
    }

    override suspend fun setActive(serverId: String) {
        dao.setActiveAtomic(serverId)
    }

    override suspend fun remove(serverId: String) {
        dao.deleteById(serverId)
        tokenStorage.deleteToken(serverId)
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
        )
    }
}
