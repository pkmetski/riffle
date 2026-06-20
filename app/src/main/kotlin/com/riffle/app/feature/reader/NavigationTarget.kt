package com.riffle.app.feature.reader

import org.readium.r2.shared.publication.Locator

/**
 * Abstracts the two navigation pipelines:
 *  - [ContinuousNavigationTarget]: continuous mode via [ContinuousNavigationView.navigateTo]
 *  - [ReadiumNavigationTarget]: paginated and scroll modes via a caller-supplied lambda
 *
 * Implementations are selected via `remember(isContinuous)` in [EpubReaderScreen] so that
 * all callers share a single `navigationTarget.navigateTo(locator)` call site.
 *
 * [alignToTop] is continuous-mode-only: set true when the locator's progression was measured at
 * content top (e.g. CFI-derived bookmark positions) rather than the viewport midpoint.
 */
internal interface NavigationTarget {
    suspend fun navigateTo(locator: Locator, alignToTop: Boolean = false)
}

/**
 * [NavigationTarget] for Continuous mode: delegates to [ContinuousNavigationView.navigateTo],
 * extracting href (plus any fragment anchor) and progression from the [Locator]. A no-op when no
 * view is available.
 */
internal class ContinuousNavigationTarget(
    private val viewProvider: () -> ContinuousNavigationView?,
) : NavigationTarget {
    override suspend fun navigateTo(locator: Locator, alignToTop: Boolean) {
        val anchor = locator.locations.fragments.firstOrNull()
        val href = if (anchor != null) "${locator.href}#$anchor" else locator.href.toString()
        viewProvider()?.navigateTo(
            href,
            locator.locations.progression?.toFloat() ?: 0f,
            alignToTop,
        )
    }
}

/**
 * [NavigationTarget] for paginated and scroll modes: delegates to a caller-supplied [go] lambda.
 * The lambda can be `goAndSnapWithCover` (return/search/annotation nav) or a bare
 * `ColumnSnap.goAndSnap` call (background server-locator sync). [alignToTop] is ignored — Readium
 * handles column alignment internally.
 */
internal class ReadiumNavigationTarget(
    private val go: suspend (Locator) -> Unit,
) : NavigationTarget {
    override suspend fun navigateTo(locator: Locator, alignToTop: Boolean) = go(locator)
}
