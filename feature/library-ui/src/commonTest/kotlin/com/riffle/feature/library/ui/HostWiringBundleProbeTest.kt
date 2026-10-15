package com.riffle.feature.library.ui

import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.feature.designsystem.HostWiringProbe
import com.riffle.feature.designsystem.NoOp
import com.riffle.feature.library.AnnotationSearchResult
import com.riffle.feature.library.AudiobookBookmarkSearchResult
import com.riffle.feature.library.LibrarySectionType
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
    fun libraryItemsActionsDetectsShowAllAnnotationsNoOp() {
        val actions = realLibraryItemsActions().copy(
            onShowAllAnnotations = NoOp.showAllAnnotationsUnavailableOnIos,
        )
        assertEquals(listOf("onShowAllAnnotations"), HostWiringProbe.check(actions.probeFields()))
    }

    @Test
    fun libraryItemsActionsIosAllowlistPassesProbe() {
        val iosActions = realLibraryItemsActions().copy(
            onShowAllAnnotations = NoOp.showAllAnnotationsUnavailableOnIos,
            onNavigateBack = NoOp.backHandledByIosHost,
        )
        val allowlist = setOf("onShowAllAnnotations", "onNavigateBack")
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
