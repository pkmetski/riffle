package com.riffle.app.feature.reader.readaloud

import androidx.media3.datasource.DataSource
import java.io.File

/**
 * Process-scoped pointer to the synced EPUB bundle the [AudioPlayerService] should stream from.
 * The service is constructed by the platform (no DI handle to the chosen book), so the
 * [ReadaloudController] sets the current bundle here before connecting and queuing media. There is
 * only ever one Readaloud playing at a time, so a single slot is sufficient.
 */
internal object SharedBundle {
    @Volatile
    var current: File? = null

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory {
        val bundle = current ?: error("No Readaloud bundle set")
        ZipAudioDataSource(bundle)
    }
}
