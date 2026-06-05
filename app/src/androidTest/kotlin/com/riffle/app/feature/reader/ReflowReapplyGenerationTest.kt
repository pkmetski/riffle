package com.riffle.app.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.ReaderOrientation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit-tests the reflow re-apply trigger ([rememberReflowReapplyGeneration]) in isolation — no
 * WebView, no Readium. Guards bug-3: a paused readaloud highlight that landed on the wrong sentence
 * after switching orientation because nothing re-applied the decoration onto the reflowed layout.
 *
 * The generation must stay put on first composition (so merely opening a book triggers no re-apply
 * storm) and must bump after an orientation change (so the decoration effects keyed on it recompute).
 */
@RunWith(AndroidJUnit4::class)
class ReflowReapplyGenerationTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun firstCompositionDoesNotBump() {
        var generation = -1
        rule.setContent {
            generation = rememberReflowReapplyGeneration(ReaderOrientation.Vertical)
        }
        rule.waitForIdle()
        assertEquals("opening at a fixed orientation must not bump the generation", 0, generation)
    }

    @Test
    fun orientationChangeBumpsAcrossTheSettleWindow() {
        var orientation by mutableStateOf(ReaderOrientation.Vertical)
        var generation = -1
        rule.setContent {
            generation = rememberReflowReapplyGeneration(orientation)
        }
        rule.waitForIdle()
        assertEquals("baseline: no bump before any orientation change", 0, generation)

        orientation = ReaderOrientation.Horizontal

        // The trigger re-applies a few times across the relayout settle window (150 + 350 + 700 ms);
        // the auto-advancing test clock drives those delays. Three bumps in total.
        rule.waitUntil(timeoutMillis = 5_000) { generation == 3 }
        assertEquals(3, generation)
    }

    @Test
    fun eachOrientationChangeRetriggersTheBumps() {
        var orientation by mutableStateOf(ReaderOrientation.Vertical)
        var generation = -1
        rule.setContent {
            generation = rememberReflowReapplyGeneration(orientation)
        }
        rule.waitForIdle()

        orientation = ReaderOrientation.Horizontal
        rule.waitUntil(timeoutMillis = 5_000) { generation == 3 }

        // A second switch must schedule another round of re-applies, not stay latched at 3.
        orientation = ReaderOrientation.Vertical
        rule.waitUntil(timeoutMillis = 5_000) { generation == 6 }
        assertEquals(6, generation)
    }
}
