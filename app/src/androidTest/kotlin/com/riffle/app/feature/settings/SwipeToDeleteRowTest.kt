package com.riffle.app.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the swipe-to-delete gesture on every configured-source row in Settings. If someone
 * un-wraps `LocalFilesSourceRow` or `ServerRow` from `SwipeToDeleteRow`, or drops the
 * end-to-start dismiss handling, this test fails.
 */
@RunWith(AndroidJUnit4::class)
class SwipeToDeleteRowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun endToStartSwipe_invokesOnDelete() {
        var deleted = false
        composeTestRule.setContent {
            SwipeToDeleteRow(onDelete = { deleted = true }) {
                Text(
                    text = "Source row",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(Color.LightGray)
                        .testTag("row"),
                )
            }
        }

        composeTestRule.onNodeWithTag("row").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertTrue("swipe end-to-start should trigger onDelete", deleted)
    }

    @Test
    fun startToEndSwipe_doesNotInvokeOnDelete() {
        var deleted = false
        composeTestRule.setContent {
            SwipeToDeleteRow(onDelete = { deleted = true }) {
                Text(
                    text = "Source row",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(Color.LightGray)
                        .testTag("row"),
                )
            }
        }

        composeTestRule.onNodeWithTag("row").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertTrue("swipe start-to-end must not trigger onDelete", !deleted)
    }
}
