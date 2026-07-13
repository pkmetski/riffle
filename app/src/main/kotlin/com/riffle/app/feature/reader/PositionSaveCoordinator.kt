package com.riffle.app.feature.reader

/**
 * Shared local-persistence policy for both the ebook reader and the audiobook player.
 *
 * Hot path ([onChanged], every scroll / playback tick): persist only the precise position — never
 * write the `readingProgress` float, because that hits `library_items` and fires Room's
 * InvalidationTracker for every active library Flow (scroll-framerate recompositions).
 *
 * Cold path ([onClose], once per pause / close): persist ONLY the `readingProgress` float. The
 * position itself has already been saved by the last `onChanged` (or by a `ServerWins` adoption
 * that ran via [com.riffle.core.domain.PositionStore.acceptServer]) — saving again on close would
 * risk overwriting a fresh server value with a stale in-memory locator if the ServerLocator UI
 * jump hadn't landed yet (#528).
 *
 * Only local persistence lives here; each medium's backend (ABS) sync runs *outside* this
 * coordinator — the reader's progress-sync controller / audio-led cycle. [savePosition] is optional:
 * the ebook stores its CFI locally for offline resume via `onChanged`; the audiobook resumes from
 * the server and has no local position to save at all.
 */
class PositionSaveCoordinator<P>(
    private val updateProgress: suspend (progress: Float) -> Unit,
    private val savePosition: suspend (position: P) -> Unit = {},
) {
    suspend fun onChanged(position: P) {
        savePosition(position)
    }

    suspend fun onClose(progress: Float) {
        updateProgress(progress)
    }
}
