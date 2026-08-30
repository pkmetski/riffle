package com.riffle.app.harness

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers.assertNoErrorState
import com.riffle.app.harness.ReaderSemanticMatchers.tapReadInDetailScreen
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.clearAllTables
import com.riffle.core.domain.WakeLockPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.test.KoinTest
import org.koin.test.inject

@RunWith(AndroidJUnit4::class)
class WakeLockHarnessTest : KoinTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

    val database: RiffleDatabaseAccess by inject()
    val wakeLockPreferencesStore: WakeLockPreferencesStore by inject()

    private val stubServer = StubAbsServer()

    @Before
    fun setUp() {
        stubServer.start()
        database.clearAllTables()
        runBlocking { wakeLockPreferencesStore.setKeepScreenOn(true) }
    }

    @After
    fun tearDown() {
        stubServer.shutdown()
        composeTestRule.activityRule.scenario.close()
        Thread.sleep(400)
        database.clearAllTables()
    }

    @Test
    fun wakeLockIsActiveByDefaultInEpubReader() {
        addServerAndBrowseToReader()

        composeTestRule.assertNoErrorState()
        composeTestRule
            .onNode(hasTestTag(ReaderSemanticMatchers.TAG_READER_READY) and hasContentDescription("wake-lock:on", substring = true))
            .assertExists()
    }

    @Test
    fun wakeLockIsOffWhenDisabledInSettings() {
        // Disable "Keep screen on" in Settings before opening a book
        addServerAndNavigateToSettings()

        // "Keep screen on while reading" is now directly visible in the Behavior section on the
        // Settings screen (no drill-in panel). Scroll it into view and tap.
        composeTestRule.onNodeWithText("Keep screen on while reading").assertExists()
        composeTestRule.onNodeWithText("Keep screen on while reading").performScrollTo()
        composeTestRule.onNodeWithText("Keep screen on while reading").performClick()
        composeTestRule.waitForIdle()
        // Compose's waitForIdle does not wait for the DataStore write fired by the toggle's
        // viewModelScope.launch. Poll the store directly so the preference is verified
        // persisted before we navigate away — otherwise the reader may read the stale `true`.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { wakeLockPreferencesStore.keepScreenOn.first() } == false
        }

        // One back-press leaves Settings and returns to the library. There is no full-screen
        // panel to dismiss; the toggle is directly in the Behavior section of Settings.
        Espresso.pressBack()
        composeTestRule.waitForIdle()

        openFirstBook()

        composeTestRule.assertNoErrorState()
        // `keepScreenOn` is exposed via SharingStarted.Eagerly with an initial value of `true`,
        // so `reader_ready` first paints with wake-lock:on and recomposes once the DataStore
        // flow delivers the persisted `false`. Wait for that descriptor instead of asserting now.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodes(hasTestTag(ReaderSemanticMatchers.TAG_READER_READY) and hasContentDescription("wake-lock:off", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun wakeLockIsActiveByDefaultInPdfReader() {
        addServerAndBrowseToPdfReader()

        composeTestRule.assertNoErrorState()
        composeTestRule
            .onNode(hasTestTag(ReaderSemanticMatchers.TAG_READER_READY) and hasContentDescription("wake-lock:on", substring = true))
            .assertExists()
    }

    private fun addServerAndBrowseToReader() {
        // With no sources, HomeScreen navigates to the Source Type picker (#435).
        // Tap the Audiobookshelf card to advance to the connect form.
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
        // Single-library servers skip SelectLibrariesScreen: commit happens automatically.
        // Switch to All Books tab so items are visible.
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

    private fun addServerAndBrowseToPdfReader() {
        // With no sources, HomeScreen navigates to the Source Type picker (#435).
        // Tap the Audiobookshelf card to advance to the connect form.
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
        // Single-library servers skip SelectLibrariesScreen: commit happens automatically.
        // Switch to All Books tab so items are visible.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).performClick()
        composeTestRule.tapReadInDetailScreen()

        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()
    }

    private fun addServerAndNavigateToSettings() {
        // With no sources, HomeScreen navigates to the Source Type picker (#435).
        // Tap the Audiobookshelf card to advance to the connect form.
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
        // Single-library servers skip SelectLibrariesScreen: commit happens automatically.
        // Switch to All Books tab so items are visible.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        // Open navigation drawer and go to Settings
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitForIdle()
    }

    private fun openFirstBook() {
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
