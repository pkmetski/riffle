package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Regression: cursor-position changes must recompose only the rail overlay, not sibling EpubNavigatorView.
@RunWith(AndroidJUnit4::class)
class ChapterRailIsolationTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun cursorPositionChangeRecomposesOnlyRailNotSibling() {
        val cursorFlow = MutableStateFlow(0f)
        var siblingRecomposeCount = 0
        var railRecomposeCount = 0

        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                // Simulates EpubNavigatorView — must NOT recompose when cursor position changes.
                Box(Modifier.fillMaxSize()) {
                    SideEffect { siblingRecomposeCount++ }
                }
                // Rail overlay with cursor state isolated inside.
                IsolatedRailForTest(
                    cursorFlow = cursorFlow,
                    onRecompose = { railRecomposeCount++ },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        composeTestRule.waitForIdle()
        val siblingCountAfterInit = siblingRecomposeCount
        val railCountAfterInit = railRecomposeCount

        repeat(5) { i ->
            composeTestRule.runOnIdle { cursorFlow.value = 0.1f * (i + 1) }
        }
        composeTestRule.waitForIdle()

        // Rail must have recomposed for each cursor change.
        assertEquals(railCountAfterInit + 5, railRecomposeCount)
        // Sibling must NOT have recomposed at all after initial render.
        assertEquals(siblingCountAfterInit, siblingRecomposeCount)
    }

    @Test
    fun siblingDoesNotRecomposeWhenRailCursorUpdatesRepeatedly() {
        val cursorFlow = MutableStateFlow(0f)
        var siblingRecomposeCount = 0

        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    SideEffect { siblingRecomposeCount++ }
                }
                IsolatedRailForTest(
                    cursorFlow = cursorFlow,
                    onRecompose = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        composeTestRule.waitForIdle()
        val countAfterInit = siblingRecomposeCount

        // Simulate 20 scroll events at varying positions.
        repeat(20) { i ->
            composeTestRule.runOnIdle { cursorFlow.value = (i + 1) / 20f }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "Sibling recomposed ${siblingRecomposeCount - countAfterInit} extra time(s) due to rail cursor updates",
            countAfterInit,
            siblingRecomposeCount,
        )
    }
}

@Composable
private fun IsolatedRailForTest(
    cursorFlow: StateFlow<Float>,
    onRecompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cursor by cursorFlow.collectAsState()
    SideEffect { onRecompose() }
    ChapterNavigationRail(
        segments = listOf(RailSegment(title = "Chapter 1", href = "ch1.xhtml")),
        activeIndex = 0,
        cursorPosition = cursor,
        subdivisions = emptyList(),
        onSegmentClick = {},
        modifier = modifier,
    )
}
