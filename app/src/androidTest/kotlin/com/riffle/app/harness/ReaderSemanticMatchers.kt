package com.riffle.app.harness

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText

/**
 * Domain-specific semantic assertion helpers for the EPUB reader screen.
 *
 * Each helper is a thin wrapper around Compose test semantics so that harness
 * tests read in terms of reader state rather than raw test tags.
 */
object ReaderSemanticMatchers {

    const val TAG_ERROR_STATE = "reader_error_state"
    const val TAG_READER_READY = "reader_ready"
    const val TAG_LOADING = "reader_loading"

    /** Asserts the reader error UI is not visible. */
    fun ComposeTestRule.assertNoErrorState() {
        val errorNodes = onAllNodesWithTag(TAG_ERROR_STATE).fetchSemanticsNodes()
        assert(errorNodes.isEmpty()) { "Expected no reader error state, but error node was found" }
    }

    /** Asserts the reader is in ready state (navigator view mounted). */
    fun ComposeTestRule.assertReaderReady(): SemanticsNodeInteraction =
        onNodeWithTag(TAG_READER_READY).assertExists()

    /** Asserts a text string is displayed somewhere in the composition. */
    fun ComposeTestRule.assertTextVisible(text: String): SemanticsNodeInteraction =
        onNodeWithText(text).assertIsDisplayed()

    /** Asserts a node with the given content description is present. */
    fun ComposeTestRule.assertContentDescriptionPresent(description: String): SemanticsNodeInteraction =
        onNodeWithContentDescription(description).assertExists()
}
