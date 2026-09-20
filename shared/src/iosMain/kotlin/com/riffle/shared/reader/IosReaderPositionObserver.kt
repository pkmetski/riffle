package com.riffle.shared.reader

import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.PositionSaveCoordinator
import kotlinx.coroutines.flow.Flow

/**
 * What the iOS EPUB reader does with every position the navigator emits.
 *
 * Lives outside the composable so the policy is testable: the composable only supplies the flow,
 * the coordinator and the lazy-publication accessors.
 *
 * iOS used to persist nothing while reading — it wrote the locator once, from `onDispose`. A
 * force-quit or a crash mid-book therefore lost the entire session, and a background kill (which
 * iOS does routinely) never runs `onDispose` at all. Android has always saved on the hot path via
 * [PositionSaveCoordinator]; this routes iOS through the same coordinator so both platforms share
 * one policy: **locator on every change, `readingProgress` only on close** (see the coordinator's
 * KDoc for why the float must stay off the hot path and the locator off the cold one).
 */
internal suspend fun observeReaderPositions(
    positions: Flow<NavigatorPosition>,
    positionSaver: PositionSaveCoordinator<NavigatorPosition>,
    lazyShape: () -> LazyPublicationShape?,
    prefetchNext: (Int) -> Unit,
) {
    positions.collect { position ->
        positionSaver.onChanged(position)

        val shape = lazyShape() ?: return@collect
        val currentIndex = shape.spine.indexOfFirst { it.fullPath == position.href }
        if (currentIndex >= 0) prefetchNext(currentIndex)
    }
}
