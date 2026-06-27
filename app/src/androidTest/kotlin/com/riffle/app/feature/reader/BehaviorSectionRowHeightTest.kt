package com.riffle.app.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Regression: the three rows in [BehaviorSection] must have equal intrinsic
 * height so the 4.dp spacers between them read as uniform vertical rhythm.
 *
 * The original bug: "Invert volume keys" used `clickable` on the Row plus a
 * real `onCheckedChange` on the Switch, which made the Switch claim its 48dp
 * minimum interactive size. Rows 1–2 passed `onCheckedChange = null`, so their
 * Switch was just its visual height. Result: row 3 was taller than rows 1–2,
 * making the gap below row 2 visually larger than the gap below row 1.
 */
@RunWith(AndroidJUnit4::class)
class BehaviorSectionRowHeightTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun allThreeRowsHaveEqualHeight() {
        composeTestRule.setContent {
            BehaviorSection(
                keepScreenOn = true,
                onKeepScreenOnChange = {},
                volumeKeyNavigationEnabled = true,
                onVolumeKeyNavigationEnabledChange = {},
                invertVolumeKeys = false,
                onInvertVolumeKeysChange = {},
            )
        }

        val keepScreen = composeTestRule.onNodeWithText("Keep screen on")
            .fetchSemanticsNode().boundsInRoot
        val volumeKeys = composeTestRule.onNodeWithText("Volume key navigation")
            .fetchSemanticsNode().boundsInRoot
        val invertKeys = composeTestRule.onNodeWithText("Invert volume keys")
            .fetchSemanticsNode().boundsInRoot

        // Each label sits in a Row whose height is dominated by the Switch's
        // touch target. If row 3's Switch claims its 48dp minimum and rows 1–2
        // don't, row 3's label sits higher within a taller row, so the gap
        // from row 2's label-bottom to row 3's label-top grows. Assert the gap
        // between consecutive labels is the same (within 1px tolerance).
        val gap12 = volumeKeys.top - keepScreen.bottom
        val gap23 = invertKeys.top - volumeKeys.bottom
        assertEquals(
            "Row gaps differ: row1→2 = $gap12, row2→3 = $gap23",
            gap12, gap23, 1f,
        )
        assert(abs(gap12 - gap23) <= 1f)
    }
}
