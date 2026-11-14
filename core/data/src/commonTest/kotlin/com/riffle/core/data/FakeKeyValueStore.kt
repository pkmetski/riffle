package com.riffle.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-memory [KeyValueStore] for unit tests. Fully synchronous; thread-safe. */
class FakeKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, Any?>()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    private fun emit() { _changes.tryEmit(Unit) }

    override suspend fun getBoolean(key: String, default: Boolean) =
        (map[key] as? Boolean) ?: default

    override suspend fun getInt(key: String, default: Int) =
        (map[key] as? Int) ?: default

    override suspend fun getFloat(key: String, default: Float) =
        (map[key] as? Float) ?: default

    override suspend fun getDouble(key: String, default: Double) =
        (map[key] as? Double) ?: default

    override suspend fun getString(key: String) = map[key] as? String

    override suspend fun getLong(key: String, default: Long) =
        (map[key] as? Long) ?: default

    override suspend fun getLongOrNull(key: String) = map[key] as? Long

    @Suppress("UNCHECKED_CAST")
    override suspend fun getStringSet(key: String) =
        (map[key] as? Set<String>) ?: emptySet()

    override suspend fun setBoolean(key: String, value: Boolean) { map[key] = value; emit() }
    override suspend fun setInt(key: String, value: Int) { map[key] = value; emit() }
    override suspend fun setFloat(key: String, value: Float) { map[key] = value; emit() }
    override suspend fun setDouble(key: String, value: Double) { map[key] = value; emit() }
    override suspend fun setLong(key: String, value: Long) { map[key] = value; emit() }
    override suspend fun setStringSet(key: String, value: Set<String>) { map[key] = value; emit() }

    override suspend fun setString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
        emit()
    }

    override suspend fun remove(key: String) { map.remove(key); emit() }

    override suspend fun updateString(key: String, update: (String?) -> String): String {
        val updated = update(map[key] as? String)
        map[key] = updated
        emit()
        return updated
    }
}
