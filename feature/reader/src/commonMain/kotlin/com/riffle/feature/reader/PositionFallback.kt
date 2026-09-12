package com.riffle.feature.reader

import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository

/**
 * Loads the saved reading position for [itemId], falling back to the active source's row when
 * the item's own [sourceId] has no entry.
 *
 * Before PR #999 positions were keyed on `getActive()?.id` rather than the item's own sourceId,
 * so books read before the fix have their positions stored under the active (ABS) source. This
 * fallback lets those books resume at the correct position on the first open after the fix,
 * instead of silently landing at the cover. Once the user reads past the current position, a new
 * row is written under [sourceId] and the fallback stops firing.
 */
suspend fun loadPositionWithActiveFallback(
    store: ReadingPositionStore,
    sourceRepository: SourceRepository,
    sourceId: String,
    itemId: String,
): String? = store.load(sourceId, itemId)
    ?: sourceRepository.getActive()?.id?.takeIf { it != sourceId }
        ?.let { activeId -> store.load(activeId, itemId) }
