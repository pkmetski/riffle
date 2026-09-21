package com.riffle.shared.audiobook

import androidx.compose.runtime.Composable
import com.riffle.core.models.LibraryItem

/**
 * Platform-specific audiobook player screen.
 * iOS actual: [IosAudiobookPlayerScreen] (AVQueuePlayer via bridge).
 * Android: Android uses its own NavGraph in :app and does not need an actual here.
 *
 * [playlistId] / [playlistLibraryId] carry the playlist the player was opened *from*, which is
 * what `AudiobookPlayerViewModel` needs to look up the next item at end-of-book and auto-advance
 * into it. Android supplies them as route query args
 * (`audiobook_player/{sourceId}/{itemId}?playlistId=…&libraryId=…`); on iOS they come from
 * [com.riffle.shared.LibraryNav.AudiobookPlayer], which the playlist detail screen fills in. Both
 * are `null` for every other entry point — a library row, the Riffle hub, a series or collection.
 *
 * [onPlaylistAdvance] is the other half of that context: at end-of-book the ViewModel resolves the
 * next item in the playlist and emits it, and the host navigates into its player instead of
 * closing. It cannot fire when [playlistId] is null.
 *
 * No default arguments: this composable is only called from Kotlin, but the surrounding module is
 * exported through the ObjC framework and defaults do not cross that boundary, so the repo's
 * convention is to spell every parameter out at the call site.
 */
@Composable
expect fun AudiobookPlayerScreen(
    item: LibraryItem,
    playlistId: String?,
    playlistLibraryId: String?,
    onBack: () -> Unit,
    onPlaylistAdvance: (sourceId: String, nextItemId: String) -> Unit,
)
