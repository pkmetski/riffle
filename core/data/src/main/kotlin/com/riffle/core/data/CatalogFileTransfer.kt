package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.domain.LocalStore
import java.io.File

/**
 * The single file-transfer implementation for every book format. Format repositories choose the
 * [BookFormat] and map failures to their public result type; response scoping, storage, and progress
 * invariants remain identical for current and future formats.
 */
internal object CatalogFileTransfer {
    suspend fun acquire(
        catalog: Catalog,
        sourceId: String,
        itemId: String,
        format: BookFormat,
        handleHint: String?,
        target: LocalStore,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): File = catalog.withFileStream(itemId, format, handleHint) { stream ->
        val input = if (onProgress == null) {
            stream.byteStream()
        } else {
            CumulativeDownloadProgress(stream.contentLength, onProgress).track(stream.byteStream())
        }
        target.save(sourceId, itemId, input)
    }

    suspend fun promote(
        sourceId: String,
        itemId: String,
        cached: File,
        cache: LocalStore,
        downloads: LocalStore,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val progress = CumulativeDownloadProgress(cached.length(), onProgress)
        val downloaded = cached.inputStream().use { input ->
            downloads.save(sourceId, itemId, progress.track(input))
        }
        cache.delete(sourceId, itemId)
        return downloaded
    }

    suspend fun acquireTemporary(
        catalog: Catalog,
        itemId: String,
        format: BookFormat,
        handleHint: String?,
        suffix: String,
    ): File {
        val temp = File.createTempFile("riffle-metadata-", suffix)
        return try {
            catalog.withFileStream(itemId, format, handleHint) { stream ->
                stream.byteStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }
}
