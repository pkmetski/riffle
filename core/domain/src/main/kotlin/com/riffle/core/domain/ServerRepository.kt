package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

sealed class AddServerResult {
    data class Success(val server: Server) : AddServerResult()
    data class WrongCredentials(val message: String = "Invalid username or password") : AddServerResult()
    data class NetworkError(val cause: Throwable) : AddServerResult()
    data class InsecureConnection(val type: InsecureConnectionType) : AddServerResult()
}

interface ServerRepository {
    fun observeAll(): Flow<List<Server>>
    suspend fun getActive(): Server?
    suspend fun addServer(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean = false,
    ): AddServerResult
    suspend fun setActive(serverId: String)
    suspend fun remove(serverId: String)
}
