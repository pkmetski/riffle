package com.riffle.app.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins Chitanka's Settings row to swipe-to-delete parity with every other configured-source row:
 * end-to-start swipe invokes onRemove, and no trailing "Remove" button exists. If the row is
 * un-wrapped from SwipeToDeleteRow or a Remove button is re-added, this test fails.
 */
@RunWith(AndroidJUnit4::class)
class ChitankaSourceRowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun endToStartSwipe_invokesOnRemove() {
        var removed = false
        composeTestRule.setContent {
            ChitankaSourceRow(
                libraryItems = emptyList(),
                isExpanded = false,
                onToggleExpanded = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
                onRemove = { removed = true },
            )
        }

        composeTestRule.onNodeWithTag("ChitankaSourceRow").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertTrue("swipe end-to-start on Chitanka row must invoke onRemove", removed)
    }

    @Test
    fun row_hasNoTrailingRemoveButton() {
        composeTestRule.setContent {
            ChitankaSourceRow(
                libraryItems = emptyList(),
                isExpanded = false,
                onToggleExpanded = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Remove").assertDoesNotExist()
    }
}
