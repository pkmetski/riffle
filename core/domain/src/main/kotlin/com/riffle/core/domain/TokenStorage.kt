package com.riffle.core.domain

interface TokenStorage {
    suspend fun saveToken(serverId: String, token: String)
    suspend fun getToken(serverId: String): String?
    suspend fun deleteToken(serverId: String)
    /**
     * Persist the user-entered password alongside the token so the AddServer edit flow can
     * prefill it. Default no-ops let test fakes that only care about the token compile unchanged;
     * production [KeystoreTokenStorage] overrides them.
     */
    suspend fun savePassword(serverId: String, password: String) {}
    suspend fun getPassword(serverId: String): String? = null
    suspend fun deletePassword(serverId: String) {}
}
