package com.riffle.core.data

import com.riffle.core.domain.DeviceLabelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

private const val KEY_DEVICE_LABEL = "riffle_device_label"
private const val MAX_LABEL_CHARS = 40

/**
 * NSUserDefaults-backed [DeviceLabelStore] for iOS.
 *
 * Mirrors [DeviceLabelStoreImpl] (Android) but uses [NSUserDefaults] instead of DataStore.
 * The in-memory [MutableStateFlow] keeps composables reactive without polling; it is
 * initialised from whatever is currently stored and updated on every [set] call.
 */
class IosDeviceLabelStoreImpl : DeviceLabelStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    @Suppress("ktlint:standard:property-naming", "ktlint:standard:backing-property-naming")
    private val _state = MutableStateFlow(defaults.stringForKey(KEY_DEVICE_LABEL))

    override fun observe(): Flow<String?> = _state.asStateFlow()

    override suspend fun get(): String? = defaults.stringForKey(KEY_DEVICE_LABEL)

    override suspend fun set(label: String?) {
        val trimmed = label?.trim()?.take(MAX_LABEL_CHARS)
        if (trimmed.isNullOrEmpty()) {
            defaults.removeObjectForKey(KEY_DEVICE_LABEL)
            _state.value = null
        } else {
            defaults.setObject(trimmed, forKey = KEY_DEVICE_LABEL)
            _state.value = trimmed
        }
    }
}
