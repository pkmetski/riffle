package com.riffle.feature.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.navigation.LibraryNav
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for [AppShellContent]: section switching, back-to-origin after Settings /
 * Downloads, and pending-nav routing to the Library section only.
 *
 * Uses [AppShellContent] (pure state parameters, no Koin) with [isTabletLayout] = true so the
 * permanent drawer is always visible — no need to open a modal drawer before tapping items.
 *
 * Runs as `:feature:library-ui:iosSimulatorArm64Test` in CI.
 */
@OptIn(ExperimentalTestApi::class)
class AppShellTest {

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun shell(
        librarySlotTag: String = "lib",
        riffleSlotTag: String = "riffle",
        downloadsSlotTag: String = "downloads",
        settingsSlotTag: String = "settings",
        externalPendingLibraryNav: LibraryNav? = null,
        onExternalPendingLibraryNavHandled: () -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        AppShellContent(
            isTabletLayout = true,
            allServers = emptyList(),
            activeServer = null,
            visibleLibraries = emptyList(),
            activeLibraryId = null,
            isRiffleMode = false,
            showDownloadsLink = true,
            serverVersions = emptyMap(),
            externalPendingLibraryNav = externalPendingLibraryNav,
            onExternalPendingLibraryNavHandled = onExternalPendingLibraryNavHandled,
            onServerSelected = {},
            onLibrarySelected = {},
            onSetRiffleActive = {},
            appVersion = null,
            appSha = null,
            libraryContent = { Box(Modifier.testTag(librarySlotTag)) { Text("Library") } },
            riffleContent = { Box(Modifier.testTag(riffleSlotTag)) { Text("Riffle") } },
            downloadsContent = { Box(Modifier.testTag(downloadsSlotTag)) { Text("Downloads") } },
            settingsContent = { Box(Modifier.testTag(settingsSlotTag)) { Text("Settings") } },
        )
    }

    // ── section switching ─────────────────────────────────────────────────────────────────────

    @Test
    fun libraryIsInitialSection() = runComposeUiTest {
        setContent(shell())
        onNodeWithTag("lib").assertIsDisplayed()
    }

    @Test
    fun settingsSelectedFromDrawerSwitchesSection() = runComposeUiTest {
        setContent(shell())
        onNodeWithTag("lib").assertIsDisplayed()
        onNodeWithTag(TestTags.NAV_DRAWER_SETTINGS).performClick()
        onNodeWithTag("settings").assertIsDisplayed()
    }

    @Test
    fun downloadsSelectedFromDrawerSwitchesSection() = runComposeUiTest {
        setContent(shell())
        onNodeWithTag("lib").assertIsDisplayed()
        onNodeWithTag(TestTags.NAV_DRAWER_DOWNLOADS).performClick()
        onNodeWithTag("downloads").assertIsDisplayed()
    }

    @Test
    fun riffleSelectedFromDrawerSwitchesSection() = runComposeUiTest {
        setContent(shell())
        onNodeWithTag(TestTags.NAV_DRAWER_RIFFLE).performClick()
        onNodeWithTag("riffle").assertIsDisplayed()
    }

    // ── back navigation ───────────────────────────────────────────────────────────────────────

    @Test
    fun settingsBackReturnsToLibrary() = runComposeUiTest {
        setContent {
            AppShellContent(
                isTabletLayout = true,
                allServers = emptyList(),
                activeServer = null,
                visibleLibraries = emptyList(),
                activeLibraryId = null,
                isRiffleMode = false,
                showDownloadsLink = true,
                serverVersions = emptyMap(),
                externalPendingLibraryNav = null,
                onExternalPendingLibraryNavHandled = {},
                onServerSelected = {},
                onLibrarySelected = {},
                onSetRiffleActive = {},
                appVersion = null,
                appSha = null,
                libraryContent = { Text("Library") },
                riffleContent = { Text("Riffle") },
                downloadsContent = { Text("Downloads") },
                settingsContent = {
                    Text("Settings")
                    androidx.compose.material3.Button(onClick = onNavigateBack) {
                        Text("Back")
                    }
                },
            )
        }
        onNodeWithTag(TestTags.NAV_DRAWER_SETTINGS).performClick()
        onNodeWithText("Settings").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithText("Library").assertIsDisplayed()
    }

    @Test
    fun settingsBackReturnsToRiffleWhenOpenedFromRiffle() = runComposeUiTest {
        setContent {
            AppShellContent(
                isTabletLayout = true,
                allServers = emptyList(),
                activeServer = null,
                visibleLibraries = emptyList(),
                activeLibraryId = null,
                isRiffleMode = false,
                showDownloadsLink = true,
                serverVersions = emptyMap(),
                externalPendingLibraryNav = null,
                onExternalPendingLibraryNavHandled = {},
                onServerSelected = {},
                onLibrarySelected = {},
                onSetRiffleActive = {},
                appVersion = null,
                appSha = null,
                libraryContent = { Text("Library") },
                riffleContent = {
                    Text("Riffle")
                },
                downloadsContent = { Text("Downloads") },
                settingsContent = {
                    Text("Settings")
                    androidx.compose.material3.Button(onClick = onNavigateBack) {
                        Text("BackFromSettings")
                    }
                },
            )
        }
        onNodeWithTag(TestTags.NAV_DRAWER_RIFFLE).performClick()
        onNodeWithText("Riffle").assertIsDisplayed()
        onNodeWithTag(TestTags.NAV_DRAWER_SETTINGS).performClick()
        onNodeWithText("Settings").assertIsDisplayed()
        onNodeWithText("BackFromSettings").performClick()
        onNodeWithText("Riffle").assertIsDisplayed()
    }

    // ── pending nav routing ───────────────────────────────────────────────────────────────────

    @Test
    fun pendingLibraryNavDeliveredToLibraryScopeOnly() = runComposeUiTest {
        var libraryReceived: LibraryNav? = null
        var settingsReceived: LibraryNav? = LibraryNav.Items
        val nav = LibraryNav.Items
        setContent {
            AppShellContent(
                isTabletLayout = true,
                allServers = emptyList(),
                activeServer = null,
                visibleLibraries = emptyList(),
                activeLibraryId = null,
                isRiffleMode = false,
                showDownloadsLink = true,
                serverVersions = emptyMap(),
                externalPendingLibraryNav = nav,
                onExternalPendingLibraryNavHandled = {},
                onServerSelected = {},
                onLibrarySelected = {},
                onSetRiffleActive = {},
                appVersion = null,
                appSha = null,
                libraryContent = {
                    libraryReceived = pendingLibraryNav
                    Text("Library")
                },
                riffleContent = { Text("Riffle") },
                downloadsContent = { Text("Downloads") },
                settingsContent = {
                    settingsReceived = pendingLibraryNav
                    Text("Settings")
                },
            )
        }
        // Library is shown initially — it receives the nav
        assertEquals(nav, libraryReceived)
        // Navigate to settings — it must NOT receive the nav
        onNodeWithTag(TestTags.NAV_DRAWER_SETTINGS).performClick()
        onNodeWithText("Settings").assertIsDisplayed()
        assertEquals(null, settingsReceived)
    }
}
