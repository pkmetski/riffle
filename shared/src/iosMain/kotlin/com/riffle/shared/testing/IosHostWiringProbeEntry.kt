package com.riffle.shared.testing

import com.riffle.feature.designsystem.HostWiringProbe
import com.riffle.feature.designsystem.NoOp
import com.riffle.feature.library.ui.DownloadsActions
import com.riffle.feature.library.ui.LibraryItemDetailActions
import com.riffle.feature.library.ui.LibraryItemsActions
import com.riffle.feature.library.ui.probeFields
import com.riffle.feature.player.ui.PlayerHostActions
import com.riffle.feature.player.ui.probeFields
import com.riffle.feature.reader.ui.ReaderHostActions
import com.riffle.feature.reader.ui.probeFields
import com.riffle.feature.settings.ui.DefaultPlatformSettingsHooks
import com.riffle.feature.settings.ui.SettingsActions
import com.riffle.feature.settings.ui.probeFields

/**
 * Kotlin entry points callable from XCTest to verify that the iOS host wires all action bundles
 * correctly. Each function constructs a representative bundle reflecting the actual iOS wiring
 * (with intentional no-ops in the allowlist) and returns the list of unexpected violations.
 *
 * A test asserts the returned list is empty.
 */

fun iosLibraryItemsActionsViolations(): List<String> {
    val actions = LibraryItemsActions(
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
        onNavigateBack = NoOp.backHandledByIosHost,
    )
    val allowlist = setOf("onNavigateBack")
    return HostWiringProbe.check(actions.probeFields(), allowlist)
}

fun iosLibraryItemDetailActionsViolations(): List<String> {
    val actions = LibraryItemDetailActions(
        onBack = {},
        onRead = { _ -> },
        onListen = { _ -> },
        onReadItemAtHref = { _, _ -> },
        onListenItemAtSec = { _, _ -> },
        onFacetSelected = { _, _, _ -> },
        onNavigateToSeries = { _, _, _ -> },
    )
    return HostWiringProbe.check(actions.probeFields())
}

fun iosDownloadsDetailActionsViolations(): List<String> {
    val actions = LibraryItemDetailActions(
        onBack = {},
        onRead = { _ -> },
        onListen = { _ -> },
        onReadItemAtHref = { _, _ -> },
        onListenItemAtSec = { _, _ -> },
        onFacetSelected = NoOp.facetNavUnavailableInDownloads,
        onNavigateToSeries = NoOp.seriesNavUnavailableInDownloads,
    )
    val allowlist = setOf("onFacetSelected", "onNavigateToSeries")
    return HostWiringProbe.check(actions.probeFields(), allowlist)
}

fun iosDownloadsActionsViolations(): List<String> {
    val actions = DownloadsActions(
        onNavigateBack = {},
        onItemSelected = { _ -> },
    )
    return HostWiringProbe.check(actions.probeFields())
}

fun iosSettingsActionsViolations(): List<String> {
    val actions = SettingsActions(
        onNavigateBack = {},
        onNavigateToAddSource = { _, _ -> },
        onNavigateToAddSourcePicker = {},
        onNavigateToAddLocalFolder = {},
        onNavigateToReadaloudSettings = {},
        onNavigateToAnnotationsSyncSettings = {},
        onNavigateToDeveloperOptions = {},
        onNavigateToDictionaryPacks = {},
        onNavigateToDebugLogs = {},
        onNavigateToChangelog = {},
        platformHooks = DefaultPlatformSettingsHooks,
    )
    return HostWiringProbe.check(actions.probeFields())
}

fun iosReaderHostActionsViolations(): List<String> {
    val actions = ReaderHostActions(
        onBack = {},
        onSwitchToAudiobook = {},
    )
    return HostWiringProbe.check(actions.probeFields())
}

fun iosPlayerHostActionsViolations(): List<String> {
    val actions = PlayerHostActions(
        onBack = {},
        onPlaylistAdvance = NoOp.playlistAdvanceUnavailable,
        onSwitchToReadaloud = { _, _ -> },
    )
    val allowlist = setOf("onPlaylistAdvance")
    return HostWiringProbe.check(actions.probeFields(), allowlist)
}
