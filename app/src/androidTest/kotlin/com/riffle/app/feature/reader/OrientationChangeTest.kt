package com.riffle.app.feature.reader

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the reader screen survives Activity recreation (as triggered by rotation)
 * without navigating back or going blank.
 */
@RunWith(AndroidJUnit4::class)
class OrientationChangeTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun readerScreenSurvivesActivityRecreation() {
        val loadingMatcher = hasTestTag("reader_loading")
        val readyMatcher = hasTestTag("reader_ready")

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodes(loadingMatcher).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodes(readyMatcher).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("reader_loading").assertExists()
    }
}
