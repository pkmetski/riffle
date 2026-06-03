package com.riffle.core.data

import com.riffle.core.network.AudiobookBundleApi
import com.riffle.core.network.NetworkAudiobookBundleResult
import kotlinx.coroutines.Dispatchers
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
    // caller points this at the Downloads EPUB store location. Keyed by (serverId, bookId) since
    // bundle ids are only unique within a Server (ADR 0025) — it must land where the Downloads store
    // looks it up, i.e. under the serverId subdirectory.
    private val targetFileProvider: (serverId: String, bookId: String) -> File,
) {

    sealed interface Result {
        data class Success(val file: File) : Result
        data class NetworkError(val cause: Throwable) : Result
    }

    suspend fun download(
        serverId: String,
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val finalFile = targetFileProvider(serverId, bookId)
        finalFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (finalFile.exists()) return@withContext Result.Success(finalFile)

        val partFile = File(finalFile.parentFile, finalFile.name + ".part")
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L

        when (val r = api.openBundleStream(baseUrl, bookId, token, insecureAllowed, resumeFrom)) {
            is NetworkAudiobookBundleResult.NetworkError -> Result.NetworkError(r.cause)
            is NetworkAudiobookBundleResult.Success -> {
                // If we asked to resume but the server sent a full body (200, not 206), the partial
                // bytes are not a prefix of this stream — start over to avoid corrupting the file.
                val appending = r.isPartial && resumeFrom > 0L
                if (!appending) partFile.delete()
                var written = if (appending) resumeFrom else 0L
                val total = if (r.totalBytes > 0) r.totalBytes else -1L
                try {
                    r.body.use { body ->
                        java.io.FileOutputStream(partFile, appending).use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            body.byteStream().use { source ->
                                while (true) {
                                    val n = source.read(buffer)
                                    if (n == -1) break
                                    sink.write(buffer, 0, n)
                                    written += n
                                    onProgress(written, if (total > 0) total else written)
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    return@withContext Result.NetworkError(e) // .part preserved for resume
                }
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                Result.Success(finalFile)
            }
        }
    }
}
