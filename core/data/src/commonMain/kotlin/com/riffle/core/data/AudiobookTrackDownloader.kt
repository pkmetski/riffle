package com.riffle.core.data

import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.HttpChannelException
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareHead
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Downloads all tracks in an [AudiobookSession] to a directory. Used by both
 * [AudiobookDownloadRepositoryImpl] (explicit user download) and [AudiobookCacheRepositoryImpl]
 * (background auto-cache). Tracks are fetched serially: some ABS backends reject concurrent pulls
 * for the same book, and a single shared helper must not break either explicit download or
 * play-time auto-cache. Returns the [AudiobookDownloadManifest.ManifestTrack] list ordered by
 * track index; throws on any per-track failure (the caller must clean up the dir).
 */
internal class AudiobookTrackDownloader(
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {

    internal suspend fun download(
        session: AudiobookSession,
        dirPath: String,
        progress: CumulativeDownloadProgress,
        interTrackDelayMs: Long = 0L,
    ): List<AudiobookDownloadManifest.ManifestTrack> = withContext(dispatchers.io) {
        if (session.trackUrls.size > 1 && !progress.hasKnownTotal()) {
            val headLengths = coroutineScope {
                session.trackUrls.map { url -> async { headContentLength(url) } }.awaitAll()
            }
            if (headLengths.all { it != null && it > 0L }) {
                progress.establishTotal(headLengths.sumOf { it!! })
            }
        }
        buildList(session.trackUrls.size) {
            session.trackUrls.forEachIndexed { i, url ->
                val fileName = "track-$i"
                downloadOne(url, "$dirPath/$fileName", singleTrack = session.trackUrls.size == 1, progress = progress)
                val span = session.tracks.getOrNull(i)
                add(
                    AudiobookDownloadManifest.ManifestTrack(
                        index = span?.index ?: i,
                        file = fileName,
                        startOffsetSec = span?.startOffsetSec ?: 0.0,
                        durationSec = span?.durationSec ?: 0.0,
                    ),
                )
                if (interTrackDelayMs > 0L && i < session.trackUrls.lastIndex) {
                    delay(interTrackDelayMs)
                }
            }
        }
    }

    private suspend fun downloadOne(
        url: String,
        outPath: String,
        singleTrack: Boolean,
        progress: CumulativeDownloadProgress,
    ) {
        var attempt = 0
        while (true) {
            try {
                httpClient.withHttpChannelStream(url = url) { stream ->
                    if (singleTrack) progress.establishTotal(stream.contentLength)
                    val outFile = outPath.toPath()
                    fileSystem.createDirectories(outFile.parent!!)
                    fileSystem.write(outFile) {
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (!stream.channel.isClosedForRead) {
                            val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) continue
                            write(buffer, 0, read)
                            progress.record(read.toLong())
                        }
                    }
                }
                return
            } catch (e: Exception) {
                if (attempt >= MAX_RETRY_ATTEMPTS || e is HttpChannelException) throw e
                delay(minOf(RETRY_BASE_DELAY_MS shl attempt, RETRY_MAX_DELAY_MS))
                attempt++
            }
        }
    }

    private suspend fun headContentLength(url: String): Long? = runCatching {
        httpClient.prepareHead(url).execute { response ->
            if (response.status.isSuccess()) response.contentLength() else null
        }
    }.getOrNull()

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val MAX_RETRY_ATTEMPTS = 5
        const val RETRY_BASE_DELAY_MS = 2_000L
        const val RETRY_MAX_DELAY_MS = 30_000L
    }
}
