package com.riffle.app.feature.library

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard: the search text field must not grab focus when entering the library screen.
 * Auto-focus pops the keyboard and activates the text cursor before the user has expressed
 * any intent to search, which is disruptive.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySearchHeaderFocusTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun searchFieldIsNotFocusedOnEntry() {
        composeTestRule.setContent {
            LibrarySearchHeader(
                libraryName = "My Library",
                searchQuery = "",
                onSearchQueryChange = {},
                onOpenDrawer = {},
            )
        }

        composeTestRule.waitForIdle()

        composeTestRule
            .onNode(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
    }
}
