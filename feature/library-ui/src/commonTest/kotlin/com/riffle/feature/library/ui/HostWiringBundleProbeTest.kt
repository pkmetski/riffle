package com.riffle.feature.library.ui

import com.riffle.feature.designsystem.HostWiringProbe
import com.riffle.feature.designsystem.NoOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostWiringBundleProbeTest {

    private fun realLibraryItemsActions() = LibraryItemsActions(
        onOpenDrawer = {},
        onItemSelected = { _ -> },
        onAnnotatedBookSelected = { _, _ -> },
        onSeriesSelected = { _ -> },
        onCollectionSelected = { _ -> },
        onSectionSeeMore = { _ -> },
        onPlaylistSelected = { _ -> },
        onSearchAnnotations = { _ -> },
        onAnnotationSelected = { _ -> },
        onAudiobookBookmarkSelected = { _ -> },
        onShowAllAnnotations = { _ -> },
        onNavigateBack = {},
    )

    @Test
    fun libraryItemsActionsHasNoViolationsWhenFullyWired() {
        val actions = realLibraryItemsActions()
        assertTrue(HostWiringProbe.check(actions.probeFields()).isEmpty())
    }

    @Test
    fun libraryItemsActionsIosAllowlistPassesProbe() {
        val iosActions = realLibraryItemsActions().copy(
            onNavigateBack = NoOp.backHandledByIosHost,
        )
        val allowlist = setOf("onNavigateBack")
        assertTrue(HostWiringProbe.check(iosActions.probeFields(), allowlist).isEmpty())
    }

    private fun realLibraryItemDetailActions() = LibraryItemDetailActions(
        onBack = {},
        onRead = { _ -> },
        onListen = { _ -> },
        onReadItemAtHref = { _, _ -> },
        onListenItemAtSec = { _, _ -> },
        onFacetSelected = { _, _, _ -> },
        onNavigateToSeries = { _, _, _ -> },
    )

    @Test
    fun libraryItemDetailActionsHasNoViolationsWhenFullyWired() {
        val actions = realLibraryItemDetailActions()
        assertTrue(HostWiringProbe.check(actions.probeFields()).isEmpty())
    }

    @Test
    fun libraryItemDetailActionsDetectsFacetNavNoOp() {
        val actions = realLibraryItemDetailActions().copy(
            onFacetSelected = NoOp.facetNavUnavailableInDownloads,
        )
        assertEquals(listOf("onFacetSelected"), HostWiringProbe.check(actions.probeFields()))
    }

    @Test
    fun downloadsActionsHasNoViolationsWhenFullyWired() {
        val actions = DownloadsActions(
            onNavigateBack = {},
            onItemSelected = { _ -> },
        )
        assertTrue(HostWiringProbe.check(actions.probeFields()).isEmpty())
    }
}
