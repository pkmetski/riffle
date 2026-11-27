package com.riffle.core.data

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.withContext

/**
 * iOS [StorytellerSidecarFetcher] (ADR 0040). Kotlin/Native's zip reader needs the central
 * directory, which lives at the end of the archive, so iOS always downloads the whole `/synced`
 * bundle and then keeps the non-audio entries (via [IosReadaloudSidecarReader]). Same output and
 * "no SMIL means not aligned" contract as Android; only the streaming-prefix optimisation is absent.
 */
@OptIn(ExperimentalForeignApi::class)
class IosStorytellerSidecarFetcher(
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) : StorytellerSidecarFetcher {

    override suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): StorytellerSidecarFetcher.FetchResult = withContext(dispatchers.io) {
        val url = "${baseUrl.trimEnd('/')}/api/books/$bookId/synced"

        val bundle = runCatching {
            httpClient.withHttpChannelStream(
                url = url,
                headers = mapOf(HttpHeaders.Authorization to "Bearer $token"),
            ) { stream ->
                val chunks = mutableListOf<ByteArray>()
                var total = 0
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    chunks += buffer.copyOfRange(0, read)
                    total += read
                }
                val body = ByteArray(total)
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(body, offset)
                    offset += chunk.size
                }
                body
            }
        }.getOrNull() ?: return@withContext StorytellerSidecarFetcher.FetchResult.NetworkError
        if (bundle.isEmpty()) return@withContext StorytellerSidecarFetcher.FetchResult.NetworkError

        // Null means the bundle carries no SMIL — Storyteller has not aligned this book yet.
        val sidecar = IosReadaloudSidecarReader.read(bundle)
            ?: return@withContext StorytellerSidecarFetcher.FetchResult.NotAligned
        StorytellerSidecarFetcher.FetchResult.Success(sidecar)
    }
}
