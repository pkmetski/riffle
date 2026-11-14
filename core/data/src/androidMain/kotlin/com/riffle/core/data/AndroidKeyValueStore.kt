package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [KeyValueStore] backed by [DataStore]. Each store instance wraps its own DataStore file so keys
 * from different stores never collide and no existing stored user data is lost on upgrade.
 */
class AndroidKeyValueStore(private val dataStore: DataStore<Preferences>) : KeyValueStore {

    override val changes: Flow<Unit> = dataStore.data.map { }

    override suspend fun getBoolean(key: String, default: Boolean): Boolean =
        dataStore.data.first()[booleanPreferencesKey(key)] ?: default

    override suspend fun getInt(key: String, default: Int): Int =
        dataStore.data.first()[intPreferencesKey(key)] ?: default

    override suspend fun getFloat(key: String, default: Float): Float =
        dataStore.data.first()[floatPreferencesKey(key)] ?: default

    override suspend fun getDouble(key: String, default: Double): Double =
        dataStore.data.first()[doublePreferencesKey(key)] ?: default

    override suspend fun getString(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun getLong(key: String, default: Long): Long =
        dataStore.data.first()[longPreferencesKey(key)] ?: default

    override suspend fun getLongOrNull(key: String): Long? =
        dataStore.data.first()[longPreferencesKey(key)]

    override suspend fun getStringSet(key: String): Set<String> =
        dataStore.data.first()[stringSetPreferencesKey(key)].orEmpty()

    override suspend fun setBoolean(key: String, value: Boolean) {
        dataStore.edit { it[booleanPreferencesKey(key)] = value }
    }

    override suspend fun setInt(key: String, value: Int) {
        dataStore.edit { it[intPreferencesKey(key)] = value }
    }

    override suspend fun setFloat(key: String, value: Float) {
        dataStore.edit { it[floatPreferencesKey(key)] = value }
    }

    override suspend fun setDouble(key: String, value: Double) {
        dataStore.edit { it[doublePreferencesKey(key)] = value }
    }

    override suspend fun setString(key: String, value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(stringPreferencesKey(key))
            else prefs[stringPreferencesKey(key)] = value
        }
    }

    override suspend fun setLong(key: String, value: Long) {
        dataStore.edit { it[longPreferencesKey(key)] = value }
    }

    override suspend fun setStringSet(key: String, value: Set<String>) {
        dataStore.edit { it[stringSetPreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { prefs ->
            // Remove under all possible typed keys; only the one that exists will have an effect.
            prefs.removeIfPresent(booleanPreferencesKey(key))
            prefs.removeIfPresent(intPreferencesKey(key))
            prefs.removeIfPresent(floatPreferencesKey(key))
            prefs.removeIfPresent(doublePreferencesKey(key))
            prefs.removeIfPresent(stringPreferencesKey(key))
            prefs.removeIfPresent(longPreferencesKey(key))
            prefs.removeIfPresent(stringSetPreferencesKey(key))
        }
    }

    override suspend fun updateString(key: String, update: (String?) -> String): String {
        var result = ""
        dataStore.edit { prefs ->
            val current = prefs[stringPreferencesKey(key)]
            val updated = update(current)
            prefs[stringPreferencesKey(key)] = updated
            result = updated
        }
        return result
    }

    private fun <T> MutablePreferences.removeIfPresent(key: Preferences.Key<T>) {
        if (this[key] != null) remove(key)
    }
}
