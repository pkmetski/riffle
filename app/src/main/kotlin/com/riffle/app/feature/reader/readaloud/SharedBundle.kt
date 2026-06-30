package com.riffle.app.feature.reader.readaloud

import androidx.media3.datasource.DataSource
import com.riffle.core.data.StreamingMediaItem
import com.riffle.core.logging.Logger
import java.io.File

/**
 * Process-scoped pointer to the Readaloud audio source the [AudioPlayerService] should play from.
 * The service is constructed by the platform; before issue #333's refactor it could not be injected,
 * and even now the *active book pointer* still flows out-of-band between the controllers (which
 * choose the book) and the service-side media sources (which read from disk). One holder, two modes.
 *
 * Two modes coexist (ADR 0028): the **bundle** path streams audio entries out of the synced EPUB on
 * disk ([current], read by [com.riffle.app.feature.audio.BundleAudioSourceFactory]); the **streaming**
 * path plays ABS tracks over HTTP with a write-through cache ([streaming], consumed by
 * [com.riffle.app.feature.audio.StreamingReadaloudItemRestorer]). There is only ever one Readaloud
 * at a time.
 */
object SharedBundle {
    @Volatile
    var current: File? = null

    @Volatile
    var streaming: Streaming? = null

    @Volatile
    var logger: Logger? = null

    /** Streaming state for the active book: how to fetch ABS audio, and the per-segment plan. */
    class Streaming(
        val httpFactory: DataSource.Factory,
        val itemsByMediaId: Map<String, StreamingMediaItem>,
    )
}
