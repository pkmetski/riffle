package com.riffle.shared.library

import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.ContentCacheSettingsStore.Companion.DEFAULT_AUTO_CLEAR
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

private const val KEY_AUTO_CLEAR = "riffle.contentCacheAutoClear"

internal class IosContentCacheSettingsStoreImpl : ContentCacheSettingsStore {

    private val prefs = NSUserDefaults.standardUserDefaults

    private val _autoClear = MutableStateFlow(load())
    override val autoClear: Flow<ContentCacheAutoClear> = _autoClear.asStateFlow()

    override suspend fun setAutoClear(value: ContentCacheAutoClear) {
        prefs.setObject(value.name, KEY_AUTO_CLEAR)
        _autoClear.value = value
    }

    private fun load(): ContentCacheAutoClear {
        val stored = prefs.stringForKey(KEY_AUTO_CLEAR) ?: return DEFAULT_AUTO_CLEAR
        return ContentCacheAutoClear.entries.firstOrNull { it.name == stored } ?: DEFAULT_AUTO_CLEAR
    }
}
