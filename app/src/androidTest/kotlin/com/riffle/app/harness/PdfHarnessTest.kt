package com.riffle.app.harness

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers.assertNoErrorState
import com.riffle.app.harness.ReaderSemanticMatchers.tapReadInDetailScreen
import com.riffle.app.harness.ReaderSemanticMatchers.waitUntilOnPdfPage
import com.riffle.app.harness.ReaderSemanticMatchers.waitUntilPdfLoaded
import com.riffle.core.data.di.PdfCacheStore
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
 * End-to-end harness test: drives the full UI from server setup through PDF reader.
 * Uses StubAbsServer for all network calls — no real server required.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PdfHarnessTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: RiffleDatabase
    @PdfCacheStore @Inject lateinit var pdfCacheStore: LocalStore

    private val stubServer = StubAbsServer()

    @Before
    fun setUp() {
        stubServer.start()
        hiltRule.inject()
        database.clearAllTables()
        // Clear the file-based PDF cache so every run exercises the download path.
        pdfCacheStore.clear()
    }

    @After
    fun tearDown() {
        stubServer.shutdown()
        composeTestRule.activityRule.scenario.close()
        Thread.sleep(400)
        database.clearAllTables()
    }

    @Test
    fun opensPdfNavigatesTwoPagesAndShowsPage3WithNoError() {
        addServerAndBrowseLibrary()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).performClick()
        composeTestRule.tapReadInDetailScreen()

        // Wait for the PDF reader to be ready and show the title
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()

        // Wait for the PDF to fully load (any page number appears in content description).
        composeTestRule.waitUntilPdfLoaded()

        // Navigate two pages forward by tapping the right side of the reader
        repeat(2) {
            composeTestRule
                .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                .performTouchInput { click(centerRight) }
            composeTestRule.waitForIdle()
        }

        // Assert PDF reader is displaying page 3 with no error state
        composeTestRule.waitUntilOnPdfPage(3)
        composeTestRule.assertNoErrorState()
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun addServerAndBrowseLibrary() {
        // With no servers, HomeScreen automatically navigates to AddServerScreen.
        // Wait for the form to appear before filling it in.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and hasText("Server URL")).performTextInput(stubServer.baseUrl)
        composeTestRule.onNode(hasSetTextAction() and hasText("Username")).performTextInput("testuser")
        composeTestRule.onNode(hasSetTextAction() and hasText("Password")).performTextInput("testpass")
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Connect anyway").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Connect anyway").performClick()
        // After server is added, HomeScreen.getStartDestination() refreshes libraries and
        // navigates directly to LibraryItemsScreen — no Browse or library-selection step needed.
    }
}
