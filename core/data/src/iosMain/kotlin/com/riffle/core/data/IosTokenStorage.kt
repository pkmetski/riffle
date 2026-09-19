package com.riffle.core.data

import com.riffle.core.domain.TokenStorage

class IosTokenStorage : TokenStorage {
    override suspend fun saveToken(sourceId: String, token: String) = saveKeychainItem(KEYCHAIN_SERVICE, tokenAccount(sourceId), token)
    override suspend fun getToken(sourceId: String): String? = loadKeychainItem(KEYCHAIN_SERVICE, tokenAccount(sourceId))
    override suspend fun deleteToken(sourceId: String) = deleteKeychainItem(KEYCHAIN_SERVICE, tokenAccount(sourceId))
    override suspend fun savePassword(sourceId: String, password: String) = saveKeychainItem(KEYCHAIN_SERVICE, passwordAccount(sourceId), password)
    override suspend fun getPassword(sourceId: String): String? = loadKeychainItem(KEYCHAIN_SERVICE, passwordAccount(sourceId))
    override suspend fun deletePassword(sourceId: String) = deleteKeychainItem(KEYCHAIN_SERVICE, passwordAccount(sourceId))
    private fun tokenAccount(sourceId: String) = "token:$sourceId"
    private fun passwordAccount(sourceId: String) = "password:$sourceId"
}
