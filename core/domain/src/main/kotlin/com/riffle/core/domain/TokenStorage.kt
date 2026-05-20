package com.riffle.core.domain

interface TokenStorage {
    suspend fun saveToken(serverId: String, token: String)
    suspend fun getToken(serverId: String): String?
    suspend fun deleteToken(serverId: String)
}
