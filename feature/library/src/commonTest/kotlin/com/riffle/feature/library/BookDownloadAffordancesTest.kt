package com.riffle.feature.library

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides which download controls an item detail screen shows.
 *
 * It only became assertable when it moved out of Android's `ActionRow` composable: iOS rendered
 * no download control at all, so every one of these decisions was Android-only behaviour that
 * the shared `BookDownloadControls` now has to reproduce exactly.
 *
 * Runs on `iosSimulatorArm64` as part of `:feature:library:iosSimulatorArm64Test`.
 */
class BookDownloadAffordancesTest {

    private fun item(
        ebookFormat: EbookFormat = EbookFormat.Epub,
        listenable: Boolean = false,
    ) = LibraryItem(
        id = "item-1",
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = ebookFormat,
        hasAudio = listenable,
    )

    @Test
    fun ebookControlIsHiddenWhenTheSourceHasNoDownloads() {
        val affordances = bookDownloadAffordances(
            item = item(),
            capabilities = DetailCapabilities.All.copy(hasDownloads = false),
            isOffline = false,
            audiobookDownloadState = null,
            readaloudDownloadState = null,
        )
        assertFalse(affordances.showEbook, "LocalFiles has no local store; no ebook download button")
        assertFalse(affordances.any)
    }

    @Test
    fun ebookControlShowsForAReadableItemOnASourceWithDownloads() {
        val affordances = bookDownloadAffordances(
            item = item(),
            capabilities = DetailCapabilities.All,
            isOffline = false,
            audiobookDownloadState = null,
            readaloudDownloadState = null,
        )
        assertTrue(affordances.showEbook)
        assertTrue(affordances.ebookEnabled)
        assertTrue(affordances.any)
    }

    @Test
    fun audiobookControlNeedsBothTheCapabilityAndAKnownAudiobookState() {
        val withoutState = bookDownloadAffordances(
            item = item(listenable = true),
            capabilities = DetailCapabilities.All,
            isOffline = false,
            audiobookDownloadState = null,
            readaloudDownloadState = null,
        )
        assertFalse(withoutState.showAudiobook, "null state means the VM has not decided yet")

        val withState = bookDownloadAffordances(
            item = item(listenable = true),
            capabilities = DetailCapabilities.All,
            isOffline = false,
            audiobookDownloadState = DownloadState.NotDownloaded,
            readaloudDownloadState = null,
        )
        assertTrue(withState.showAudiobook)
    }

    @Test
    fun offlineDisablesTheAudiobookControlOnlyWhenNothingIsStoredYet() {
        val nothingStored = bookDownloadAffordances(
            item = item(listenable = true),
            capabilities = DetailCapabilities.All,
            isOffline = true,
            audiobookDownloadState = DownloadState.NotDownloaded,
            readaloudDownloadState = null,
        )
        assertFalse(nothingStored.audiobookEnabled, "cannot fetch tracks with no network")

        val alreadyDownloaded = bookDownloadAffordances(
            item = item(listenable = true),
            capabilities = DetailCapabilities.All,
            isOffline = true,
            audiobookDownloadState = DownloadState.Downloaded,
            readaloudDownloadState = null,
        )
        assertTrue(alreadyDownloaded.audiobookEnabled, "removing a local copy needs no network")
    }

    @Test
    fun readaloudControlIsGatedOnTheReadaloudCapabilityNotOnDownloads() {
        val affordances = bookDownloadAffordances(
            item = item(),
            capabilities = DetailCapabilities.All.copy(hasDownloads = false, hasReadaloud = true),
            isOffline = false,
            audiobookDownloadState = null,
            readaloudDownloadState = DownloadState.NotDownloaded,
        )
        assertFalse(affordances.showEbook)
        assertTrue(affordances.showReadaloud, "readaloud is a Storyteller sidecar, not a Source download")
    }

    @Test
    fun readaloudControlIsHiddenWithoutAMatchedStorytellerLink() {
        val affordances = bookDownloadAffordances(
            item = item(),
            capabilities = DetailCapabilities.All,
            isOffline = false,
            audiobookDownloadState = null,
            readaloudDownloadState = null,
        )
        assertFalse(affordances.showReadaloud)
    }

    @Test
    fun downloadOutcomeReportsCompletionOnlyWhenTheRunLanded() {
        assertEquals(
            BookDownloadOutcome.Completed,
            bookDownloadOutcome(DownloadState.InProgress(90), DownloadState.Downloaded),
        )
        assertEquals(
            BookDownloadOutcome.Failed,
            bookDownloadOutcome(DownloadState.InProgress(90), DownloadState.NotDownloaded),
        )
        assertEquals(
            BookDownloadOutcome.Failed,
            bookDownloadOutcome(DownloadState.InProgress(null), DownloadState.Cached),
        )
    }

    @Test
    fun downloadOutcomeIsSilentForEveryTransitionThatIsNotTheEndOfARun() {
        assertEquals(null, bookDownloadOutcome(null, DownloadState.Downloaded), "first emission")
        assertEquals(
            null,
            bookDownloadOutcome(DownloadState.NotDownloaded, DownloadState.InProgress(0)),
            "starting a download is not an outcome",
        )
        assertEquals(
            null,
            bookDownloadOutcome(DownloadState.InProgress(10), DownloadState.InProgress(20)),
            "progress ticks are not an outcome",
        )
        assertEquals(
            null,
            bookDownloadOutcome(DownloadState.Downloaded, DownloadState.NotDownloaded),
            "a removal is not a failed download",
        )
    }
}
