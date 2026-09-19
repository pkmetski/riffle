package com.riffle.core.data

import com.riffle.core.common.EncryptedKeyValueStore

// Keychain-backed EncryptedKeyValueStore for iOS, using the same generic-password primitives as
// IosTokenStorage under a distinct Keychain service namespace so config-store keys never collide
// with token/password accounts.
class IosEncryptedKeyValueStore : EncryptedKeyValueStore {
    override fun get(key: String): String? = loadKeychainItem(SERVICE, key)
    override fun put(key: String, value: String) = saveKeychainItem(SERVICE, key, value)
    override fun remove(key: String) = deleteKeychainItem(SERVICE, key)

    private companion object {
        const val SERVICE = "com.riffle.app.config"
    }
}
