package com.riffle.app.feature.annotations

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.data.AnnotatedBook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: the Annotations tab must render an "Annotations (N)" section
 * header above the cover grid, matching the "To Read (N)" / "All Books (N)"
 * pattern used by sibling tabs in the Library Tab Bar.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationsListHeaderTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun headerShowsBookCount() {
        val books = listOf(
            annotatedBook(itemId = "a"),
            annotatedBook(itemId = "b"),
            annotatedBook(itemId = "c"),
        )
        composeTestRule.setContent {
            AnnotationsListScreen(
                state = AnnotationsListUiState(loading = false, books = books),
                onBookClick = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Annotations (3)").assertIsDisplayed()
    }

    private fun annotatedBook(itemId: String): AnnotatedBook = AnnotatedBook(
        sourceId = "s1",
        itemId = itemId,
        title = "Book $itemId",
        author = "Author",
        coverUrl = null,
        highlightCount = 1,
        latestUpdatedAt = 0L,
    )
}
