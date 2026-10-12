package com.riffle.core.data

import com.riffle.core.domain.HighlightColorPreferencesStore
import com.riffle.core.models.HighlightColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed HighlightColorPreferencesStore for iOS. Keyed by (sourceId, itemId).
internal class IosHighlightColorPreferencesStoreImpl : HighlightColorPreferencesStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    // MutableStateFlow to emit on writes so collectors see updates in-session.
    private val changeSignal = MutableStateFlow(0)

    override fun lastUsedColor(sourceId: String, itemId: String): Flow<HighlightColor> =
        changeSignal.map { _ ->
            HighlightColor.fromToken(defaults.stringForKey(colorKey(sourceId, itemId)))
        }

    override suspend fun setLastUsedColor(sourceId: String, itemId: String, value: HighlightColor) {
        defaults.setObject(value.token, forKey = colorKey(sourceId, itemId))
        changeSignal.value++
    }

    override fun lastUsedIsNone(sourceId: String, itemId: String): Flow<Boolean> =
        changeSignal.map { _ ->
            defaults.stringForKey(noneKey(sourceId, itemId)) == "1"
        }

    override suspend fun setLastUsedIsNone(sourceId: String, itemId: String, value: Boolean) {
        if (value) {
            defaults.setObject("1", forKey = noneKey(sourceId, itemId))
        } else {
            defaults.removeObjectForKey(noneKey(sourceId, itemId))
        }
        changeSignal.value++
    }

    private fun colorKey(sourceId: String, itemId: String) =
        "highlight_color_${sourceId}_$itemId"

    private fun noneKey(sourceId: String, itemId: String) =
        "highlight_color_none_${sourceId}_$itemId"
}
