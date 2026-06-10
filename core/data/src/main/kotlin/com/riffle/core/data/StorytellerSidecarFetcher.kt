package com.riffle.core.data

import com.riffle.core.network.NetworkRangeResult
import com.riffle.core.network.NetworkStorytellerBundleSizeResult
import com.riffle.core.network.StorytellerBundleProbeApi
import com.riffle.core.network.StorytellerRangeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Fetches the Readaloud sidecar (SMIL + chapter text) by probing the `/synced` bundle's size and
 * range-extracting only the non-audio entries (ADR 0028) — ~1 MB instead of the whole bundle.
 * Credentials are resolved by the caller; this stays pure orchestration over the transport.
 */
class StorytellerSidecarFetcher(
    private val probe: StorytellerBundleProbeApi,
    private val range: StorytellerRangeApi,
) {
    /** The audio-free EPUB bytes, or null if the size/transport are unavailable. */
    suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val size = when (val r = probe.probeBundleSize(baseUrl, bookId, token, insecureAllowed)) {
            is NetworkStorytellerBundleSizeResult.Success -> r.sizeBytes
            is NetworkStorytellerBundleSizeResult.NetworkError -> return@withContext null
        }
        val reader = RangeReader { offset, length ->
            // ReadaloudSidecarReader is synchronous; we're already on Dispatchers.IO, so bridge the
            // suspend range call with runBlocking for each (small) range request.
            when (val r = runBlocking { range.readBundleRange(baseUrl, bookId, token, insecureAllowed, offset, length) }) {
                is NetworkRangeResult.Success -> r.bytes
                is NetworkRangeResult.NetworkError -> throw r.cause
            }
        }
        runCatching { ReadaloudSidecarReader.read(size, reader) }.getOrNull()
    }
}
