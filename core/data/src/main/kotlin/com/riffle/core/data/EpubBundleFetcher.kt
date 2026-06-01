package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EpubBundleFetcher(
    private val api: StorytellerBundleApi,
    private val workingDirProvider: () -> File,
) {

    sealed interface Result {
        data class Success(val epubFile: File) : Result
        data class NetworkError(val cause: Throwable) : Result
    }

    // Streams /api/books/{id}/synced into a temp file in workingDirProvider().
    //
    // Despite the endpoint's "synced" name, the response IS the EPUB 3 file (with
    // Media Overlay .smil entries inside, per EPUB 3 spec) — not an outer archive
    // that needs unpacking. We stream the body straight to disk.
    //
    // I/O runs on Dispatchers.IO so the multi-hundred-MB stream + write doesn't
    // block the caller's thread (typically viewModelScope on Main).
    suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): Result = withContext(Dispatchers.IO) {
        when (val r = api.downloadBundle(baseUrl, bookId, token, insecureAllowed)) {
            is NetworkStorytellerBundleResult.Success -> {
                val workingDir = workingDirProvider()
                if (!workingDir.exists()) workingDir.mkdirs()
                val out = File.createTempFile("storyteller-", ".epub", workingDir)
                try {
                    r.body.use { body ->
                        out.outputStream().use { sink -> body.byteStream().copyTo(sink) }
                    }
                    Result.Success(out)
                } catch (e: Throwable) {
                    out.delete()
                    Result.NetworkError(e)
                }
            }
            is NetworkStorytellerBundleResult.NetworkError -> Result.NetworkError(r.cause)
        }
    }
}
