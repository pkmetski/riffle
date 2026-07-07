@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.util.DirectionalNavigationAdapter

/**
 * Regression pin for the "screen tap on the left/right edge turns the page" behaviour that
 * shipped by default with Readium's DirectionalNavigationAdapter. Riffle disables tap-edge
 * navigation so a tap only toggles immersive mode (via the paged InputListener) — swipes and
 * hardware keys still turn the page.
 *
 * This test flips red if someone reverts [createPagedDirectionalNavigationAdapter] to the
 * Readium default (which is `setOf(TapEdge.Horizontal)`).
 */
class PagedDirectionalNavigationAdapterTest {

    @Test
    fun `paged navigation adapter is configured with no tap edges`() {
        val navigator = mockk<OverflowableNavigator>(relaxed = true)

        val adapter = createPagedDirectionalNavigationAdapter(navigator)

        val field = DirectionalNavigationAdapter::class.java.getDeclaredField("tapEdges")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val tapEdges = field.get(adapter) as Set<DirectionalNavigationAdapter.TapEdge>

        assertTrue(
            "tapEdges must be empty — screen taps should not turn the page (they toggle immersive)",
            tapEdges.isEmpty(),
        )
    }
}
