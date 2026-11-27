package com.riffle.core.data

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Android [StorytellerSidecarFetcher] (ADR 0040).
 *
 * **Fast path** ([bundleApi]): streams the bundle and stops at the first audio entry — transfers only
 * the ~1 MB non-audio prefix. Works when Storyteller packs SMIL before audio (the common ordering).
 *
 * **Full-download fallback** ([fullBundleApi]): when the fast path finds no SMIL before the first
 * audio entry — either because SMIL comes after audio in this bundle's zip ordering, or because the
 * book genuinely has no SMIL yet — downloads the entire bundle to a temp file and extracts with
 * [java.util.zip.ZipFile] random access. This resolves the ambiguity: if [java.util.zip.ZipFile]
 * also finds no SMIL, the book is definitively unaligned ([StorytellerSidecarFetcher.FetchResult.NotAligned]);
 * if it does find SMIL, the bundle's ordering was non-standard and the sidecar is returned successfully.
 */
open class AndroidStorytellerSidecarFetcher(
    private val bundleApi: StorytellerBundleApi,
    private val fullBundleApi: StorytellerBundleApi = bundleApi,
    private val tempDir: () -> File = { File(System.getProperty("java.io.tmpdir") ?: "/tmp") },
    private val dispatchers: DispatcherProvider,
) : StorytellerSidecarFetcher {

    override suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): StorytellerSidecarFetcher.FetchResult = withContext(dispatchers.io) {
        // Fast path: stream the prefix, stop at the first audio entry (~1 MB transferred).
        val fastPath = bundleApi.downloadBundle(baseUrl, bookId, token, insecureAllowed)
        if (fastPath !is NetworkResult.Success) return@withContext StorytellerSidecarFetcher.FetchResult.NetworkError
        val streaming = fastPath.value.body.use { source ->
            runCatching { ReadaloudSidecarReader.readStreaming(source) }.getOrNull()
        }
        if (streaming != null) return@withContext StorytellerSidecarFetcher.FetchResult.Success(streaming)

        // Fast path returned null — SMIL may be after audio in this bundle's zip ordering.
        // Download the full bundle to a temp file and confirm with ZipFile random access.
        val tempFile = File.createTempFile("sidecar_$bookId", ".tmp", tempDir())
        try {
            val full = fullBundleApi.downloadBundle(baseUrl, bookId, token, insecureAllowed)
            if (full !is NetworkResult.Success) {
                StorytellerSidecarFetcher.FetchResult.NetworkError
            } else {
                full.value.body.use { source ->
                    tempFile.outputStream().use { out -> source.copyTo(out) }
                }
                val bytes = ReadaloudSidecarReader.readFromFile(tempFile)
                if (bytes != null) {
                    StorytellerSidecarFetcher.FetchResult.Success(bytes)
                } else {
                    StorytellerSidecarFetcher.FetchResult.NotAligned
                }
            }
        } catch (e: IOException) {
            StorytellerSidecarFetcher.FetchResult.NetworkError
        } finally {
            tempFile.delete()
        }
    }
}
