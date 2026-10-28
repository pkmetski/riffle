package com.riffle.shared.audiobook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.ui.ScreenScopedViewModelHost
import com.riffle.feature.player.AudiobookPlayerEvent
import com.riffle.feature.player.AudiobookPlayerViewModel
import com.riffle.feature.player.ui.AudiobookPlayerBody
import com.riffle.feature.player.ui.isCompactPlayerHeight
import com.riffle.feature.player.ui.isCompactPlayerWidth
import com.riffle.feature.player.ui.playerChromeLabels
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

/**
 * iOS's host for the full-screen audiobook player.
 *
 * Everything the user sees is `:feature:player-ui`'s [AudiobookPlayerBody] — the same composable
 * `:app` renders — so the cover art, the draggable chapter-map scrubber with its buffer band and
 * chapter/bookmark ticks, the remaining-time readout, the speed sheet, the sleep timer, the
 * chapters and bookmarks sheets, the corner bookmark ribbon and the swipe-down readaloud handoff
 * are one implementation rather than two. This file supplies only the iOS-specific parts: the
 * screen-scoped ViewModel host, the English label catalogue (iOS has no i18n mechanism yet, #1072
 * §5) and the two-column decision, which iOS derives from the window rather than from Android's
 * `WindowSizeClass`.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AudiobookPlayerScreen(
    item: LibraryItem,
    playlistId: String?,
    playlistLibraryId: String?,
    startAtSec: Double = -1.0,
    onBack: () -> Unit,
    onPlaylistAdvance: (sourceId: String, nextItemId: String) -> Unit,
    onSwitchToReadaloud: ((ebookItemId: String, atSec: Double) -> Unit)? = null,
) {
    // The ViewModel is a Koin `factory` and iOS has no navigation-provided ViewModelStoreOwner, so
    // without an explicit host nothing ever calls AudiobookPlayerViewModel.onCleared() — the follow
    // loop keeps running, the final progress push never happens and controller.stop() (which is what
    // disposes the AVQueuePlayer) is skipped, leaving audio playing after Back.
    val koin = getKoin()
    val host = remember(item.id) { ScreenScopedViewModelHost() }
    val vm: AudiobookPlayerViewModel = remember(item.id) {
        // Playlist context (indices 2–3) and start position (index 4) are always supplied
        // positionally so `params.get(N)` never throws. Empty string for absent playlist;
        // -1f for "no start position" (the ViewModel treats negative as absent).
        host.adopt(
            koin.get {
                parametersOf(item.id, item.sourceId, playlistId.orEmpty(), playlistLibraryId.orEmpty(), startAtSec.toFloat())
            },
        )
    }
    val state by vm.uiState.collectAsState()

    DisposableEffect(item.id) { onDispose { host.clear() } }

    // End of book: the VM stops the player and emits Finished. Android closes the screen on it;
    // iOS used to collect nothing at all, so a finished book left the player sitting on a dead
    // transport with the scrubber pinned at the end.
    val latestOnBack = rememberUpdatedState(onBack)
    val latestOnPlaylistAdvance = rememberUpdatedState(onPlaylistAdvance)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                AudiobookPlayerEvent.Finished -> latestOnBack.value()
                // Reachable since PlaylistDetailScreen's Play carries the playlist context in
                // (#1072 §1). Same handling as Android's ReaderNavGraph: hop into the next
                // item's player rather than closing.
                is AudiobookPlayerEvent.PlaylistAdvance -> {
                    // iOS creates a fresh AVQueuePlayer bridge for the next item, so the
                    // outgoing bridge must be stopped here. Without this, both the old and new
                    // players run simultaneously until the old VM's onCleared fires.
                    vm.stopPlayerBridgeForPlaylistAdvance()
                    latestOnPlaylistAdvance.value(event.sourceId, event.nextItemId)
                }
            }
        }
    }

    // Android takes this from the activity's WindowSizeClass; iOS has no such API, so the window
    // dimensions go through the shared breakpoints. Two-column requires compact height AND enough
    // width (≥ 600dp) — a narrow compact-height screen (e.g. a foldable cover screen) cannot fit
    // the 300dp cover column alongside the controls and must use the vertical layout instead.
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val twoColumn = isCompactPlayerHeight(containerSize.height / density)
        && !isCompactPlayerWidth(containerSize.width / density)

    AudiobookPlayerBody(
        viewModel = vm,
        state = state,
        labels = playerChromeLabels(),
        onNavigateBack = onBack,
        twoColumn = twoColumn,
        onSwitchToReadaloud = onSwitchToReadaloud,
    )
}
