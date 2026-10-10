package com.riffle.app.feature.audiobook

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffle.feature.player.AudiobookPlayerEvent
import com.riffle.feature.player.AudiobookPlayerViewModel
import com.riffle.feature.player.ui.AudiobookPlayerBody
import com.riffle.feature.player.ui.playerChromeLabels
import org.koin.androidx.compose.koinViewModel

/**
 * Android's host for the full-screen [Audiobook Player] (ADR 0035). The chrome itself —
 * cover, scrubber, transport, speed, sleep timer, chapters/bookmarks sheets, the corner ribbon and
 * the swipe-down readaloud handoff — is [AudiobookPlayerBody] in `:feature:player-ui`, rendered by
 * iOS from `IosAudiobookPlayerScreen` as well. This file supplies only what is genuinely
 * Android-specific: the Koin ViewModel and the `WindowSizeClass` the two-column decision is taken from.
 */
@Composable
fun AudiobookPlayerScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onSwitchToReadaloud: (ebookItemId: String, atSec: Double) -> Unit = { _, _ -> },
    /** Called on end-of-book when the player was opened inside a playlist context (via
     *  [PlaylistDetailScreen]) and there IS a next item. Callers navigate to the next item's
     *  audiobook player, preserving the playlist context so auto-advance chains through. */
    onPlaylistAdvance: (sourceId: String, nextItemId: String) -> Unit = { _, _ -> },
    viewModel: AudiobookPlayerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // When the book ends naturally (last track played through to STATE_ENDED), either advance to
    // the next playlist item (if opened inside a playlist context with a successor) or close the
    // player. The VM's onCleared() stops the MediaSession, which clears the foreground notification.
    val latestOnNavigateBack = rememberUpdatedState(onNavigateBack)
    val latestOnPlaylistAdvance = rememberUpdatedState(onPlaylistAdvance)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AudiobookPlayerEvent.Finished -> latestOnNavigateBack.value()
                is AudiobookPlayerEvent.PlaylistAdvance -> latestOnPlaylistAdvance.value(event.sourceId, event.nextItemId)
            }
        }
    }
    // Any short (Compact-height) window — i.e. a phone in landscape — is too short for the vertical
    // layout (the square cover pushes the controls off-screen), so split into cover+details / controls.
    val twoColumn = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    AudiobookPlayerBody(
        viewModel = viewModel,
        state = state,
        labels = playerChromeLabels(),
        onNavigateBack = onNavigateBack,
        twoColumn = twoColumn,
        onSwitchToReadaloud = onSwitchToReadaloud,
    )
}
