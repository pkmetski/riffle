package com.riffle.app.feature.audiobook

import com.riffle.core.models.AudiobookTrackSpan

/**
 * Process-scoped pointer to the active audiobook's track spans and total duration, set by
 * [AudiobookController] before it queues tracks and cleared when the session ends.
 *
 * Mirrors the [com.riffle.app.feature.reader.readaloud.SharedBundle] pattern: the
 * [com.riffle.app.feature.reader.readaloud.AudioPlayerService] is platform-constructed (no DI), so
 * out-of-band state sharing is the only seam available. Used by [AbsolutePositionPlayer] to compute
 * book-absolute position / total duration for the OS media controls.
 */
object SharedAudiobookContext {
    @Volatile var spans: List<AudiobookTrackSpan> = emptyList()
    @Volatile var totalDurationMs: Long = 0L
}
