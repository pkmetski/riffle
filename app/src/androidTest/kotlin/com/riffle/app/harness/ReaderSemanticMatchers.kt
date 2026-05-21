package com.riffle.app.harness

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
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
    const val TAG_TOC_PANEL = "toc_panel"

    /** Asserts the reader error UI is not visible. */
    fun ComposeTestRule.assertNoErrorState() {
        val errorNodes = onAllNodesWithTag(TAG_ERROR_STATE).fetchSemanticsNodes()
        if (errorNodes.isNotEmpty()) {
            val text = runCatching {
                onNodeWithTag(TAG_ERROR_STATE)
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .joinToString()
            }.getOrElse { "<unreadable>" }
            throw AssertionError("Expected no reader error state, but got: $text")
        }
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

    /**
     * Asserts the reader's current locator href contains [hrefSubstring].
     * The reader view exposes its current locator href as a semantic content description,
     * allowing tests to verify which chapter/resource is displayed without inspecting WebView content.
     */
    fun ComposeTestRule.assertInChapter(hrefSubstring: String): SemanticsNodeInteraction =
        onNode(hasTestTag(TAG_READER_READY) and hasContentDescription(hrefSubstring, substring = true))
            .assertExists()

    /**
     * Polls until the reader's locator href contains [hrefSubstring], or throws after [timeoutMillis].
     * Use this after triggering async navigation (e.g. TOC tap) where the locator updates with a delay.
     */
    fun ComposeTestRule.waitUntilInChapter(hrefSubstring: String, timeoutMillis: Long = 15_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodes(hasTestTag(TAG_READER_READY) and hasContentDescription(hrefSubstring, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
