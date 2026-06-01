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
            } catch (e: Throwable) {
                Result.NetworkError(e)
            }
        }
        is NetworkStorytellerBundleResult.NetworkError -> Result.NetworkError(r.cause)
    }
}
