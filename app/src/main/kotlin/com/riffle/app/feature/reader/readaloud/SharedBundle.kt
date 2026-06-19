package com.riffle.app.feature.reader.readaloud

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.riffle.core.data.StreamingMediaItem
import java.io.File
import java.io.IOException

/**
 * Process-scoped pointer to the Readaloud audio source the [AudioPlayerService] should play from.
 * The service is constructed by the platform (no DI handle to the chosen book), so the
 * [ReadaloudController] sets the current source here before connecting and queuing media.
 *
 * Two modes coexist (ADR 0028): the **bundle** path streams audio entries out of the synced EPUB on
 * disk ([current]); the **streaming** path plays ABS tracks over HTTP with a write-through cache
 * ([streaming]). A single [dataSourceFactory] serves both — it routes per request by URI scheme, so
 * the player never needs to be rebuilt when switching. There is only ever one Readaloud at a time.
 */
@OptIn(UnstableApi::class)
object SharedBundle {
    @Volatile
    var current: File? = null

    @Volatile
    var streaming: Streaming? = null

    /** Streaming state for the active book: how to fetch ABS audio, and the per-segment plan. */
    class Streaming(
        val httpFactory: DataSource.Factory,
        val itemsByMediaId: Map<String, StreamingMediaItem>,
    )

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory {
        ReadaloudDataSource(
            makeZip = { ZipAudioDataSource(current ?: error("No Readaloud bundle set")) },
            makeHttp = { streaming?.httpFactory?.createDataSource() },
        )
    }
}

/**
 * Delegates each open to the bundle ([makeZip]) or streaming ([makeHttp]) source by URI scheme —
 * `zipaudio://` for the on-disk bundle, `http(s)://` for ABS. Transfer listeners registered before
 * open are forwarded to whichever delegate handles the request.
 */
@OptIn(UnstableApi::class)
private class ReadaloudDataSource(
    private val makeZip: () -> DataSource,
    private val makeHttp: () -> DataSource?,
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val d = if (dataSpec.uri.scheme == ZIP_SCHEME) {
            makeZip()
        } else {
            makeHttp() ?: throw IOException("No streaming source set for ${dataSpec.uri}")
        }
        listeners.forEach { d.addTransferListener(it) }
        delegate = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (delegate ?: throw IOException("read before open")).read(buffer, offset, length)

    override fun getUri() = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }

    private companion object {
        const val ZIP_SCHEME = "zipaudio"
    }
}
