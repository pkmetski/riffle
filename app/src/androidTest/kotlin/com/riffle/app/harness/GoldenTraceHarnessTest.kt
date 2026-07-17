package com.riffle.app.harness

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
 * Golden trace sentinel for the multi-platform-core migration (issue #550).
 *
 * Composes the full user-visible round trip in a single test so subsequent phases
 * (#551–#557) can be gated on "does this still pass". If any phase breaks
 * serialization, networking, persistence, or reader wiring, this fails first.
 *
 * Trace: login to ABS → open library → open EPUB → progress-sync round-trip.
 *
 * Scope note: the original scope also mentioned "readaloud one sentence" but readaloud
 * requires a Storyteller peer stub that doesn't exist in the harness, and audio playback
 * is unreliable on the headless API-25 AVD (audio-HAL stalls). Readaloud regressions
 * during the migration remain covered by unit tests in the readaloud/ package and by
 * on-device verification.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GoldenTraceHarnessTest {

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
    fun goldenTraceLoginLibraryOpenEpubSync() {
        // 1. Login to ABS (via Source Type picker → Audiobookshelf → Connect form).
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

        // 2. Browse library (single-library auto-commit lands on LibraryItemsScreen).
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()

        // 3. Open an EPUB and confirm the reader is ready.
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        composeTestRule.tapReadInDetailScreen()
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()
        composeTestRule.onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY).assertExists()

        // 4. Sync round-trip: a PATCH /api/me/progress/:itemId lands with an epubcfi body.
        //    This is the sentinel — the entire serialization / network / DB / reader path
        //    has to survive the multi-platform-core migration for this to pass.
        composeTestRule.waitUntil(timeoutMillis = 40_000) {
            stubServer.lastProgressBody?.contains("epubcfi(") == true
        }
        val path = stubServer.lastProgressPath
        assert(path == "/api/me/progress/${StubAbsServer.TEST_STANDALONE_ITEM_ID}") {
            "Expected PATCH /api/me/progress/:itemId but got: $path"
        }
    }
}
