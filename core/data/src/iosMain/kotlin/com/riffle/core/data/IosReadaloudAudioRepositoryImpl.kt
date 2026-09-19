package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudBundleReader
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withContext

/**
 * iOS [ReadaloudAudioRepository] — the counterpart to `ReadaloudAudioRepositoryImpl`.
 *
 * A Storyteller "synced" bundle is simultaneously the EPUB and the audio source (ADR 0027), so
 * like Android it is stored in the EPUB download/cache namespaces (`<ns>/<sourceId>/<bookId>.epub`)
 * and a downloaded bundle doubles as the reader's offline EPUB.
 *
 * [readTrack] parses the bundle's Media Overlay timeline through [IosMediaOverlayReader] and
 * memoises it per (sourceId, itemId, bundle size) — Android keys on `lastModified()` for the same
 * reason: re-downloading must re-parse, but repeated opens of the same bundle must not.
 */
class IosReadaloudAudioRepositoryImpl(
    private val fileStore: FileStore,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) : ReadaloudAudioRepository, ReadaloudBundleReader {

    private val trackCache = mutableMapOf<Triple<String, String, Long>, ReadaloudTrack>()

    override fun isAudioAvailable(sourceId: String, itemId: String): Boolean =
        bundlePath(sourceId, itemId) != null

    /** Downloaded bundle wins over the cached copy, matching Android's downloads-then-cache order. */
    override fun bundlePath(sourceId: String, itemId: String): String? {
        val download = downloadPath(sourceId, itemId)
        if (IosAudiobookFiles.exists(download)) return download
        val cache = cachePath(sourceId, itemId)
        return if (IosAudiobookFiles.exists(cache)) cache else null
    }

    override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack? = withContext(dispatchers.io) {
        val path = bundlePath(sourceId, itemId) ?: return@withContext null
        val bytes = IosAudiobookFiles.readBytes(path) ?: return@withContext null
        val key = Triple(sourceId, itemId, bytes.size.toLong())
        trackCache[key]?.let { return@withContext it }
        val track = runCatching { IosMediaOverlayReader.readTrack(bytes) }.getOrNull()
            ?.takeIf { it.clips.isNotEmpty() }
            ?: return@withContext null
        trackCache[key] = track
        track
    }

    /** Raw audio bytes for a clip's zip-internal path, for the player to feed to AVAudioPlayer. */
    suspend fun readAudio(sourceId: String, itemId: String, audioPath: String): ByteArray? =
        withContext(dispatchers.io) {
            val path = bundlePath(sourceId, itemId) ?: return@withContext null
            val bytes = IosAudiobookFiles.readBytes(path) ?: return@withContext null
            IosMediaOverlayReader.readAudio(bytes, audioPath)
        }

    override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? {
        val source = sourceRepository.getById(sourceId) ?: return null
        val token = tokenStorage.getToken(source.id) ?: return null
        return runCatching {
            httpClient.head(bundleUrl(source.url.value, itemId)) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.let { if (it.status.isSuccess()) it.contentLength() else null }
        }.getOrNull()
    }

    override suspend fun downloadAudio(
        sourceId: String,
        bookId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult = withContext(dispatchers.io) {
        val destination = downloadPath(sourceId, bookId)
        if (IosAudiobookFiles.exists(destination)) return@withContext AudioDownloadResult.Success

        val source = sourceRepository.getById(sourceId)
            ?: return@withContext AudioDownloadResult.NetworkError(IllegalStateException("No source $sourceId"))
        val token = tokenStorage.getToken(sourceId)
            ?: return@withContext AudioDownloadResult.NetworkError(IllegalStateException("No token for $sourceId"))

        runCatching {
            httpClient.withHttpChannelStream(
                url = bundleUrl(source.url.value, bookId),
                headers = mapOf(HttpHeaders.Authorization to "Bearer $token"),
            ) { stream ->
                val chunks = mutableListOf<ByteArray>()
                var downloaded = 0L
                val buffer = ByteArray(64 * 1024)
                while (!stream.channel.isClosedForRead) {
                    val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    chunks += buffer.copyOfRange(0, read)
                    downloaded += read
                    onProgress(downloaded, stream.contentLength)
                }
                val body = ByteArray(downloaded.toInt())
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(body, offset)
                    offset += chunk.size
                }
                if (body.isEmpty()) {
                    AudioDownloadResult.NoBundle
                } else if (IosAudiobookFiles.writeBytes(destination, body)) {
                    AudioDownloadResult.Success
                } else {
                    AudioDownloadResult.NetworkError(IllegalStateException("Could not write bundle to $destination"))
                }
            }
        }.getOrElse { AudioDownloadResult.NetworkError(it) }
    }

    override suspend fun removeAudio(sourceId: String, itemId: String): Long = withContext(dispatchers.io) {
        var freed = 0L
        for (path in listOf(downloadPath(sourceId, itemId), cachePath(sourceId, itemId))) {
            if (IosAudiobookFiles.exists(path)) {
                freed += IosAudiobookFiles.readBytes(path)?.size?.toLong() ?: 0L
                IosAudiobookFiles.deleteRecursively(path)
            }
        }
        freed
    }

    private fun bundleUrl(baseUrl: String, bookId: String) =
        "${baseUrl.trimEnd('/')}/api/books/$bookId/synced"

    private fun downloadPath(sourceId: String, itemId: String) =
        fileStore.resolve(NS_EPUB_DOWNLOADS, "$sourceId/$itemId.epub")

    private fun cachePath(sourceId: String, itemId: String) =
        fileStore.resolve(NS_EPUB_CACHE, "$sourceId/$itemId.epub")
}
