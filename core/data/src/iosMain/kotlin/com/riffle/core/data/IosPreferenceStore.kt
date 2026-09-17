package com.riffle.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

/**
 * NSUserDefaults-backed [PreferenceStore] for iOS.
 * Each instance manages a single keyed value in the standard user-defaults store.
 *
 * @param key        NSUserDefaults key.
 * @param defaultValue Value to return when the key is absent.
 * @param read       Reads the typed value from defaults for [key]; return null when absent so the
 *                   store falls back to [defaultValue].
 * @param write      Persists [T] into defaults under [key].
 */
class IosPreferenceStore<T>(
    private val key: String,
    private val defaultValue: T,
    private val read: (NSUserDefaults, String) -> T?,
    private val write: (NSUserDefaults, String, T) -> Unit,
) : PreferenceStore<T> {
    private val defaults = NSUserDefaults.standardUserDefaults

    @Suppress("ktlint:standard:property-naming", "ktlint:standard:backing-property-naming")
    private val _state = MutableStateFlow(read(defaults, key) ?: defaultValue)

    override val flow: Flow<T> get() = _state

    override suspend fun update(value: T) {
        write(defaults, key, value)
        _state.value = value
    }
}
