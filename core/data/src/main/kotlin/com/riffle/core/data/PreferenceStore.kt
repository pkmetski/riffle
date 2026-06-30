package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Generic flow/update facade over a single-key `DataStore<Preferences>` entry.
 *
 * Domain-specific stores compose one of these per typed value via [PrefCodecs] and
 * a top-level factory function (e.g. `AppThemeStore(ds)`), so the interface
 * vocabulary stays domain-shaped while the read/write plumbing lives here.
 */
class PreferenceStore<T>(
    private val dataStore: DataStore<Preferences>,
    private val read: (Preferences) -> T,
    private val write: (MutablePreferences, T) -> Unit,
) {
    val flow: Flow<T> = dataStore.data.map(read).distinctUntilChanged()

    suspend fun update(value: T) {
        dataStore.edit { prefs -> write(prefs, value) }
    }
}

/** Pairs a single-key reader with its writer. Hand to [preferenceStore]. */
data class PreferenceCodec<T>(
    val read: (Preferences) -> T,
    val write: (MutablePreferences, T) -> Unit,
)

fun <T> preferenceStore(
    dataStore: DataStore<Preferences>,
    codec: PreferenceCodec<T>,
): PreferenceStore<T> = PreferenceStore(dataStore, codec.read, codec.write)

object PrefCodecs {
    fun boolean(name: String, default: Boolean): PreferenceCodec<Boolean> {
        val key = booleanPreferencesKey(name)
        return PreferenceCodec(
            read = { prefs -> prefs[key] ?: default },
            write = { prefs, value -> prefs[key] = value },
        )
    }

    fun int(name: String, default: Int): PreferenceCodec<Int> {
        val key = intPreferencesKey(name)
        return PreferenceCodec(
            read = { prefs -> prefs[key] ?: default },
            write = { prefs, value -> prefs[key] = value },
        )
    }

    fun float(name: String, default: Float): PreferenceCodec<Float> {
        val key = floatPreferencesKey(name)
        return PreferenceCodec(
            read = { prefs -> prefs[key] ?: default },
            write = { prefs, value -> prefs[key] = value },
        )
    }

    fun double(name: String, default: Double): PreferenceCodec<Double> {
        val key = doublePreferencesKey(name)
        return PreferenceCodec(
            read = { prefs -> prefs[key] ?: default },
            write = { prefs, value -> prefs[key] = value },
        )
    }

    fun string(name: String, default: String): PreferenceCodec<String> {
        val key = stringPreferencesKey(name)
        return PreferenceCodec(
            read = { prefs -> prefs[key] ?: default },
            write = { prefs, value -> prefs[key] = value },
        )
    }

    /** Unknown/missing names fall back to [default] rather than crashing on a renamed enum. */
    fun <E : Enum<E>> enum(name: String, default: E, values: Array<E>): PreferenceCodec<E> {
        val key = stringPreferencesKey(name)
        return PreferenceCodec(
            read = { prefs ->
                prefs[key]?.let { stored -> values.firstOrNull { it.name == stored } } ?: default
            },
            write = { prefs, value -> prefs[key] = value.name },
        )
    }
}
