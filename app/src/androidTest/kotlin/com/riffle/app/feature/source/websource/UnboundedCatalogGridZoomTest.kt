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
    fun pinchOutMakesCatalogItemsLarger() {
        var observedScale = 1f
        rule.setContent {
            var scale by remember { mutableFloatStateOf(1f) }
            CompositionLocalProvider(LocalCoverGridScale provides scale) {
                UnboundedCatalogGrid(
                    items = (0 until 30).toList(),
                    isPaging = false,
                    hasMore = false,
                    onLoadMore = {},
                    onCoverScaleChange = {
                        scale = it
                        observedScale = it
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

        val columnsBefore = firstVisibleRowCount()
        rule.onRoot().performTouchInput {
            pinch(
                start0 = Offset(centerX - 24f, centerY),
                end0 = Offset(left + 16f, centerY),
                start1 = Offset(centerX + 24f, centerY),
                end1 = Offset(right - 16f, centerY),
            )
        }
        rule.waitForIdle()

        val columnsAfter = firstVisibleRowCount()
        assertTrue("pinch-out should increase the cover scale", observedScale > 1f)
        assertTrue(
            "larger covers should reduce the first-row column count ($columnsBefore -> $columnsAfter)",
            columnsAfter < columnsBefore,
        )
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
