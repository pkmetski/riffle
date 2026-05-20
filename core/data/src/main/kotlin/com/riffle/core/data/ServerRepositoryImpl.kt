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
                val isFirst = dao.getActive() == null
                val id = UUID.randomUUID().toString()
                val displayName = url.value
                    .substringAfter("://")
                    .substringBefore("/")
                    .substringBefore(":")
                val entity = ServerEntity(
                    id = id,
                    url = url.value,
                    displayName = displayName,
                    isActive = isFirst,
                    insecureConnectionAllowed = insecureAllowed,
                )
                dao.upsert(entity)
                tokenStorage.saveToken(id, networkResult.token)
                AddServerResult.Success(entity.toDomain())
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
        dao.clearActiveFlag()
        dao.setActive(serverId)
    }

    override suspend fun remove(serverId: String) {
        dao.deleteById(serverId)
        tokenStorage.deleteToken(serverId)
    }

    private fun ServerEntity.toDomain(): Server = Server(
        id = id,
        url = ServerUrl.parse(url)!!,
        displayName = displayName,
        isActive = isActive,
        insecureConnectionAllowed = insecureConnectionAllowed,
    )
}
