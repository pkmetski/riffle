package com.riffle.shared

import androidx.compose.runtime.Composable
import com.riffle.feature.navigation.LibraryNav
import com.riffle.shared.audiobook.AudiobookPlayerScreen
import com.riffle.shared.reader.CbzReaderScreen
import com.riffle.shared.reader.EpubReaderScreen
import com.riffle.shared.reader.PdfReaderScreen

@Composable
internal fun ReaderHost(
    destination: LibraryNav.ReaderDestination,
    onBack: () -> Unit,
    onPlaylistAdvance: (sourceId: String, nextItemId: String) -> Unit,
    onSwitchToReadaloud: ((ebookItemId: String, atSec: Double) -> Unit)? = null,
    onSwitchToAudiobook: (() -> Unit)? = null,
) {
    when (destination) {
        is LibraryNav.Reader -> EpubReaderScreen(
            item = destination.item,
            onBack = onBack,
            initialLocatorJson = destination.initialLocatorJson,
            startHref = destination.startHref,
            startReadaloudAtSec = destination.startReadaloudAtSec,
            onSwitchToAudiobook = if (destination.item.isListenable) onSwitchToAudiobook else null,
        )
        is LibraryNav.PdfReader -> PdfReaderScreen(item = destination.item, onBack = onBack)
        is LibraryNav.CbzReader -> CbzReaderScreen(item = destination.item, onBack = onBack)
        is LibraryNav.AudiobookPlayer -> AudiobookPlayerScreen(
            item = destination.item,
            playlistId = destination.playlistId,
            playlistLibraryId = destination.playlistLibraryId,
            startAtSec = destination.startAtSec,
            onBack = onBack,
            onPlaylistAdvance = onPlaylistAdvance,
            onSwitchToReadaloud = onSwitchToReadaloud,
        )
    }
}
