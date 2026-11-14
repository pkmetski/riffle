package com.riffle.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.Foundation.NSLock
import platform.Foundation.NSUserDefaults

/**
 * [KeyValueStore] backed by [NSUserDefaults]. Each instance observes its own [changes] flow so
 * writes to one store do not trigger re-reads in other stores — matching the DataStore isolation
 * on Android where each store owns its own DataStore file.
 *
 * All key-value reads/writes target [NSUserDefaults.standardUserDefaults], so keys across
 * different instances share the same underlying store. Per-store key namespacing is the
 * responsibility of the store implementations (e.g. `"library_filter:$sourceId:$libraryId:facet"`).
 */
class IosKeyValueStore : KeyValueStore {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val lock = NSLock()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = _changes

    private fun emit() {
        _changes.tryEmit(Unit)
    }

    override suspend fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else default

    override suspend fun getInt(key: String, default: Int): Int =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else default

    override suspend fun getFloat(key: String, default: Float): Float =
        if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else default

    override suspend fun getDouble(key: String, default: Double): Double =
        if (defaults.objectForKey(key) != null) defaults.doubleForKey(key) else default

    override suspend fun getString(key: String): String? = defaults.stringForKey(key)

    override suspend fun getLong(key: String, default: Long): Long =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key) else default

    override suspend fun getLongOrNull(key: String): Long? =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key) else null

    override suspend fun getStringSet(key: String): Set<String> {
        @Suppress("UNCHECKED_CAST")
        return (defaults.stringArrayForKey(key) as? List<String>)?.toSet() ?: emptySet()
    }

    override suspend fun setBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
        emit()
    }

    override suspend fun setInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
        emit()
    }

    override suspend fun setFloat(key: String, value: Float) {
        defaults.setFloat(value, forKey = key)
        emit()
    }

    override suspend fun setDouble(key: String, value: Double) {
        defaults.setDouble(value, forKey = key)
        emit()
    }

    override suspend fun setString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key)
        else defaults.setObject(value, forKey = key)
        emit()
    }

    override suspend fun setLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
        emit()
    }

    override suspend fun setStringSet(key: String, value: Set<String>) {
        defaults.setObject(value.toList(), forKey = key)
        emit()
    }

    override suspend fun remove(key: String) {
        defaults.removeObjectForKey(key)
        emit()
    }

    override suspend fun updateString(key: String, update: (String?) -> String): String {
        lock.lock()
        try {
            val current = defaults.stringForKey(key)
            val updated = update(current)
            defaults.setObject(updated, forKey = key)
            emit()
            return updated
        } finally {
            lock.unlock()
        }
    }
}
