package com.riffle.app.harness

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

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

    /**
     * Waits for the Library Item Detail Screen's Read button to appear, then taps it.
     * Insert this between tapping a book item card and waiting for the reader to load.
     */
    fun ComposeTestRule.tapReadInDetailScreen(timeoutMillis: Long = 20_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText("Read").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Read").performClick()
    }

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

    /** Asserts the PDF reader is showing [page] (1-based) with no error state. */
    fun ComposeTestRule.assertOnPdfPage(page: Int): SemanticsNodeInteraction =
        onNode(hasTestTag(TAG_READER_READY) and hasContentDescription("page:$page", substring = true))
            .assertExists()

    /** Polls until the PDF reader shows [page] (1-based), or throws after [timeoutMillis]. */
    fun ComposeTestRule.waitUntilOnPdfPage(page: Int, timeoutMillis: Long = 20_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodes(hasTestTag(TAG_READER_READY) and hasContentDescription("page:$page", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Polls until the PDF reader shows any page number in its content description, meaning the
     * PDF has finished loading and the locator is stable. The stable initial page reported by
     * the Readium PDF navigator after load is not necessarily page 1 due to how the pdfium
     * adapter converts pdfium's 0-based page indices to 1-based Readium locator positions.
     */
    fun ComposeTestRule.waitUntilPdfLoaded(timeoutMillis: Long = 20_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodes(hasTestTag(TAG_READER_READY) and hasContentDescription("page:", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    const val TAG_RAIL = "chapter_navigation_rail"

    /**
     * Asserts the navigation rail has an active segment whose title contains [titleSubstring].
     * The active segment exposes its title via contentDescription "Active rail segment: <title>".
     */
    fun ComposeTestRule.assertRailActiveSegment(titleSubstring: String) {
        val nodes = onAllNodes(
            hasContentDescription("Active rail segment: $titleSubstring", substring = true)
        ).fetchSemanticsNodes()
        if (nodes.isEmpty()) {
            throw AssertionError(
                "Expected navigation rail to have an active segment matching '$titleSubstring' " +
                "but no such segment was found"
            )
        }
    }

    /**
     * Polls until the rail's active segment title contains [titleSubstring], or throws after [timeoutMillis].
     */
    fun ComposeTestRule.waitUntilRailActiveSegment(
        titleSubstring: String,
        timeoutMillis: Long = 15_000,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodes(
                hasContentDescription("Active rail segment: $titleSubstring", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
