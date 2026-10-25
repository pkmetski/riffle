package com.riffle.app.harness

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.clearAllTables
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.LocalStore
import com.riffle.feature.designsystem.TestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.qualifier.named
import org.koin.test.KoinTest
import org.koin.test.inject

/**
 * Regression test for issue #1311: "Show all annotations" in the library search bar navigates to
 * the annotation search results screen on Android.
 *
 * Seeds 6 annotations (> ANNOTATION_PREVIEW_CAP=5) for a library item so the "Show all" footer
 * appears in the inline search results, then asserts that tapping it opens
 * [TestTags.ANNOTATION_SEARCH_RESULTS]. The iOS counterpart is AnnotationSearchFlowTests.swift,
 * which covers the Annotations-tab search field path (iOS's only route into the results screen).
 */
@RunWith(AndroidJUnit4::class)
class ShowAllAnnotationsHarnessTest : KoinTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

    val database: RiffleDatabaseAccess by inject()
    val epubCacheStore: LocalStore by inject(named("epubCacheStore"))
    val annotationStore: AnnotationStore by inject()

    private val stubServer = StubAbsServer()

    @Before
    fun setUp() {
        stubServer.start()
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
    fun showAllAnnotationsOpensResultsScreen() {
        addServerAndBrowseLibrary()
        seedAnnotations()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(TestTags.LIBRARY_SEARCH).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTags.LIBRARY_SEARCH).performTextReplacement(SEED_TEXT_PREFIX)

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(TestTags.SHOW_ALL_ANNOTATIONS).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTags.SHOW_ALL_ANNOTATIONS).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(TestTags.ANNOTATION_SEARCH_RESULTS).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTags.ANNOTATION_SEARCH_RESULTS).assertIsDisplayed()
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
            composeTestRule.onAllNodesWithTag(TestTags.LIBRARY_SEARCH).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun seedAnnotations() = runBlocking {
        val source = database.sourceDao().getActive()
            ?: error("no active source after browsing library")
        repeat(SEED_COUNT) { index ->
            annotationStore.createHighlight(
                sourceId = source.id,
                itemId = StubAbsServer.TEST_STANDALONE_ITEM_ID,
                cfi = "epubcfi(/6/2[chapter1]!/4/2/${index + 1}/1:0)",
                textSnippet = "$SEED_TEXT_PREFIX $index",
                chapterHref = "chapter1.xhtml",
                originFontFamily = "Georgia, serif",
            )
        }
    }

    companion object {
        private const val SEED_COUNT = 6
        private const val SEED_TEXT_PREFIX = "section text"
    }
}
