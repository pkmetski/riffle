package com.riffle.core.data

import com.riffle.core.data.di.qualifiers.StreamingHttpClient
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.withHttpByteStream
import io.ktor.client.HttpClient
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Downloads all tracks in an [AudiobookSession] to a directory. Used by both
 * [AudiobookDownloadRepositoryImpl] (explicit user download) and [AudiobookCacheRepositoryImpl]
 * (background auto-cache). Tracks are fetched serially: some ABS backends reject concurrent pulls
 * for the same book, and a single shared helper must not break either explicit download or
 * play-time auto-cache. Returns the [AudiobookDownloadManifest.ManifestTrack] list ordered by track
 * index; throws on any per-track failure (the caller must clean up the dir).
 */
class AudiobookTrackDownloader @Inject constructor(
    @StreamingHttpClient private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) {
    internal suspend fun download(
        session: AudiobookSession,
        dir: File,
        progress: CumulativeDownloadProgress,
    ): List<AudiobookDownloadManifest.ManifestTrack> = withContext(dispatchers.io) {
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
}
