package com.riffle.app.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.riffle.core.domain.Library
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerSettingsExpansionTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun server(type: ServerType, active: Boolean) = Server(
        id = "srv-1",
        url = ServerUrl.parse("https://example.com")!!,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "",
        serverType = type,
    )

    private fun libraryItem(id: String, visible: Boolean = true) = LibraryUiItem(
        library = Library(id = id, name = id, mediaType = "book", isUnsupported = false),
        isVisible = visible,
        switchEnabled = true,
    )

    @Test
    fun activeAbsServerShowsLibrarySwitches() {
        composeTestRule.setContent {
            ServerSettingsExpansion(
                server = server(ServerType.AUDIOBOOKSHELF, active = true),
                libraryItems = listOf(libraryItem("Fiction"), libraryItem("Non-fiction")),
                summary = null,
                onSetLibraryVisible = { _, _ -> },
                onOpenReadaloudMatches = {},
            )
        }

        composeTestRule.onNodeWithText("Enabled libraries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fiction").assertIsDisplayed()
        composeTestRule.onNodeWithText("Non-fiction").assertIsDisplayed()
    }

    @Test
    fun togglingALibrarySwitchInvokesCallback() {
        var toggledLibrary: String? = null
        var toggledVisible: Boolean? = null
        composeTestRule.setContent {
            ServerSettingsExpansion(
                server = server(ServerType.AUDIOBOOKSHELF, active = true),
                libraryItems = listOf(libraryItem("Fiction", visible = true)),
                summary = null,
                onSetLibraryVisible = { id, visible -> toggledLibrary = id; toggledVisible = visible },
                onOpenReadaloudMatches = {},
            )
        }

        composeTestRule.onAllNodes(isToggleable()).onFirst().performClick()

        assertEquals("Fiction", toggledLibrary)
        assertEquals(false, toggledVisible)
    }

    @Test
    fun inactiveAbsServerStillShowsItsLibraries() {
        composeTestRule.setContent {
            ServerSettingsExpansion(
                server = server(ServerType.AUDIOBOOKSHELF, active = false),
                libraryItems = listOf(libraryItem("Fiction")),
                summary = null,
                onSetLibraryVisible = { _, _ -> },
                onOpenReadaloudMatches = {},
            )
        }

        // No activation required — a non-active server manages its own libraries.
        composeTestRule.onNodeWithText("Fiction").assertIsDisplayed()
    }

    @Test
    fun storytellerServerShowsMatchesSummaryAndNavigates() {
        var opened = false
        composeTestRule.setContent {
            ServerSettingsExpansion(
                server = server(ServerType.STORYTELLER, active = true),
                libraryItems = emptyList(),
                summary = ReadaloudMatchSummary(unmatchedCount = 2, suggestedCount = 1, partiallyMatchedCount = 1, matchedCount = 3),
                onSetLibraryVisible = { _, _ -> },
                onOpenReadaloudMatches = { opened = true },
            )
        }

        composeTestRule.onNodeWithText("Readaloud matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 unmatched · 1 suggested · 1 partially matched · 3 matched").assertIsDisplayed()
        composeTestRule.onNodeWithText("Review & match readalouds").performClick()

        assertTrue(opened)
    }
}
