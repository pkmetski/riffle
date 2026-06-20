package com.riffle.app.feature.reader

import org.readium.r2.shared.publication.Locator

/**
 * Abstracts the two navigation pipelines:
 *  - [ContinuousNavigationTarget]: continuous mode via [ContinuousNavigationView.navigateTo]
 *  - [ReadiumNavigationTarget]: paginated and scroll modes via a caller-supplied lambda
 *
 * Implementations are selected via `remember(isContinuous)` in [EpubReaderScreen] so that
 * all callers share a single `navigationTarget.navigateTo(locator)` call site.
 */
internal fun interface NavigationTarget {
    suspend fun navigateTo(locator: Locator)
}

/**
 * [NavigationTarget] for Continuous mode: delegates to [ContinuousNavigationView.navigateTo],
 * extracting href and progression from the [Locator]. A no-op when no view is available.
 */
internal class ContinuousNavigationTarget(
    private val viewProvider: () -> ContinuousNavigationView?,
) : NavigationTarget {
    override suspend fun navigateTo(locator: Locator) {
        viewProvider()?.navigateTo(
            locator.href.toString(),
            locator.locations.progression?.toFloat() ?: 0f,
        )
    }
}

/**
 * [NavigationTarget] for paginated and scroll modes: delegates to a caller-supplied [go] lambda.
 * The lambda can be `goAndSnapWithCover` (return/search/annotation nav) or a bare
 * `ColumnSnap.goAndSnap` call (background server-locator sync).
 */
internal class ReadiumNavigationTarget(
    private val go: suspend (Locator) -> Unit,
) : NavigationTarget {
    override suspend fun navigateTo(locator: Locator) = go(locator)
}
