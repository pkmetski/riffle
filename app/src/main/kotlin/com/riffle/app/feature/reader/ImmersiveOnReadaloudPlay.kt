package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Drops the reader into immersive mode whenever readaloud transitions into playing.
 *
 * [ImmersiveModeState.hide] collapses BOTH the OS system bars AND the reader TopAppBar (the bar's
 * visibility is bound to `!isImmersive`), so a single call hides all chrome together.
 *
 * One-way by design: pausing does NOT restore the chrome, and a manual tap to reveal it still
 * works — the next play re-hides. [ImmersiveModeState.hide] is idempotent and does not touch the
 * persisted `savedIsImmersive` flag, so re-entering on every play never fights the rotation /
 * sleep-resume restore logic. The readaloud mini-player stays visible (it is gated on the player
 * being open, not on immersive state), so only the top bar and system bars go away.
 */
@Composable
internal fun ImmersiveOnReadaloudPlay(
    isReadaloudPlaying: Boolean,
    immersiveState: ImmersiveModeState,
) {
    LaunchedEffect(isReadaloudPlaying) {
        if (isReadaloudPlaying) immersiveState.hide()
    }
}
