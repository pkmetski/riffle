package com.riffle.app.shell

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
import com.riffle.app.harness.StubAbsServer
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.clearAllTables
import com.riffle.feature.designsystem.TestTags
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.test.KoinTest
import org.koin.test.inject

/**
 * Smoke test verifying that the AppShell renders correctly inside the Android host.
 *
 * Confirms that [com.riffle.feature.shellui.AppShell] is wired to [MainActivity] via
 * [com.riffle.app.navigation.MainScreen]. Uses a [StubAbsServer] to log in and reach the library
 * section, then verifies that the navigation drawer toggle and drawer items are present.
 */
@RunWith(AndroidJUnit4::class)
class AppShellSmokeTest : KoinTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val database: RiffleDatabaseAccess by inject()
    private val stubServer = StubAbsServer()

    @Before
    fun setUp() {
        stubServer.start()
        database.clearAllTables()
    }

    @After
    fun tearDown() {
        stubServer.shutdown()
        database.clearAllTables()
    }

    private fun loginAndReachLibrary() {
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
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag(TestTags.NAV_DRAWER_TOGGLE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun drawerToggleIsPresent() {
        loginAndReachLibrary()
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_TOGGLE).assertIsDisplayed()
    }

    @Test
    fun settingsDrawerEntryIsReachable() {
        loginAndReachLibrary()
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_TOGGLE).performClick()
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_SETTINGS).assertIsDisplayed()
    }

    @Test
    fun downloadsDrawerEntryIsReachable() {
        loginAndReachLibrary()
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_TOGGLE).performClick()
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_DOWNLOADS).assertIsDisplayed()
    }
}
