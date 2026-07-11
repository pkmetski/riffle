package com.riffle.app.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.Library
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
    fun expanded_libraryRows_doNotOverlap() {
        val books = LibraryUiItem(
            library = Library(id = "chi:books", name = "Books", mediaType = "book", isUnsupported = false),
            isVisible = true,
            switchEnabled = true,
        )
        val gramofonche = LibraryUiItem(
            library = Library(id = "chi:gramofonche", name = "Gramofonche", mediaType = "book", isUnsupported = false),
            isVisible = true,
            switchEnabled = true,
        )
        composeTestRule.setContent {
            ChitankaSourceRow(
                libraryItems = listOf(books, gramofonche),
                isExpanded = true,
                onToggleExpanded = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Books").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gramofonche").assertIsDisplayed()

        val booksBounds = composeTestRule.onNodeWithText("Books").getUnclippedBoundsInRoot()
        val gramofoncheBounds = composeTestRule.onNodeWithText("Gramofonche").getUnclippedBoundsInRoot()
        // If the two library ListItems drew on top of each other (the AnimatedVisibility→Box→stack
        // bug), both labels would land at the same origin. Assert Gramofonche's top is strictly
        // below Chitanka's bottom — the guarantee the Column wrapper inside ExpandableSourceRow
        // must give every source's expanded body.
        assertTrue(
            "Gramofonche row must be laid out below the Books library row " +
                "(Books bottom=${booksBounds.bottom}, Gramofonche top=${gramofoncheBounds.top})",
            gramofoncheBounds.top >= booksBounds.bottom,
        )
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
