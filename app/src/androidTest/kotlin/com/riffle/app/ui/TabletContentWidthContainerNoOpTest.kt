package com.riffle.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that TabletContentWidthContainer is a transparent pass-through on
 * the phone layout (Compact width bucket). Pairs with [TabletContentWidthContainerTest]
 * which covers the Expanded path on the tablet AVD.
 *
 * No `@TabletLayout` annotation: this runs on the Harness Medium Phone AVD via
 * `make harness-test` and is excluded from the tablet target.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
class TabletContentWidthContainerNoOpTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onCompact_contentFillsAvailableWidth() {
        composeTestRule.setContent {
            val sizeClass = calculateWindowSizeClass(composeTestRule.activity)
            // Sanity: the phone AVD really is below the Expanded threshold.
            check(sizeClass.widthSizeClass != WindowWidthSizeClass.Expanded) {
                "phone AVD unexpectedly resolved to Expanded; check harness config"
            }
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

        val rootWidth = (rootBounds.right - rootBounds.left).value
        val contentWidth = (contentBounds.right - contentBounds.left).value
        assertEquals(
            "content should fill the full available width on Compact (no cap applied)",
            rootWidth,
            contentWidth,
            0.5f,
        )
    }
}
