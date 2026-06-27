package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Fetches the Readaloud sidecar (SMIL + chapter text, ADR 0028) from the Storyteller `/synced` bundle.
 *
 * **Fast path** ([bundleApi]): streams the bundle and stops at the first audio entry — transfers only
 * the ~1 MB non-audio prefix. Works when Storyteller packs SMIL before audio (the common ordering).
 *
 * **Full-download fallback** ([fullBundleApi]): when the fast path finds no SMIL before the first
 * audio entry — either because SMIL comes after audio in this bundle's zip ordering, or because the
 * book genuinely has no SMIL yet — downloads the entire bundle to a temp file and extracts with
 * [java.util.zip.ZipFile] random access. This resolves the ambiguity: if [ZipFile] also finds no
 * SMIL, the book is definitively unaligned ([FetchResult.NotAligned]); if it does find SMIL, the
 * bundle's ordering was non-standard and the sidecar is returned successfully.
 */
open class StorytellerSidecarFetcher(
    private val bundleApi: StorytellerBundleApi,
    private val fullBundleApi: StorytellerBundleApi = bundleApi,
    private val tempDir: () -> File = { File(System.getProperty("java.io.tmpdir") ?: "/tmp") },
) {
    sealed interface FetchResult {
        /** Sidecar bytes ready to write to disk. */
        data class Success(val bytes: ByteArray) : FetchResult
        /** Transient transport failure — caller should retry. */
        data object NetworkError : FetchResult
        /** Bundle has no SMIL (Storyteller not yet aligned) — no point retrying until alignment. */
        data object NotAligned : FetchResult
    }

    open suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): FetchResult = withContext(Dispatchers.IO) {
        // Fast path: stream the prefix, stop at the first audio entry (~1 MB transferred).
        when (val r = bundleApi.downloadBundle(baseUrl, bookId, token, insecureAllowed)) {
            is NetworkStorytellerBundleResult.NetworkError -> return@withContext FetchResult.NetworkError
            is NetworkStorytellerBundleResult.Success -> {
                val streaming = r.body.use { body ->
                    runCatching { ReadaloudSidecarReader.readStreaming(body.byteStream()) }.getOrNull()
                }
                if (streaming != null) return@withContext FetchResult.Success(streaming)
            }
        }

        // Fast path returned null — SMIL may be after audio in this bundle's zip ordering.
        // Download the full bundle to a temp file and confirm with ZipFile random access.
        val tempFile = File.createTempFile("sidecar_$bookId", ".tmp", tempDir())
        try {
            when (val r = fullBundleApi.downloadBundle(baseUrl, bookId, token, insecureAllowed)) {
                is NetworkStorytellerBundleResult.NetworkError -> FetchResult.NetworkError
                is NetworkStorytellerBundleResult.Success -> {
                    r.body.use { body ->
                        tempFile.outputStream().use { out -> body.byteStream().copyTo(out) }
                    }
                    val bytes = ReadaloudSidecarReader.readFromFile(tempFile)
                    if (bytes != null) FetchResult.Success(bytes) else FetchResult.NotAligned
                }
            }
        } catch (e: IOException) {
            FetchResult.NetworkError
        } finally {
            tempFile.delete()
        }
    }
}
