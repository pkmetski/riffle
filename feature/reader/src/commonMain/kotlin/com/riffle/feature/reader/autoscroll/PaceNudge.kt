package com.riffle.feature.reader.autoscroll

import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.models.ScreenDimensionBucket

suspend fun nudgeAndPersistPace(
    store: BookFormattingPreferencesStore,
    sourceId: String,
    itemId: String,
    dimension: ScreenDimensionBucket,
    current: BookFormattingOverrides,
    newWpm: Int?,
    set: (BookFormattingOverrides, Int) -> BookFormattingOverrides,
): BookFormattingOverrides? {
    val updated = newWpm?.let { set(current, it) } ?: return null
    store.save(sourceId, itemId, dimension, updated)
    return updated
}
