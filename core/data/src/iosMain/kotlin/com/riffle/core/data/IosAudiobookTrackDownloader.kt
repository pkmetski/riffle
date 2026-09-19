package com.riffle.core.data

import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * iOS counterpart to [AudiobookTrackDownloader]: downloads every track of an [AudiobookSession]
 * into [dirPath], returning the manifest track list ordered by index and throwing on any per-track
 * failure (the caller cleans up the directory).
 *
 * Same contract as Android's — serial fetches (some ABS backends reject concurrent pulls for one
 * book) and an optional inter-track delay for background caching. It streams through
 * [withHttpChannelStream] (core:net's KMP streamer) rather than Android's JVM-only
 * `withHttpByteStream`, and writes with NSFileManager instead of `java.io.File`.
 */
class IosAudiobookTrackDownloader(
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) {

    internal suspend fun download(
        session: AudiobookSession,
        dirPath: String,
        progress: IosCumulativeDownloadProgress,
        interTrackDelayMs: Long = 0L,
    ): List<AudiobookDownloadManifest.ManifestTrack> = withContext(dispatchers.io) {
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
        progress: IosCumulativeDownloadProgress,
    ) {
        httpClient.withHttpChannelStream(url = url) { stream ->
            if (singleTrack) progress.establishTotal(stream.contentLength)
            // NSFileManager has no append-stream primitive as convenient as an OutputStream, and
            // audiobook tracks are chapter-sized, so each track is assembled in memory and written
            // once. Progress is still reported per chunk so the UI advances during the transfer.
            val chunks = mutableListOf<ByteArray>()
            var totalRead = 0
            val buffer = ByteArray(64 * 1024)
            while (!stream.channel.isClosedForRead) {
                val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) continue
                chunks += buffer.copyOfRange(0, read)
                totalRead += read
                progress.record(read.toLong())
            }
            val body = ByteArray(totalRead)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(body, offset)
                offset += chunk.size
            }
            if (!IosAudiobookFiles.writeBytes(outPath, body)) {
                error("Could not write track to $outPath")
            }
        }
    }
}

/**
 * iOS counterpart to `CumulativeDownloadProgress`. Kotlin/Native has no `@Synchronized`, but tracks
 * are downloaded serially here (unlike Android's optional parallel HEAD pre-scan), so the counter
 * is only ever touched from one coroutine at a time.
 */
class IosCumulativeDownloadProgress(
    total: Long,
    private val onProgress: (downloaded: Long, total: Long) -> Unit,
) {
    private var downloaded = 0L
    private var total = total.takeIf { it > 0L } ?: 0L

    fun hasKnownTotal(): Boolean = total > 0L

    fun establishTotal(candidate: Long?) {
        if (total == 0L && candidate != null && candidate > 0L) total = candidate
    }

    fun record(delta: Long) {
        if (delta <= 0L) return
        downloaded += delta
        onProgress(downloaded, total)
    }
}
