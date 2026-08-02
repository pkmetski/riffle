package com.riffle.core.data

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.AudiobookBundleApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.errorAsThrowable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads the Storyteller synced bundle (the Readaloud audio source — ADR 0023) into the
 * permanent Downloads area with **resume** and **progress reporting**.
 *
 * Bytes accumulate in a `.part` sidecar so an interrupted transfer can pick up where it left off
 * via a `Range` request; on completion the sidecar is atomically renamed to the final file. A
 * network failure leaves the `.part` in place for the next attempt.
 */
class AudiobookBundleDownloader(
    private val api: AudiobookBundleApi,
    // Resolves the final on-disk destination for a book's bundle. The reader and the player share
    // this single file (the synced bundle is both the EPUB and the audio source — ADR 0023), so the
    // caller points this at the Downloads EPUB store location. Keyed by (sourceId, bookId) since
    // bundle ids are only unique within a Source (ADR 0025) — it must land where the Downloads store
    // looks it up, i.e. under the sourceId subdirectory.
    private val targetFileProvider: (sourceId: String, bookId: String) -> File,
    private val dispatchers: DispatcherProvider,
) {

    sealed interface Result {
        data class Success(val file: File) : Result
        data class NetworkError(val cause: Throwable) : Result
    }

    suspend fun download(
        sourceId: String,
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result = withContext(dispatchers.io) {
        val finalFile = targetFileProvider(sourceId, bookId)
        finalFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (finalFile.exists()) return@withContext Result.Success(finalFile)

        val partFile = File(finalFile.parentFile, finalFile.name + ".part")
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L

        val response = api.withBundleStream(
            baseUrl, bookId, token, insecureAllowed, resumeFrom,
        ) { stream ->
            // If we asked to resume but the server sent a full body (200, not 206), the partial
            // bytes are not a prefix of this stream — start over to avoid corrupting the file.
            val appending = stream.isPartial && resumeFrom > 0L
            if (!appending) partFile.delete()
            var written = if (appending) resumeFrom else 0L
            val total = if (stream.totalBytes > 0) stream.totalBytes else -1L
            val progress = CumulativeDownloadProgress(total, onProgress, initialDownloaded = written)
            stream.body.use { source ->
                java.io.FileOutputStream(partFile, appending).use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = source.read(buffer)
                        if (n == -1) break
                        sink.write(buffer, 0, n)
                        written += n
                        progress.record(n.toLong())
                    }
                }
            }
            // A stream can end short without throwing. Preserve the .part for a later resume.
            if (total > 0 && written < total) {
                throw java.io.IOException("Truncated bundle for $bookId: $written/$total bytes")
            }
        }
        if (response !is NetworkResult.Success) {
            return@withContext Result.NetworkError(response.errorAsThrowable())
        }
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        Result.Success(finalFile)
    }
}
