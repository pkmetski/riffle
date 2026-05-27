package com.riffle.app.harness

import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.app.feature.reader.TAG_FOOTNOTE_POPUP
import com.riffle.app.harness.ReaderSemanticMatchers.assertNoErrorState
import com.riffle.app.harness.ReaderSemanticMatchers.tapReadInDetailScreen
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.domain.LocalStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end harness tests for the footnote popup feature.
 *
 * Tests:
 * 1. No phantom popup on reader open — regression for the "null" popup bug.
 * 2. Popup appears after tapping a footnote link in the footnote test EPUB.
 * 3. Popup dismisses when the close button is tapped.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FootnoteHarnessTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: RiffleDatabase
    @EpubCacheStore @Inject lateinit var epubCacheStore: LocalStore

    private val stubServer = StubAbsServer()

    @Before
    fun setUp() {
        stubServer.start()
        hiltRule.inject()
        database.clearAllTables()
        epubCacheStore.clear()
    }

    @After
    fun tearDown() {
        stubServer.shutdown()
        composeTestRule.activityRule.scenario.close()
        Thread.sleep(400)
        database.clearAllTables()
    }

    @Test
    fun noPhantomPopupWhenReaderFirstOpens() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).performClick()
        assertReaderReady()

        // No popup should appear immediately after the reader opens
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(TAG_FOOTNOTE_POPUP)
            .fetchSemanticsNodes()
            .let { nodes ->
                assert(nodes.isEmpty()) {
                    "Footnote popup appeared unexpectedly when reader first opened"
                }
            }
        composeTestRule.assertNoErrorState()
    }

    @Test
    fun footnoteLinkShowsPopupWithContent() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).performClick()
        assertReaderReady()

        // The test EPUB has "Archimedes[1]" in 32pt centered text as the first element.
        // Tap near the top-center of the reader where the footnote superscript appears.
        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .performTouchInput { click(Offset(width * 0.75f, height * 0.12f)) }

        // Wait for footnote popup to appear
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(TAG_FOOTNOTE_POPUP).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag(TAG_FOOTNOTE_POPUP).assertIsDisplayed()
        composeTestRule.assertNoErrorState()
    }

    @Test
    fun footnotePopupDismissesOnCloseButton() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).performClick()
        assertReaderReady()

        // Tap to trigger the footnote popup
        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .performTouchInput { click(Offset(width * 0.75f, height * 0.12f)) }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(TAG_FOOTNOTE_POPUP).fetchSemanticsNodes().isNotEmpty()
        }

        // Tap the close button
        composeTestRule.onNodeWithContentDescription("Close footnote").performClick()

        // Popup should be gone
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            composeTestRule.onAllNodesWithTag(TAG_FOOTNOTE_POPUP).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.assertNoErrorState()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun addServerAndBrowseLibrary() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and hasText("Server URL")).performTextReplacement(stubServer.baseUrl)
        composeTestRule.onNode(hasSetTextAction() and hasText("Username")).performTextReplacement("testuser")
        composeTestRule.onNode(hasSetTextAction() and hasText("Password")).performTextReplacement("testpass")
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Connect anyway").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Connect anyway").performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("All Books").fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        // Switch to All Books tab to ensure all items are visible
        if (composeTestRule.onAllNodesWithText("All Books").fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithText("All Books").performClick()
        }
    }

    private fun assertReaderReady() {
        composeTestRule.tapReadInDetailScreen()
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()
        composeTestRule.onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY).assertExists()
    }
}
