package com.riffle.shared.settings

import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicBackgroundThemeOptions
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import com.riffle.feature.settings.PanelOverflowOptions
import com.riffle.feature.settings.ReadaloudMatchSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the composed strings and the chip selections the iOS Settings screen renders.
 *
 * These live as `internal` top-level functions rather than inline in a Composable precisely so
 * this test can reach them — every one of them is a site that had drifted away from the shared
 * derivation, and a Composable body is not reachable from a unit test.
 */
class SettingsScreenDerivationsTest {

    // --- Listening summary + speed range (item 16c) ---

    /**
     * The private `formatSpeed` this replaced truncated to one decimal, so a 0.75× default read
     * "0.7×" — a speed the player will never be set to. Reverting turns this red.
     */
    @Test fun listeningSummaryPrintsTheSpeedAtTheDomainsStepGranularity() {
        assertEquals("Speed 0.75× · Skip 30s · Rewind 10s", listeningSummary(0.75f, 30, 10))
        assertEquals("Speed 1.25× · Skip 15s · Rewind 5s", listeningSummary(1.25f, 15, 5))
    }

    @Test fun listeningSummaryTrimsAWholeNumberSpeed() {
        assertEquals("Speed 1× · Skip 30s · Rewind 10s", listeningSummary(1.0f, 30, 10))
    }

    // --- Readaloud subtitle (item 16j) ---

    private fun storyteller(username: String = "reader") = Source(
        id = "sty-1",
        url = SourceUrl.parse("https://storyteller.example.com")!!,
        isActive = false,
        insecureConnectionAllowed = false,
        username = username,
        serverType = ServerType.STORYTELLER_SERVICE,
    )

    /**
     * The private copy printed "N matched · M unmatched" unconditionally, so a server with
     * nothing unmatched still advertised "0 unmatched", and the host and version were dropped
     * the moment any summary existed.
     */
    @Test fun readaloudSubtitleSuppressesZeroCountsAndKeepsHostAndVersion() {
        val subtitle = readaloudSubtitle(
            storyteller(),
            serverVersions = mapOf("sty-1" to "1.4.0"),
            readaloudSummaries = mapOf(
                "sty-1" to ReadaloudMatchSummary(
                    unmatchedCount = 0,
                    suggestedCount = 0,
                    partiallyMatchedCount = 0,
                    matchedCount = 12,
                ),
            ),
        )
        assertEquals("reader@storyteller.example.com · v1.4.0 · 12 matched", subtitle)
    }

    @Test fun readaloudSubtitleHasAFriendlyEmptyCase() {
        val subtitle = readaloudSubtitle(
            storyteller(),
            serverVersions = emptyMap(),
            readaloudSummaries = mapOf(
                "sty-1" to ReadaloudMatchSummary(0, 0, 0, 0),
            ),
        )
        assertEquals("reader@storyteller.example.com · no readalouds yet", subtitle)
    }

    // --- Chip selection is keyed on the value, not on rendered text ---

    /**
     * iOS offered the three concrete backdrops only and selected on the raw stored value, so a
     * stored `Auto` highlighted nothing — there was no chip it could equal. The option list now
     * carries Auto, the same set Android's picker renders.
     */
    @Test fun comicBackgroundOptionsIncludeAuto() {
        assertTrue(ReaderTheme.Auto in ComicBackgroundThemeOptions)
        assertEquals(ReaderTheme.Auto, comicBackgroundChipSelection(ReaderTheme.Auto))
    }

    @Test fun comicBackgroundChipFoldsDarkDimOntoDark() {
        // There is no separate Dim backdrop; Dim and Dark paint the same page behind a comic.
        assertEquals(ReaderTheme.Dark, comicBackgroundChipSelection(ReaderTheme.DarkDim))
    }

    @Test fun everyStoredComicBackgroundThemeSelectsAChipThatExists() {
        for (theme in ReaderTheme.entries) {
            assertTrue(
                comicBackgroundChipSelection(theme) in ComicBackgroundThemeOptions,
                "no comic background chip would highlight for $theme",
            )
        }
    }

    @Test fun panelOverflowChipsCoverEveryBehaviour() {
        assertEquals(PanelOverflowBehavior.entries.toSet(), PanelOverflowOptions.ORDER.toSet())
    }
}
