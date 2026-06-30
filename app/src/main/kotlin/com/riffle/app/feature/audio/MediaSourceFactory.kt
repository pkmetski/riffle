package com.riffle.app.feature.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.riffle.app.feature.reader.readaloud.SharedBundle
import com.riffle.app.feature.reader.readaloud.ZipAudioDataSource
import com.riffle.core.logging.Logger
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A scheme-keyed [DataSource] factory. The [MediaSourceRegistry] composes a list of these and
 * dispatches each [DataSpec] open to the factory whose [handles] matches the URI scheme. Adding a
 * new audio backend (e.g. an S3 source, a local cache layer) is a new [MediaSourceFactory] entry —
 * no edits in `AudioPlayerService`.
 */
@OptIn(UnstableApi::class)
interface MediaSourceFactory {
    fun handles(scheme: String?): Boolean
    fun createDataSource(): DataSource
}

/** Streamed ABS audiobook tracks (ADR 0029). */
@OptIn(UnstableApi::class)
@Singleton
class HttpAudioSourceFactory @Inject constructor() : MediaSourceFactory {
    override fun handles(scheme: String?): Boolean = scheme == "http" || scheme == "https"
    override fun createDataSource(): DataSource = DefaultHttpDataSource.Factory().createDataSource()
}

/** Downloaded audiobook tracks (ADR 0029, file:// after offline download). */
@OptIn(UnstableApi::class)
@Singleton
class FileAudioSourceFactory @Inject constructor() : MediaSourceFactory {
    override fun handles(scheme: String?): Boolean = scheme == "file"
    override fun createDataSource(): DataSource = FileDataSource()
}

/**
 * Readaloud bundle audio served out of the synced EPUB zip (ADR 0023). The active bundle file
 * pointer lives in [SharedBundle] — set by `ReadaloudController` / `AudiobookController` before
 * the controller queues media items.
 */
@OptIn(UnstableApi::class)
@Singleton
class BundleAudioSourceFactory @Inject constructor(private val logger: Logger) : MediaSourceFactory {
    override fun handles(scheme: String?): Boolean = scheme == ZIP_SCHEME
    override fun createDataSource(): DataSource {
        val bundle = SharedBundle.current ?: throw IOException("No Readaloud bundle set")
        return ZipAudioDataSource.Factory(bundle, logger).createDataSource()
    }

    companion object {
        const val ZIP_SCHEME = "zipaudio"
    }
}

/**
 * Composes a list of [MediaSourceFactory] into a single Media3 [DataSource.Factory] that dispatches
 * by URI scheme. Replaces the two duplicated per-scheme switches previously hand-rolled in
 * `AudioPlayerService.SchemeResolvingDataSource` and `SharedBundle.ReadaloudDataSource`.
 */
@OptIn(UnstableApi::class)
class MediaSourceRegistry @Inject constructor(private val factories: List<@JvmSuppressWildcards MediaSourceFactory>) {
    fun asDataSourceFactory(): DataSource.Factory = DataSource.Factory { ResolvingDataSource(factories) }

    private class ResolvingDataSource(
        private val factories: List<MediaSourceFactory>,
    ) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            val factory = factories.firstOrNull { it.handles(scheme) }
                ?: throw IOException("No MediaSourceFactory for scheme=$scheme uri=${dataSpec.uri}")
            val source = factory.createDataSource()
            listeners.forEach { source.addTransferListener(it) }
            delegate = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            (delegate ?: throw IOException("read before open")).read(buffer, offset, length)

        override fun getUri() = delegate?.uri

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }
}
