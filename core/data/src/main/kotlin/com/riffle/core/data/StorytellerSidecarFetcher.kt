package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches the Readaloud sidecar (SMIL + chapter text, ADR 0028) by streaming the `/synced` bundle once
 * and keeping only the non-audio entries. Storyteller serves `/synced` by generating the whole aligned
 * bundle before the first byte, and it doesn't serve cheap byte-ranges (each range re-triggers that
 * generation) — so range-extraction would mean many slow round-trips. A single streaming GET that stops
 * at the first audio entry costs **one** generation and transfers only the ~1 MB non-audio prefix (audio
 * is packed last), discarding the hundreds of MB of audio without ever pulling them over the wire.
 *
 * Credentials are resolved by the caller; this stays pure orchestration over the transport.
 */
class StorytellerSidecarFetcher(
    private val bundleApi: StorytellerBundleApi,
) {
    /** The audio-free EPUB bytes, or null if the transport is unavailable. */
    suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): ByteArray? = withContext(Dispatchers.IO) {
        when (val r = bundleApi.downloadBundle(baseUrl, bookId, token, insecureAllowed)) {
            is NetworkStorytellerBundleResult.Success ->
                r.body.use { body ->
                    runCatching { ReadaloudSidecarReader.readStreaming(body.byteStream()) }.getOrNull()
                }
            is NetworkStorytellerBundleResult.NetworkError -> null
        }
    }
}
