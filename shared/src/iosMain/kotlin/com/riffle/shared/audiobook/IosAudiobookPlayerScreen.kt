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
import com.riffle.feature.player.AudiobookPlayerEvent
import com.riffle.feature.player.AudiobookPlayerViewModel
import com.riffle.feature.player.ui.AudiobookPlayerBody
import com.riffle.feature.player.ui.PlayerChromeLabels
import com.riffle.feature.player.ui.isCompactPlayerHeight
import com.riffle.shared.ScreenScopedViewModelHost
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
actual fun AudiobookPlayerScreen(item: LibraryItem, onBack: () -> Unit) {
    // The ViewModel is a Koin `factory` and iOS has no navigation-provided ViewModelStoreOwner, so
    // without an explicit host nothing ever calls AudiobookPlayerViewModel.onCleared() — the follow
    // loop keeps running, the final progress push never happens and controller.stop() (which is what
    // disposes the AVQueuePlayer) is skipped, leaving audio playing after Back.
    val koin = getKoin()
    val host = remember(item.id) { ScreenScopedViewModelHost() }
    val vm: AudiobookPlayerViewModel = remember(item.id) {
        host.adopt(koin.get { parametersOf(item.id, item.sourceId) })
    }
    val state by vm.uiState.collectAsState()

    DisposableEffect(item.id) { onDispose { host.clear() } }

    // End of book: the VM stops the player and emits Finished. Android closes the screen on it;
    // iOS used to collect nothing at all, so a finished book left the player sitting on a dead
    // transport with the scrubber pinned at the end.
    val latestOnBack = rememberUpdatedState(onBack)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                AudiobookPlayerEvent.Finished -> latestOnBack.value()
                // Playlist auto-advance needs a playlist context to have opened the player, which
                // no iOS surface can supply yet (see the Koin factory's note) — so it cannot be
                // emitted here. Treated as "the book ended" rather than silently ignored.
                is AudiobookPlayerEvent.PlaylistAdvance -> latestOnBack.value()
            }
        }
    }

    // Android takes this from the activity's WindowSizeClass; iOS has no such API, so the window's
    // own height goes through the shared breakpoint instead of a second hard-coded threshold.
    val containerHeightPx = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current.density
    val twoColumn = isCompactPlayerHeight(containerHeightPx / density)

    AudiobookPlayerBody(
        viewModel = vm,
        state = state,
        labels = PlayerChromeLabels.English,
        onNavigateBack = onBack,
        twoColumn = twoColumn,
    )
}
