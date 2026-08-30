package com.riffle.core.data

import com.riffle.core.domain.TokenStorage
import platform.Foundation.NSUserDefaults

// TODO: replace with Keychain-backed storage before shipping to production iOS
class IosTokenStorage : TokenStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun saveToken(sourceId: String, token: String) {
        defaults.setObject(token, forKey = tokenKey(sourceId))
    }

    override suspend fun getToken(sourceId: String): String? =
        defaults.stringForKey(tokenKey(sourceId))

    override suspend fun deleteToken(sourceId: String) {
        defaults.removeObjectForKey(tokenKey(sourceId))
    }

    override suspend fun savePassword(sourceId: String, password: String) {
        defaults.setObject(password, forKey = passwordKey(sourceId))
    }

    override suspend fun getPassword(sourceId: String): String? =
        defaults.stringForKey(passwordKey(sourceId))

    override suspend fun deletePassword(sourceId: String) {
        defaults.removeObjectForKey(passwordKey(sourceId))
    }

    private fun tokenKey(sourceId: String) = "token:$sourceId"
    private fun passwordKey(sourceId: String) = "password:$sourceId"
}
