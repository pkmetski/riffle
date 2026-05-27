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
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Compose's waitForIdle() blocks indefinitely when the Readium WebView is active —
// continuous position callbacks keep Compose perpetually busy. EpubHarnessTest passes
// by race-condition timing. The footnote popup feature is verified by
// EpubReaderViewModelFootnoteTest (unit) and manual testing.
@Ignore("Compose waitForIdle hangs with Readium WebView position callbacks")
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EpubFootnoteHarnessTest {

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
        Runtime.getRuntime().gc()
        Thread.sleep(400)
        database.clearAllTables()
    }

    @Test
    fun noPhantomPopupWhenReaderFirstOpens() {
        openFootnoteEpubReader()
        Thread.sleep(2_000)
        assert(composeTestRule.onAllNodesWithTag(TAG_FOOTNOTE_POPUP).fetchSemanticsNodes().isEmpty()) {
            "Footnote popup appeared unexpectedly when reader first opened"
        }
    }

    @Test
    fun footnoteLinkShowsPopupWithContent() {
        openFootnoteEpubReader()

        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .performTouchInput { click(Offset(width * 0.75f, height * 0.12f)) }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(TAG_FOOTNOTE_POPUP).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TAG_FOOTNOTE_POPUP).assertIsDisplayed()
    }

    @Test
    fun footnotePopupDismissesOnCloseButton() {
        openFootnoteEpubReader()

        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .performTouchInput { click(Offset(width * 0.75f, height * 0.12f)) }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(TAG_FOOTNOTE_POPUP).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Close footnote").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(TAG_FOOTNOTE_POPUP).fetchSemanticsNodes().isEmpty()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun openFootnoteEpubReader() {
        addServerAndBrowseLibrary()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_FOOTNOTE_ITEM_TITLE).performClick()
        assertReaderReady()
    }

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
        if (composeTestRule.onAllNodesWithText("All Books").fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithText("All Books").performClick()
        }
    }

    private fun assertReaderReady() {
        composeTestRule.tapReadInDetailScreen()
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()
        composeTestRule.onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY).assertExists()
    }
}
