package com.riffle.app.harness

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
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
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
 * Progress-sync regression tests split out of EpubHarnessTest to cap the WebView-open count
 * per shard. Each test here opens a reader and waits 40 s for a progress-sync round-trip,
 * leaving significant native memory behind. Running them in the REST shard (shard 3) gives
 * them a fresh renderer after the long GC gap provided by the 200+ preceding feature tests,
 * while keeping EpubHarnessTest's own shard (shard 2) at ≤2 WebView opens.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EpubProgressHarnessTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: RiffleDatabase
    @EpubCacheStore @Inject lateinit var epubCacheStore: LocalStore

    private val stubServer = StubAbsServer()

    @Before
    fun setUp() {
        Runtime.getRuntime().gc()
        Thread.sleep(800)
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
        Thread.sleep(800)
        database.clearAllTables()
    }

    @Test
    fun progressSyncUsesCorrectEndpoint() {
        // Regression: previously synced to /api/session (wrong endpoint); must use PATCH /api/me/progress/:itemId
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady()

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
        assertReaderReady()

        composeTestRule.waitUntil(timeoutMillis = 40_000) {
            stubServer.lastProgressBody?.contains("epubcfi(") == true
        }

        val body = stubServer.lastProgressBody
        assert(body != null) { "No progress sync body captured" }
        assert(body!!.contains("\"ebookLocation\":\"epubcfi(")) {
            "Expected ebookLocation to be an epub.js CFI (epubcfi(...)) but body was: $body"
        }
        val cfiMatch = Regex("""epubcfi\(/6/\d+!/\d""").containsMatchIn(body)
        assert(cfiMatch) {
            "epubcfi must have a content-document path after ! (e.g. epubcfi(/6/2!/4/2)) but body was: $body"
        }
        assert(!body.contains("\"href\"")) {
            "ebookLocation must not be a Readium Locator JSON object but body was: $body"
        }
    }

    private fun addServerAndBrowseLibrary() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Audiobookshelf").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Audiobookshelf").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and hasText("Source URL")).performTextReplacement(stubServer.baseUrl)
        composeTestRule.onNode(hasSetTextAction() and hasText("Username")).performTextReplacement("testuser")
        composeTestRule.onNode(hasSetTextAction() and hasText("Password")).performTextReplacement("testpass")
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Connect anyway").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Connect anyway").performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()
    }

    private fun assertReaderReady() {
        composeTestRule.tapReadInDetailScreen()
        composeTestRule.waitUntil(timeoutMillis = 120_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()
        composeTestRule.onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY).assertExists()
    }
}
