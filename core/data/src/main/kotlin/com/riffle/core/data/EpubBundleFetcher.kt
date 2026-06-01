package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import java.io.File

class EpubBundleFetcher(
    private val api: StorytellerBundleApi,
    private val workingDirProvider: () -> File,
) {

    sealed interface Result {
        data class Success(val epubFile: File) : Result
        data class NetworkError(val cause: Throwable) : Result
    }

    suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): Result = when (val r = api.downloadBundle(baseUrl, bookId, token, insecureAllowed)) {
        is NetworkStorytellerBundleResult.Success -> r.body.use { body ->
            try {
                val epub = EpubBundleExtractor.extractEpub(body.byteStream(), workingDirProvider())
                Result.Success(epub)
            // Extraction failures (bad zip, missing .epub entry, disk-full) are folded into
            // NetworkError because EpubOpenResult / EpubDownloadResult expose only one
            // failure variant anyway; the user-facing distinction would be lost downstream.
            } catch (e: Throwable) {
                Result.NetworkError(e)
            }
        }
        is NetworkStorytellerBundleResult.NetworkError -> Result.NetworkError(r.cause)
    }
}
