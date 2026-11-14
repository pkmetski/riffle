package com.riffle.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

/**
 * Platform-agnostic multi-key key-value store. Single-key preference stores that need only one
 * typed value should still use [PreferenceStore]; this interface is for stores that manage several
 * related keys under a shared scope (e.g. listening preferences, formatting preferences).
 *
 * Android: backed by [androidx.datastore.core.DataStore].
 * iOS: backed by [platform.Foundation.NSUserDefaults].
 */
interface KeyValueStore {
    /**
     * Emits [Unit] after every write to any key in this store. Does not replay the last value on
     * subscription — use [observe] which prepends an initial emission.
     */
    val changes: Flow<Unit>

    suspend fun getBoolean(key: String, default: Boolean): Boolean
    suspend fun getInt(key: String, default: Int): Int
    suspend fun getFloat(key: String, default: Float): Float
    suspend fun getDouble(key: String, default: Double): Double
    suspend fun getString(key: String): String?
    suspend fun getLong(key: String, default: Long): Long
    suspend fun getLongOrNull(key: String): Long?
    suspend fun getStringSet(key: String): Set<String>

    suspend fun setBoolean(key: String, value: Boolean)
    suspend fun setInt(key: String, value: Int)
    suspend fun setFloat(key: String, value: Float)
    suspend fun setDouble(key: String, value: Double)
    suspend fun setString(key: String, value: String?)
    suspend fun setLong(key: String, value: Long)
    suspend fun setStringSet(key: String, value: Set<String>)
    suspend fun remove(key: String)

    /**
     * Reads the string stored under [key] (or null when absent), applies [update], writes the
     * result, and returns it. Implementations must make the read-modify-write atomic where the
     * platform supports it (Android: DataStore `edit`; iOS: sequential read + write is acceptable).
     */
    suspend fun updateString(key: String, update: (String?) -> String): String
}

/**
 * Returns a [Flow] that immediately emits the current state via [block] on subscription, and
 * re-emits after every write to this store. [distinctUntilChanged] suppresses duplicate emissions.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <T> KeyValueStore.observe(block: suspend () -> T): Flow<T> =
    changes
        .onStart { emit(Unit) }
        .transformLatest { emit(block()) }
        .distinctUntilChanged()
