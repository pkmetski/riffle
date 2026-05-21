package com.riffle.app.harness

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers.assertContentDescriptionPresent
import com.riffle.app.harness.ReaderSemanticMatchers.assertInChapter
import com.riffle.app.harness.ReaderSemanticMatchers.assertNoErrorState
import com.riffle.app.harness.ReaderSemanticMatchers.assertTextVisible
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end harness test: drives the full UI from server setup through EPUB reader.
 * Uses StubAbsServer for all network calls — no real server required.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EpubHarnessTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val stubServer = StubAbsServer()

    @Before
    fun setUp() {
        stubServer.start()
        hiltRule.inject()
    }

    @After
    fun tearDown() = stubServer.shutdown()

    @Test
    fun opensEpubAndShowsReaderWithoutError() {
        addServerAndBrowseLibrary()

        // Library items load — tap the test EPUB item directly
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_ITEM_TITLE).performClick()

        assertReaderReady()

        // Simulate two page turns by tapping the right edge of the reader view
        repeat(2) {
            composeTestRule
                .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                .performTouchInput { click(centerRight) }
            composeTestRule.waitForIdle()
        }

        composeTestRule.assertNoErrorState()
        composeTestRule.onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY).assertIsDisplayed()
        // Chapter 1 is long enough to span multiple pages; assert we are still in chapter 1.
        composeTestRule.assertInChapter("chapter1")
    }

    @Test
    fun opensEpubViaSeriesNavigationAndShowsReaderWithoutError() {
        addServerAndBrowseLibrary()

        // Library items screen shows the series — tap into it
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_SERIES_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_SERIES_NAME).performClick()

        // Series detail loads — tap the item
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_ITEM_TITLE).performClick()

        assertReaderReady()
    }

    @Test
    fun opensEpubViaCollectionNavigationAndShowsReaderWithoutError() {
        addServerAndBrowseLibrary()

        // Library items screen shows the collection — tap into it
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_COLLECTION_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_COLLECTION_NAME).performClick()

        // Collection detail loads — tap the item
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_ITEM_TITLE).performClick()

        assertReaderReady()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun addServerAndBrowseLibrary() {
        composeTestRule.onNodeWithContentDescription("Add server").performClick()
        composeTestRule.onNode(hasSetTextAction() and hasText("Server URL")).performTextInput(stubServer.baseUrl)
        composeTestRule.onNode(hasSetTextAction() and hasText("Username")).performTextInput("testuser")
        composeTestRule.onNode(hasSetTextAction() and hasText("Password")).performTextInput("testpass")
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.onNodeWithText("Connect anyway").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Browse").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Browse").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_LIBRARY_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_LIBRARY_NAME).performClick()
    }

    private fun assertReaderReady() {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        with(composeTestRule) {
            assertNoErrorState()
            assertTextVisible(StubAbsServer.TEST_ITEM_TITLE)
            assertContentDescriptionPresent("Back")
        }
    }
}
