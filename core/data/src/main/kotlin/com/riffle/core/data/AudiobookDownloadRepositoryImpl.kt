package com.riffle.core.data

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.data.di.qualifiers.StreamingHttpClient
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.withHttpByteStream
import io.ktor.client.HttpClient
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** On-disk manifest written after a successful download so the book plays offline (ADR 0029). */
@Serializable
internal data class AudiobookDownloadManifest(
    val durationSec: Double,
    val tracks: List<ManifestTrack>,
    val chapters: List<ManifestChapter>,
) {
    @Serializable
    data class ManifestTrack(val index: Int, val file: String, val startOffsetSec: Double, val durationSec: Double)

    @Serializable
    data class ManifestChapter(val index: Int, val startSec: Double, val endSec: Double, val title: String)
}

/**
 * Downloads an [com.riffle.core.domain.Audiobook]'s ABS tracks to a permanent per-item directory and
 * reconstructs a playable [AudiobookSession] from them offline (ADR 0029). The directory holds one
 * file per track plus `manifest.json`; the manifest is written **last**, so its presence is the
 * atomic "fully downloaded" marker — a partial download (some tracks, no manifest) reads as
 * not-downloaded and is simply re-fetched.
 */
class AudiobookDownloadRepositoryImpl @Inject constructor(
    private val audiobookRepository: AudiobookRepository,
    @StreamingHttpClient private val httpClient: HttpClient,
    @com.riffle.core.data.di.AudiobookDownloadsDir private val downloadsDir: File,
    private val dispatchers: DispatcherProvider,
) : AudiobookDownloadRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun itemDir(sourceId: String, itemId: String) = File(downloadsDir, "$sourceId/$itemId")
    private fun manifestFile(sourceId: String, itemId: String) = File(itemDir(sourceId, itemId), "manifest.json")

    override fun isDownloaded(sourceId: String, itemId: String): Boolean =
        manifestFile(sourceId, itemId).exists()

    override fun localSession(sourceId: String, itemId: String): AudiobookSession? {
        val mf = manifestFile(sourceId, itemId)
        if (!mf.exists()) return null
        val manifest = runCatching { json.decodeFromString<AudiobookDownloadManifest>(mf.readText()) }.getOrNull()
            ?: return null
        val dir = itemDir(sourceId, itemId)
        return AudiobookSession(
            trackUrls = manifest.tracks.map { File(dir, it.file).toURI().toString() }, // file:// URLs
            tracks = manifest.tracks.map { AudiobookTrackSpan(it.index, it.startOffsetSec, it.durationSec) },
            timeline = AudiobookTimeline(
                durationSec = manifest.durationSec,
                chapters = manifest.chapters.map { AudiobookChapter(it.index, it.startSec, it.endSec, it.title) },
            ),
            serverCurrentTimeSec = 0.0, // resume position comes from progress sync, not the manifest
        )
    }

    override suspend fun download(
        sourceId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult = withContext(dispatchers.io) {
        if (isDownloaded(sourceId, itemId)) return@withContext AudiobookDownloadResult.Success
        val session = audiobookRepository.openSession(sourceId, itemId)
            ?: return@withContext AudiobookDownloadResult.NetworkError(IOException("Could not open play session"))
        val wholeAudiobookBytes = audiobookRepository.downloadSizeBytes(sourceId, itemId)
            ?.takeIf { it > 0L }
        val progress = CumulativeDownloadProgress(wholeAudiobookBytes ?: 0L, onProgress)

        val dir = itemDir(sourceId, itemId).apply { mkdirs() }
        val manifestTracks = ArrayList<AudiobookDownloadManifest.ManifestTrack>()
        try {
            session.trackUrls.forEachIndexed { i, url ->
                val fileName = "track-$i"
                val out = File(dir, fileName)
                httpClient.withHttpByteStream(
                    url = url,
                    httpFailure = { failure -> IOException("HTTP ${failure.code} for track $i") },
                ) { response ->
                    // A per-track Content-Length is only the whole download size for a one-track
                    // audiobook. For multi-track books, using each newly discovered length as the
                    // denominator makes progress hit 100% at every track boundary and then go
                    // backwards. Prefer the Source fingerprint's aggregate size; stay indeterminate
                    // when a multi-track Source cannot provide one.
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
                manifestTracks += AudiobookDownloadManifest.ManifestTrack(
                    index = span?.index ?: i,
                    file = fileName,
                    startOffsetSec = span?.startOffsetSec ?: 0.0,
                    durationSec = span?.durationSec ?: 0.0,
                )
            }
            val manifest = AudiobookDownloadManifest(
                durationSec = session.timeline.durationSec,
                tracks = manifestTracks,
                chapters = session.timeline.chapters.map {
                    AudiobookDownloadManifest.ManifestChapter(it.index, it.startSec, it.endSec, it.title)
                },
            )
            // Written last → atomic completion marker.
            manifestFile(sourceId, itemId).writeText(json.encodeToString(manifest))
            AudiobookDownloadResult.Success
        } catch (e: IOException) {
            dir.deleteRecursively() // leave no partial download behind
            AudiobookDownloadResult.NetworkError(e)
        }
    }

    override suspend fun remove(sourceId: String, itemId: String): Long = withContext(dispatchers.io) {
        val dir = itemDir(sourceId, itemId)
        val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        dir.deleteRecursively()
        freed
    }
}
