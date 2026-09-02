package com.riffle.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// NSUserDefaults-backed PreferenceStore for iOS.
// Each instance manages a single keyed value in the standard user defaults store.
class IosPreferenceStore<T>(
    private val key: String,
    private val defaultValue: T,
    private val write: (T) -> Any?,
) : PreferenceStore<T> {
    @Suppress("ktlint:standard:property-naming")
    private val _state = MutableStateFlow(defaultValue)

    override val flow: Flow<T> get() = _state

    override suspend fun update(value: T) {
        platform.Foundation.NSUserDefaults.standardUserDefaults.setObject(
            write(value),
            forKey = key,
        )
        _state.value = value
    }
}
