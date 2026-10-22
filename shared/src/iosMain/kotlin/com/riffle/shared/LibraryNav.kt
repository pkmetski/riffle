package com.riffle.shared

import androidx.compose.runtime.Composable
import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.player.ui.PlayerHostActions
import com.riffle.feature.reader.ui.ReaderHostActions
import com.riffle.shared.audiobook.AudiobookPlayerScreen
import com.riffle.shared.reader.CbzReaderScreen
import com.riffle.shared.reader.EpubReaderScreen
import com.riffle.shared.reader.PdfReaderScreen

@Composable
internal fun ReaderHost(
    destination: LibraryNav.ReaderDestination,
    readerActions: ReaderHostActions,
    playerActions: PlayerHostActions,
) {
    when (destination) {
        is LibraryNav.Reader -> EpubReaderScreen(
            item = destination.item,
            onBack = readerActions.onBack,
            initialLocatorJson = destination.initialLocatorJson,
            startHref = destination.startHref,
            startReadaloudAtSec = destination.startReadaloudAtSec,
            source = destination.readerSource,
            onSwitchToAudiobook = if (destination.item.isListenable) readerActions.onSwitchToAudiobook else null,
        )
        is LibraryNav.PdfReader -> PdfReaderScreen(item = destination.item, onBack = readerActions.onBack)
        is LibraryNav.CbzReader -> CbzReaderScreen(item = destination.item, onBack = readerActions.onBack)
        is LibraryNav.AudiobookPlayer -> AudiobookPlayerScreen(
            item = destination.item,
            playlistId = destination.playlistId,
            playlistLibraryId = destination.playlistLibraryId,
            startAtSec = destination.startAtSec,
            onBack = playerActions.onBack,
            onPlaylistAdvance = playerActions.onPlaylistAdvance,
            onSwitchToReadaloud = playerActions.onSwitchToReadaloud,
        )
    }
}
