package com.riffle.core.data

import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.withHttpByteStream
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareHead
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Downloads all tracks in an [AudiobookSession] to a directory. Used by both
 * [AudiobookDownloadRepositoryImpl] (explicit user download) and [AudiobookCacheRepositoryImpl]
 * (background auto-cache). Tracks are fetched serially: some ABS backends reject concurrent pulls
 * for the same book, and a single shared helper must not break either explicit download or
 * play-time auto-cache. Returns the [AudiobookDownloadManifest.ManifestTrack] list ordered by track
 * index; throws on any per-track failure (the caller must clean up the dir).
 */
class AudiobookTrackDownloader constructor(
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) {
    internal suspend fun download(
        session: AudiobookSession,
        dir: File,
        progress: CumulativeDownloadProgress,
    ): List<AudiobookDownloadManifest.ManifestTrack> = withContext(dispatchers.io) {
        // For multi-track sources (e.g. Radio.es podcasts) whose catalog doesn't provide a
        // fingerprint size, pre-scan all track URLs in parallel via HEAD to establish the cumulative
        // total before any bytes flow. Skipped when a fingerprint-based total is already known so
        // sources like ABS don't pay extra HEAD round-trips. If any track lacks a Content-Length
        // the pre-scan is skipped and progress stays indeterminate for that download.
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
                val out = File(dir, fileName)
                httpClient.withHttpByteStream(
                    url = url,
                    httpFailure = { failure -> IOException("HTTP ${failure.code} for track $i") },
                ) { response ->
                    if (session.trackUrls.size == 1) {
                        progress.establishTotal(response.contentLength)
                    }
                    response.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                progress.record(read.toLong())
                            }
                        }
                    }
                }
                val span = session.tracks.getOrNull(i)
                add(
                    AudiobookDownloadManifest.ManifestTrack(
                        index = span?.index ?: i,
                        file = fileName,
                        startOffsetSec = span?.startOffsetSec ?: 0.0,
                        durationSec = span?.durationSec ?: 0.0,
                    ),
                )
            }
        }
    }

    private suspend fun headContentLength(url: String): Long? = runCatching {
        httpClient.prepareHead(url).execute { response ->
            if (response.status.isSuccess()) response.contentLength() else null
        }
    }.getOrNull()
}
