package com.riffle.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.harness.TabletLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Verifies that TabletContentWidthContainer caps content width at 600dp and
 * centres it horizontally when the WindowSizeClass is Expanded (ADR 0019).
 *
 * The WindowSizeClass is derived from the actual host Activity, so this test
 * also exercises the size-class calculation wiring — it relies on the
 * Harness Medium Tablet AVD resolving to the Expanded width bucket
 * (`make harness-test-tablet`). Settings is the representative screen called
 * out in the issue; its layout is a Scaffold-wrapped scrolling Column wrapped
 * by this container, which is structurally what this test exercises.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@TabletLayout
@RunWith(AndroidJUnit4::class)
class TabletContentWidthContainerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onExpanded_contentIsCappedAt600dpAndCentred() {
        composeTestRule.setContent {
            val sizeClass = calculateWindowSizeClass(composeTestRule.activity)
            TabletContentWidthContainer(
                windowSizeClass = sizeClass,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Red)
                        .testTag("content"),
                )
            }
        }

        val rootBounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val contentBounds = composeTestRule.onNodeWithTag("content").getUnclippedBoundsInRoot()

        // Sanity: the AVD really is in the Expanded bucket. If this fails the
        // harness AVD has been reconfigured below 840dp and the test would
        // otherwise silently exercise the no-op path.
        assertTrue(
            "tablet AVD root should be in the Expanded width bucket (was ${rootBounds.right - rootBounds.left})",
            (rootBounds.right - rootBounds.left).value >= 840f,
        )

        val contentWidth = (contentBounds.right - contentBounds.left).value
        assertEquals(
            "content width should be capped at 600dp",
            600f,
            contentWidth,
            0.5f,
        )

        val leftGap = (contentBounds.left - rootBounds.left).value
        val rightGap = (rootBounds.right - contentBounds.right).value
        assertTrue(
            "content should be centred horizontally (leftGap=$leftGap, rightGap=$rightGap)",
            abs(leftGap - rightGap) < 1f,
        )
    }
}
