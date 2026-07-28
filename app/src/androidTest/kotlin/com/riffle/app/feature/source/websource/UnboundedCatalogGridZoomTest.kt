package com.riffle.app.feature.source.websource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.library.LocalCoverGridScale
import com.riffle.app.feature.library.MAX_COVER_SCALE
import com.riffle.app.feature.library.MIN_COVER_SCALE
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the Chitanka/Gutenberg unbounded-catalog grids. These screens used
 * fixed columns and omitted the library pinch modifier, so the global cover-density preference
 * could neither be changed nor reflected while browsing them.
 */
@RunWith(AndroidJUnit4::class)
class UnboundedCatalogGridZoomTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun pinchOutIncreasesCoverScale() {
        var observedScale = 1f
        setGridContent(onScaleObserved = { observedScale = it })

        rule.onRoot().performTouchInput {
            pinch(
                start0 = Offset(centerX - 24f, centerY),
                end0 = Offset(left + 16f, centerY),
                start1 = Offset(centerX + 24f, centerY),
                end1 = Offset(right - 16f, centerY),
            )
        }
        rule.waitForIdle()

        assertTrue("pinch-out should increase the cover scale", observedScale > 1f)
    }

    @Test
    fun pinchInMakesCatalogItemsSmaller() {
        var observedScale = 1f
        setGridContent(onScaleObserved = { observedScale = it })

        rule.onRoot().performTouchInput {
            pinch(
                start0 = Offset(left + 16f, centerY),
                end0 = Offset(centerX - 24f, centerY),
                start1 = Offset(right - 16f, centerY),
                end1 = Offset(centerX + 24f, centerY),
            )
        }
        rule.waitForIdle()

        assertTrue("pinch-in should decrease the cover scale", observedScale < 1f)
    }

    @Test
    fun scaleBoundsReflowCatalogGridInBothDirections() {
        lateinit var updateScale: (Float) -> Unit
        setGridContent(onScaleObserved = {}, onScaleSetter = { updateScale = it })

        val defaultColumns = firstVisibleRowCount()
        rule.runOnIdle { updateScale(MAX_COVER_SCALE) }
        rule.waitForIdle()
        val largeCoverColumns = firstVisibleRowCount()

        rule.runOnIdle { updateScale(MIN_COVER_SCALE) }
        rule.waitForIdle()
        val smallCoverColumns = firstVisibleRowCount()

        assertTrue(
            "maximum scale should reduce columns ($defaultColumns -> $largeCoverColumns)",
            largeCoverColumns < defaultColumns,
        )
        assertTrue(
            "minimum scale should increase columns ($defaultColumns -> $smallCoverColumns)",
            smallCoverColumns > defaultColumns,
        )
    }

    private fun setGridContent(
        onScaleObserved: (Float) -> Unit,
        onScaleSetter: ((Float) -> Unit) -> Unit = {},
    ) {
        rule.setContent {
            var scale by remember { mutableFloatStateOf(1f) }
            onScaleSetter { scale = it }
            CompositionLocalProvider(LocalCoverGridScale provides scale) {
                UnboundedCatalogGrid(
                    items = (0 until 30).toList(),
                    isPaging = false,
                    hasMore = false,
                    onLoadMore = {},
                    onCoverScaleChange = {
                        scale = it
                        onScaleObserved(it)
                    },
                    itemKey = { it },
                ) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(2f / 3f)
                            .semantics { contentDescription = TILE_DESCRIPTION },
                    )
                }
            }
        }
    }

    private fun firstVisibleRowCount(): Int {
        val nodes = rule.onAllNodesWithContentDescription(TILE_DESCRIPTION).fetchSemanticsNodes()
        assertTrue("expected catalog tiles to render", nodes.isNotEmpty())
        val firstRowTop = nodes.minOf { it.boundsInRoot.top }
        return nodes.count { it.boundsInRoot.top == firstRowTop }
    }

    private companion object {
        const val TILE_DESCRIPTION = "catalog_cover_tile"
    }
}
