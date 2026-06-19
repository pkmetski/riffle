package com.riffle.app.feature.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CornerBookmarkIndicatorTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun notVisible_rendersNothing() {
        composeTestRule.setContent {
            CornerBookmarkIndicator(
                isBookmarked = false,
                isVisible = false,
                onToggle = {},
            )
        }
        composeTestRule.onNodeWithContentDescription("Bookmark this page").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Remove bookmark").assertDoesNotExist()
    }

    @Test
    fun visible_notBookmarked_showsBookmarkPrompt() {
        composeTestRule.setContent {
            CornerBookmarkIndicator(
                isBookmarked = false,
                isVisible = true,
                onToggle = {},
            )
        }
        composeTestRule.onNodeWithContentDescription("Bookmark this page").assertIsDisplayed()
    }

    @Test
    fun visible_bookmarked_showsRemovePrompt() {
        composeTestRule.setContent {
            CornerBookmarkIndicator(
                isBookmarked = true,
                isVisible = true,
                onToggle = {},
            )
        }
        composeTestRule.onNodeWithContentDescription("Remove bookmark").assertIsDisplayed()
    }

    @Test
    fun tap_firesToggleCallback() {
        var toggled = false
        composeTestRule.setContent {
            CornerBookmarkIndicator(
                isBookmarked = false,
                isVisible = true,
                onToggle = { toggled = true },
            )
        }
        composeTestRule.onNodeWithContentDescription("Bookmark this page").performClick()
        assertTrue("tapping the ribbon must call onToggle", toggled)
    }

    @Test
    fun customContentDescription_overridesDefault() {
        composeTestRule.setContent {
            CornerBookmarkIndicator(
                isBookmarked = false,
                isVisible = true,
                onToggle = {},
                contentDescription = "Custom description",
            )
        }
        composeTestRule.onNodeWithContentDescription("Custom description").assertIsDisplayed()
    }
}
