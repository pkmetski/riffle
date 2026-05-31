package com.riffle.app.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.harness.TabletLayout
import com.riffle.core.domain.Library
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerUrl
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that RiffleNavigationDrawer in permanent mode (ADR 0019, Expanded
 * window size class) renders its contents without requiring the drawer to be
 * opened — proving the absence of a hamburger affordance / modal scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@TabletLayout
@RunWith(AndroidJUnit4::class)
class PermanentNavigationDrawerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun permanentDrawerRendersContentsWithoutOpening() {
        val server = Server(
            id = "s1",
            url = ServerUrl.parse("http://example.com")!!,
            displayName = "Example",
            isActive = true,
            insecureConnectionAllowed = false,
            username = "alice",
        )
        val libraries = listOf(
            Library(id = "lib1", name = "Ebooks", mediaType = "book", isUnsupported = false),
        )

        composeTestRule.setContent {
            RiffleNavigationDrawer(
                drawerState = DrawerState(DrawerValue.Closed),
                usePermanentDrawer = true,
                activeServer = server,
                allServers = listOf(server),
                visibleLibraries = libraries,
                activeLibraryId = "lib1",
                serverVersions = emptyMap(),
                onServerSelected = {},
                onLibrarySelected = {},
                onDownloadsSelected = {},
                onSettingsSelected = {},
            ) {
                Box(Modifier.fillMaxSize())
            }
        }

        // Drawer is rendered without being "opened" — its items are immediately on screen.
        composeTestRule.onNodeWithText("Ebooks").assertIsDisplayed()
        composeTestRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    /**
     * Reader routes are immersive: when the host signals that the panel should be
     * hidden (e.g. the current destination is the EPUB/PDF reader), the permanent
     * drawer must not paint its sheet — the reader content takes the full width.
     */
    @Test
    fun permanentDrawerHidesPanelWhenHideFlagSet() {
        val server = Server(
            id = "s1",
            url = ServerUrl.parse("http://example.com")!!,
            displayName = "Example",
            isActive = true,
            insecureConnectionAllowed = false,
            username = "alice",
        )
        val libraries = listOf(
            Library(id = "lib1", name = "Ebooks", mediaType = "book", isUnsupported = false),
        )

        composeTestRule.setContent {
            RiffleNavigationDrawer(
                drawerState = DrawerState(DrawerValue.Closed),
                usePermanentDrawer = true,
                hidePermanentDrawerPanel = true,
                activeServer = server,
                allServers = listOf(server),
                visibleLibraries = libraries,
                activeLibraryId = "lib1",
                serverVersions = emptyMap(),
                onServerSelected = {},
                onLibrarySelected = {},
                onDownloadsSelected = {},
                onSettingsSelected = {},
            ) {
                Box(Modifier.fillMaxSize().testTag("reader-content"))
            }
        }

        // Drawer items must be absent — the panel itself is not emitted.
        composeTestRule.onNodeWithText("Ebooks").assertDoesNotExist()
        composeTestRule.onNodeWithText("Downloads").assertDoesNotExist()
        composeTestRule.onNodeWithText("Settings").assertDoesNotExist()
        // Reader content is still rendered.
        composeTestRule.onNodeWithTag("reader-content").assertIsDisplayed()
    }
}
