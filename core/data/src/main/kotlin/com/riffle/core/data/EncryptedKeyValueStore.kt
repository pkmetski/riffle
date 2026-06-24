package com.riffle.core.data

/**
 * Tiny synchronous key/value contract used by config stores that need their values
 * encrypted at rest. Exists so the config-store logic can be JVM-unit-tested with an
 * in-memory fake while the production wiring binds an Android-Keystore-backed
 * implementation.
 */
interface EncryptedKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}
