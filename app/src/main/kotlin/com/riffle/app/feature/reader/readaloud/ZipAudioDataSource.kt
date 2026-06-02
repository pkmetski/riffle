package com.riffle.app.feature.reader.readaloud

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Media3 [DataSource] that streams a single audio entry directly out of the Storyteller synced
 * EPUB zip (ADR 0023) — no extraction to a second on-disk copy. The entry path is carried in the
 * [DataSpec] URI path; the backing bundle file is fixed per book by [Factory].
 *
 * Seeks are honoured by reopening the entry and skipping, since zip entry streams are forward-only.
 */
internal class ZipAudioDataSource(private val bundle: File) : BaseDataSource(/* isNetwork = */ false) {

    private var zip: ZipFile? = null
    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val entryPath = requireNotNull(dataSpec.uri.path).trimStart('/')
        val z = ZipFile(bundle).also { zip = it }
        val entry = requireNotNull(z.getEntry(entryPath)) { "Missing audio entry: $entryPath" }
        val full = entry.size
        val input = z.getInputStream(entry).also { stream = it }
        var skipped = 0L
        while (skipped < dataSpec.position) {
            val s = input.skip(dataSpec.position - skipped)
            if (s <= 0) break
            skipped += s
        }
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            full - dataSpec.position
        } else {
            dataSpec.length
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(bytesRemaining, length.toLong()).toInt()
        val read = stream?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            stream?.close()
            zip?.close()
        } finally {
            stream = null
            zip = null
            uri = null
            transferEnded()
        }
    }

    class Factory(private val bundle: File) : DataSource.Factory {
        override fun createDataSource(): DataSource = ZipAudioDataSource(bundle)
    }

    companion object {
        /** Builds the playback URI for a zip-internal audio entry path. */
        fun uriFor(entryPath: String): Uri = Uri.parse("zipaudio:///$entryPath")
    }
}
