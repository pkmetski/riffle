package com.riffle.app.feature.reader

/**
 * Shared local-persistence policy for both the ebook reader and the audiobook player.
 *
 * Hot path ([onChanged], every scroll / playback tick): persist only the precise position — never
 * write the `readingProgress` float, because that hits `library_items` and fires Room's
 * InvalidationTracker for every active library Flow (scroll-framerate recompositions).
 *
 * Cold path ([onClose], once per pause / close): persist the position AND the `readingProgress`
 * float, so the detail and library screens reflect where the user stopped.
 *
 * Only local persistence lives here; each medium's backend (ABS) sync runs *outside* this
 * coordinator — the reader's progress-sync controller / audio-led cycle. [savePosition] is optional:
 * the ebook stores its CFI locally for offline resume, while the audiobook resumes from the server
 * and so has no local position to save (it only uses the cold-path progress write).
 */
class PositionSaveCoordinator<P>(
    private val updateProgress: suspend (progress: Float) -> Unit,
    private val savePosition: suspend (position: P) -> Unit = {},
) {
    suspend fun onChanged(position: P) {
        savePosition(position)
    }

    suspend fun onClose(position: P, progress: Float) {
        savePosition(position)
        updateProgress(progress)
    }
}
