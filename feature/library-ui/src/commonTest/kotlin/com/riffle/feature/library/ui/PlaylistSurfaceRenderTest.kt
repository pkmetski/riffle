package com.riffle.feature.library.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.CatalogPlaylist
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real playlist composables. Excluded from the JVM host-test task (Compose's UI
 * harness needs an Android runtime and the repo has no Robolectric) and run for real on
 * `:feature:library-ui:iosSimulatorArm64Test` — so these ARE the iOS-path assertions for the
 * playlist surfaces (#1072 §1).
 *
 * Every case here is something iOS could not do before: drill into a playlist, and add or remove
 * the current item from one.
 */
@OptIn(ExperimentalTestApi::class)
class PlaylistSurfaceRenderTest {

    private val labels = PlaylistLabels.English

    private val evening = CatalogPlaylist(id = "p1", rootId = "root", name = "Evening Queue", bookCount = 4)
    private val morning = CatalogPlaylist(
        id = "p2",
        rootId = "root",
        name = "Morning Queue",
        bookCount = 1,
        itemIds = listOf("item-1"),
    )

    /**
     * The revert this pins: iOS's deleted copy of the tab rendered names with no `clickable` at
     * all, so there was no way into a playlist. Removing the `onPlaylistSelected` wiring turns
     * `tapped` back to null.
     */
    @Test
    fun tappingAPlaylistRowDrillsIn() = runComposeUiTest {
        var tapped: CatalogPlaylist? = null
        setContent {
            PlaylistsTabContent(
                playlists = listOf(evening, morning),
                labels = labels,
                onPlaylistSelected = { tapped = it },
            )
        }

        onNodeWithText("Evening Queue").assertIsDisplayed()
        onNodeWithTag("playlist-row-p1").performClick()

        assertEquals(evening, tapped)
    }

    @Test
    fun playlistRowsShowTheSharedItemCountWording() = runComposeUiTest {
        setContent {
            PlaylistsTabContent(
                playlists = listOf(evening, morning),
                labels = labels,
                onPlaylistSelected = {},
            )
        }

        onNodeWithText("4 items").assertIsDisplayed()
        onNodeWithText("1 item").assertIsDisplayed()
    }

    @Test
    fun theEmptyTabExplainsHowToCreateAPlaylist() = runComposeUiTest {
        setContent {
            PlaylistsTabContent(playlists = emptyList(), labels = labels, onPlaylistSelected = {})
        }

        onNodeWithText(labels.noPlaylistsFromAnyItem).assertIsDisplayed()
    }

    /**
     * `toggleItemInPlaylist` was bound and injected on iOS with zero callers because no surface
     * could reach it. Removing the picker row's `onToggle` wiring turns `toggled` back to null.
     */
    @Test
    fun tappingAPickerRowTogglesTheItemInThatPlaylist() = runComposeUiTest {
        var toggled: CatalogPlaylist? = null
        setContent {
            AddToPlaylistSheet(
                itemId = "item-1",
                playlistsFlow = flowOf(listOf(evening, morning)),
                labels = labels,
                onToggle = { toggled = it },
                onCreate = { "" },
                onDismiss = {},
            )
        }

        onNodeWithTag("playlist-pick-p1").performClick()

        assertEquals(evening, toggled)
    }

    /**
     * The tick is the only thing that tells the user the item is already in a playlist, and it is
     * keyed on membership rather than on a flag the caller passes — the picker reads
     * `itemId in playlist.itemIds`.
     */
    @Test
    fun onlyThePlaylistsContainingTheItemShowTheMembershipTick() = runComposeUiTest {
        setContent {
            AddToPlaylistSheet(
                itemId = "item-1",
                playlistsFlow = flowOf(listOf(evening, morning)),
                labels = labels,
                onToggle = {},
                onCreate = { "" },
                onDismiss = {},
            )
        }

        // morning contains item-1, evening does not — so exactly one tick, and it is labelled
        // for VoiceOver/TalkBack rather than being a bare glyph.
        onNodeWithContentDescription(labels.inThisPlaylist, useUnmergedTree = true).assertIsDisplayed()
    }

    /** "+ New playlist" must open the create dialog, not silently do nothing. */
    @Test
    fun theNewPlaylistRowOpensTheCreateDialog() = runComposeUiTest {
        setContent {
            AddToPlaylistSheet(
                itemId = "item-1",
                playlistsFlow = flowOf(emptyList()),
                labels = labels,
                onToggle = {},
                onCreate = { "" },
                onDismiss = {},
            )
        }

        assertTrue(onNodeWithTag("playlist-name-field").isNotDisplayedYet())
        onNodeWithTag("playlist-new").performClick()

        onNodeWithTag("playlist-name-field").assertIsDisplayed()
    }

    @Test
    fun theEmptyPickerExplainsHowToGetStarted() = runComposeUiTest {
        var dismissed: Boolean? = null
        setContent {
            AddToPlaylistSheet(
                itemId = "item-1",
                playlistsFlow = flowOf(emptyList()),
                labels = labels,
                onToggle = {},
                onCreate = { "" },
                onDismiss = { dismissed = true },
            )
        }

        onNodeWithText(labels.noPlaylistsGetStarted).assertIsDisplayed()
        assertNull(dismissed)
    }
}

@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.SemanticsNodeInteraction.isNotDisplayedYet(): Boolean =
    runCatching { assertIsDisplayed() }.isFailure
