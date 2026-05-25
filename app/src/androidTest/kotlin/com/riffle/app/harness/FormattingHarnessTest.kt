package com.riffle.app.harness

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers.assertNoErrorState
import com.riffle.app.harness.ReaderSemanticMatchers.tapReadInDetailScreen
import com.riffle.core.database.RiffleDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FormattingHarnessTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: RiffleDatabase

    private val stubServer = StubAbsServer()

    @Before
    fun setUp() {
        stubServer.start()
        hiltRule.inject()
        database.clearAllTables()
    }

    @After
    fun tearDown() {
        stubServer.shutdown()
        composeTestRule.activityRule.scenario.close()
        Thread.sleep(400)
        database.clearAllTables()
    }

    @Test
    fun openFormattingPanelAndSwitchToSepiaTheme() {
        addServerAndBrowseToReader()

        // Reader opens in immersive mode — tap content to reveal the floating TopAppBar.
        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .performTouchInput { click(Offset(width * 0.5f, height * 0.5f)) }
        composeTestRule.waitUntil(timeoutMillis = 2_000) {
            composeTestRule.onAllNodesWithContentDescription("Format").fetchSemanticsNodes().isNotEmpty()
        }

        // Open formatting panel
        composeTestRule.onNodeWithContentDescription("Format").performClick()
        composeTestRule.waitForIdle()

        // Select Sepia theme
        composeTestRule.onNodeWithContentDescription("Sepia theme").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.assertNoErrorState()
        // Assert reader exposes sepia theme in its semantic description
        composeTestRule
            .onNode(hasTestTag(ReaderSemanticMatchers.TAG_READER_READY) and hasContentDescription("theme:sepia", substring = true))
            .assertExists()
    }

    private fun addServerAndBrowseToReader() {
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
        // navigates directly to LibraryItemsScreen. Switch to All Books tab so items are visible.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()

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
    }
}
