package com.riffle.feature.player

/** One-shot UI events emitted by the [AudiobookPlayerViewModel] — collected by the screen. */
sealed interface AudiobookPlayerEvent {
    /** Playback finished naturally (last track ended); the screen should close the player. */
    data object Finished : AudiobookPlayerEvent

    /**
     * Playback finished AND the player was opened inside a playlist context that has a next
     * item. The screen navigates to the next item's audiobook player (preserving the same
     * playlist context) so playback auto-advances through the playlist.
     */
    data class PlaylistAdvance(val nextItemId: String) : AudiobookPlayerEvent
}
