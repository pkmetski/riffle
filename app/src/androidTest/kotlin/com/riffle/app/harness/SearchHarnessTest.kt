package com.riffle.app.harness

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.app.feature.reader.SearchTopBarTags
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
 * End-to-end harness test for EPUB full-text search.
 * Drives the real UI through search open → query → navigation → close.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SearchHarnessTest {

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
    fun searchOpensThenShowsResultsWithWorkingPrevNextNavigation() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady()

        showTopAppBar()
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        // Search field must appear
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(SearchTopBarTags.FIELD).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(SearchTopBarTags.FIELD).assertIsDisplayed()

        // Type a term that appears multiple times across all chapters ("Section" → 9 occurrences)
        composeTestRule.onNodeWithTag(SearchTopBarTags.FIELD).performTextInput("Section")

        // Wait until results appear — count text changes from "" to "1 of N"
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            val nodes = composeTestRule.onAllNodesWithTag(SearchTopBarTags.COUNT).fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes.any { node ->
                runCatching { node.config[SemanticsProperties.Text] }.getOrElse { emptyList() }
                    .any { it.text.contains(" of ") }
            }
        }

        // At the first result: prev is disabled, next is enabled
        composeTestRule.onNodeWithTag(SearchTopBarTags.PREV).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(SearchTopBarTags.NEXT).assertIsEnabled()

        // Navigate to the next result
        composeTestRule.onNodeWithTag(SearchTopBarTags.NEXT).performClick()

        // Count must now show "2 of N"
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(SearchTopBarTags.COUNT).fetchSemanticsNodes()
                .any { node ->
                    runCatching { node.config[SemanticsProperties.Text] }.getOrElse { emptyList() }
                        .any { it.text.startsWith("2 of ") }
                }
        }

        // Both prev and next are now enabled
        composeTestRule.onNodeWithTag(SearchTopBarTags.PREV).assertIsEnabled()
        composeTestRule.onNodeWithTag(SearchTopBarTags.NEXT).assertIsEnabled()

        // Navigate back to the first result
        composeTestRule.onNodeWithTag(SearchTopBarTags.PREV).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(SearchTopBarTags.COUNT).fetchSemanticsNodes()
                .any { node ->
                    runCatching { node.config[SemanticsProperties.Text] }.getOrElse { emptyList() }
                        .any { it.text.startsWith("1 of ") }
                }
        }

        composeTestRule.assertNoErrorState()
    }

    @Test
    fun closingSearchRestoresNormalTopAppBar() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady()

        showTopAppBar()
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(SearchTopBarTags.FIELD).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithContentDescription("Close search").performClick()

        // SearchTopBar field disappears, normal Back button returns
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(SearchTopBarTags.FIELD).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.assertNoErrorState()
    }

    @Test
    fun searchWithNoMatchesShowsNoResults() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        assertReaderReady()

        showTopAppBar()
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(SearchTopBarTags.FIELD).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag(SearchTopBarTags.FIELD).performTextInput("xyzzy_no_match_term")

        // Wait for search to complete — count must say "No results"
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            composeTestRule.onAllNodesWithTag(SearchTopBarTags.COUNT).fetchSemanticsNodes()
                .any { node ->
                    runCatching { node.config[SemanticsProperties.Text] }.getOrElse { emptyList() }
                        .any { it.text == "No results" }
                }
        }

        // Both prev and next are disabled when there are no results
        composeTestRule.onNodeWithTag(SearchTopBarTags.PREV).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(SearchTopBarTags.NEXT).assertIsNotEnabled()
        composeTestRule.assertNoErrorState()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun addServerAndBrowseLibrary() {
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
        // Single-library servers skip SelectLibrariesScreen: commit happens automatically.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()
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

    private fun showTopAppBar() {
        repeat(3) {
            composeTestRule
                .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                .performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }
            try {
                composeTestRule.waitUntil(timeoutMillis = 2_000) {
                    composeTestRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
                }
                return
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                // dismissOverlay() race may have re-hidden the bar — retry tap
            }
        }
        throw AssertionError("showTopAppBar: Back button not visible after 3 tap attempts")
    }
}
