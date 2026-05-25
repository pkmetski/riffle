package com.riffle.app.harness

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers.assertContentDescriptionPresent
import com.riffle.app.harness.ReaderSemanticMatchers.assertInChapter
import com.riffle.app.harness.ReaderSemanticMatchers.assertNoErrorState
import com.riffle.app.harness.ReaderSemanticMatchers.assertRailActiveSegment
import com.riffle.app.harness.ReaderSemanticMatchers.assertTextVisible
import com.riffle.app.harness.ReaderSemanticMatchers.tapReadInDetailScreen
import com.riffle.app.harness.ReaderSemanticMatchers.waitUntilInChapter
import com.riffle.app.harness.ReaderSemanticMatchers.waitUntilRailActiveSegment
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
 * End-to-end harness test: drives the full UI from server setup through EPUB reader.
 * Uses StubAbsServer for all network calls — no real server required.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EpubHarnessTest {

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
        // Close the Activity explicitly so Fragment.onDestroy() fires before the next test
        // launches a new Activity. The sleep gives Readium's DataStore coroutine scope time
        // to cancel and deregister from the DataStore registry (async cancellation).
        composeTestRule.activityRule.scenario.close()
        Thread.sleep(400)
        // Clear DB after closing the activity so the next test's activity starts with an
        // empty DB. Without this, HomeScreen.LaunchedEffect can read stale server data
        // before setUp()'s clearAllTables() runs and navigate to the wrong screen.
        database.clearAllTables()
    }

    @Test
    fun opensEpubViaSeriesNavigationAndShowsReaderWithoutError() {
        addServerAndBrowseLibrary()

        // Navigate to the Series tab
        composeTestRule.onNodeWithContentDescription("Series").performClick()

        // Library items screen shows the series — tap into it
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_SERIES_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_SERIES_NAME).performClick()

        // Series detail loads — tap the item
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_ITEM_TITLE).performClick()

        assertReaderReady()
    }

    @Test
    fun progressSyncUsesCorrectEndpoint() {
        // Regression: previously synced to /api/session (wrong endpoint); must use PATCH /api/me/progress/:itemId
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)

        composeTestRule.waitUntil(timeoutMillis = 40_000) {
            stubServer.sessionSyncCount > 0
        }

        val path = stubServer.lastProgressPath
        assert(path == "/api/me/progress/${StubAbsServer.TEST_STANDALONE_ITEM_ID}") {
            "Expected PATCH /api/me/progress/:itemId but got: $path"
        }
    }

    @Test
    fun progressSyncSendsEpubCfiNotJson() {
        // Regression: previously sent Readium Locator JSON as ebookLocation; must send epubcfi(...)
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)

        // Wait for a sync that carries a real CFI — the immediate sync in openBook() may fire
        // first with an empty location before the Readium navigator emits its first locator.
        composeTestRule.waitUntil(timeoutMillis = 40_000) {
            stubServer.lastProgressBody?.contains("epubcfi(") == true
        }

        val body = stubServer.lastProgressBody
        assert(body != null) { "No progress sync body captured" }
        assert(body!!.contains("\"ebookLocation\":\"epubcfi(")) {
            "Expected ebookLocation to be an epub.js CFI (epubcfi(...)) but body was: $body"
        }
        // CFI must include a content-document path after the indirection operator (!).
        // e.g. epubcfi(/6/2!/4/2) — without it epub.js shows a black screen and can't navigate.
        val cfiMatch = Regex("""epubcfi\(/6/\d+!/\d""").containsMatchIn(body)
        assert(cfiMatch) {
            "epubcfi must have a content-document path after ! (e.g. epubcfi(/6/2!/4/2)) but body was: $body"
        }
        assert(!body.contains("\"href\"")) {
            "ebookLocation must not be a Readium Locator JSON object but body was: $body"
        }
    }

    @Test
    fun progressSyncSendsBookWideFraction() {
        // Regression: previously sent per-chapter progression instead of total book progress.
        // Opening to chapter 1 of the test EPUB, ebookProgress must be in [0, 0.5) — not a large
        // per-chapter fraction that would imply we're near the end of the book.
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)

        composeTestRule.waitUntil(timeoutMillis = 40_000) {
            stubServer.sessionSyncCount > 0
        }

        val body = stubServer.lastProgressBody
        assert(body != null) { "No progress sync body captured" }
        val progressMatch = Regex(""""ebookProgress":([\d.E+\-]+)""").find(body!!)
        assert(progressMatch != null) { "ebookProgress field not found in body: $body" }
        val progress = progressMatch!!.groupValues[1].toFloat()
        assert(progress in 0f..0.5f) {
            "ebookProgress should be a book-wide fraction near 0 when opening at chapter 1, but was $progress (body: $body)"
        }
    }

    @Test
    fun railHighlightsActiveChapterAfterNavigation() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)

        // Navigate to Chapter 2 Section 3 via the TOC panel
        composeTestRule.onNodeWithContentDescription("Table of Contents").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_TOC_PANEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Section 2.3: Turning Point").performClick()

        // Wait until the navigator reaches chapter 2
        composeTestRule.waitUntilInChapter("chapter2", timeoutMillis = 15_000)
        composeTestRule.assertNoErrorState()

        // Assert the rail highlights Chapter 2 as the active chapter
        composeTestRule.waitUntilRailActiveSegment("Chapter 2", timeoutMillis = 10_000)
        composeTestRule.assertRailActiveSegment("Chapter 2")
    }

    @Test
    fun tappingContentTogglesImmersiveMode() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)

        // Back button is visible before immersive mode
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()

        // Tap the center of the reading area — fires InputListener.onTap, toggles immersive
        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }

        // Wait for TopAppBar to animate out
        composeTestRule.waitUntil(timeoutMillis = 2_000) {
            composeTestRule.onAllNodesWithContentDescription("Back")
                .fetchSemanticsNodes().isEmpty()
        }

        // Tap center again to exit immersive mode
        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }

        // Back button reappears
        composeTestRule.waitUntil(timeoutMillis = 2_000) {
            composeTestRule.onAllNodesWithContentDescription("Back")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeTestRule.assertNoErrorState()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun addServerAndBrowseLibrary() {
        // With no servers, HomeScreen automatically navigates to AddServerScreen.
        // Wait for the form to appear before filling it in.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty()
        }
        // Use replaceText to overwrite any pre-filled dev credentials from BuildConfig.
        composeTestRule.onNode(hasSetTextAction() and hasText("Server URL")).performTextReplacement(stubServer.baseUrl)
        composeTestRule.onNode(hasSetTextAction() and hasText("Username")).performTextReplacement("testuser")
        composeTestRule.onNode(hasSetTextAction() and hasText("Password")).performTextReplacement("testpass")
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Connect anyway").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Connect anyway").performClick()
        // After server is added, HomeScreen.getStartDestination() refreshes libraries and
        // navigates directly to LibraryItemsScreen. Switch to All Books tab so items are visible.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()
    }

    private fun assertReaderReady(title: String = StubAbsServer.TEST_ITEM_TITLE) {
        composeTestRule.tapReadInDetailScreen()
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        with(composeTestRule) {
            assertNoErrorState()
            assertTextVisible(title)
            assertContentDescriptionPresent("Back")
        }
    }
}
