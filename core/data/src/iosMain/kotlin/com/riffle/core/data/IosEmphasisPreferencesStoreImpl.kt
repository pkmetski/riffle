package com.riffle.core.data

import com.riffle.core.domain.EmphasisPreferencesStore
import com.riffle.core.models.EmphasisStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed EmphasisPreferencesStore for iOS. Keyed by (sourceId, itemId).
internal class IosEmphasisPreferencesStoreImpl : EmphasisPreferencesStore {

    private val defaults = NSUserDefaults.standardUserDefaults
    // MutableStateFlow to emit on writes so collectors see updates in-session.
    private val changeSignal = MutableStateFlow(0)

    override fun lastUsedStyles(sourceId: String, itemId: String): Flow<Set<EmphasisStyle>> =
        changeSignal.map { _ ->
            EmphasisStyle.decode(defaults.stringForKey(key(sourceId, itemId))) ?: emptySet()
        }

    override suspend fun setLastUsedStyles(sourceId: String, itemId: String, value: Set<EmphasisStyle>) {
        val encoded = EmphasisStyle.encode(value)
        if (encoded != null) {
            defaults.setObject(encoded, forKey = key(sourceId, itemId))
        } else {
            defaults.removeObjectForKey(key(sourceId, itemId))
        }
        changeSignal.value++
    }

    private fun key(sourceId: String, itemId: String) = "emphasis_styles_${sourceId}_$itemId"
}
