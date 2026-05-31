package com.riffle.app.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.harness.TabletLayout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that cover grids use `GridCells.Adaptive` with the Expanded cell size
 * on the tablet AVD (≥ 840dp width per ADR 0019). The phone size class falls
 * back to ~3 columns; the Expanded size must yield more, otherwise the size
 * class indexing has regressed.
 */
@TabletLayout
@RunWith(AndroidJUnit4::class)
class AdaptiveCoverGridTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun expandedClassYieldsMoreThanThreeColumns() {
        composeTestRule.setContent {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(coverGridMinCellSize()),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(30) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .aspectRatio(0.7f)
                            .semantics { contentDescription = TILE_DESC },
                    )
                }
            }
        }

        val tileNodes = composeTestRule
            .onAllNodesWithContentDescription(TILE_DESC)
            .fetchSemanticsNodes()

        assertTrue("expected tiles to render", tileNodes.isNotEmpty())

        val firstRowTop = tileNodes.minOf { it.boundsInRoot.top }
        val firstRowCount = tileNodes.count { it.boundsInRoot.top == firstRowTop }

        // Expanded width class with our 160dp minSize must yield ≥ 4 columns.
        // The phone fallback (112dp) at <560dp width caps at 3, so this check
        // is definitive evidence the size-class branch ran.
        assertTrue(
            "expected ≥ 4 columns on Expanded, got $firstRowCount",
            firstRowCount >= 4,
        )
    }

    private companion object {
        const val TILE_DESC = "cover_tile"
    }
}
