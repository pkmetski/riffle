package com.riffle.core.domain

interface TokenStorage {
    suspend fun saveToken(sourceId: String, token: String)
    suspend fun getToken(sourceId: String): String?
    suspend fun deleteToken(sourceId: String)
    /**
     * Persist the user-entered password alongside the token so the AddServer edit flow can
     * prefill it. Default no-ops let test fakes that only care about the token compile unchanged;
     * production [KeystoreTokenStorage] overrides them.
     */
    suspend fun savePassword(sourceId: String, password: String) {}
    suspend fun getPassword(sourceId: String): String? = null
    suspend fun deletePassword(sourceId: String) {}
}
