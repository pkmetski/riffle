package com.riffle.core.data

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.models.AudiobookTrackSpan
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** On-disk manifest written after a successful download so the book plays offline (ADR 0035). */
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
 * reconstructs a playable [AudiobookSession] from them offline (ADR 0035). The directory holds one
 * file per track plus `manifest.json`; the manifest is written **last**, so its presence is the
 * atomic "fully downloaded" marker — a partial download (some tracks, no manifest) reads as
 * not-downloaded and is simply re-fetched. Track transfer is delegated to the shared
 * [AudiobookTrackDownloader].
 */
class AudiobookDownloadRepositoryImpl @Inject constructor(
    private val audiobookRepository: AudiobookRepository,
    private val trackDownloader: AudiobookTrackDownloader,
    @com.riffle.core.data.di.AudiobookCacheDir private val cacheDir: File,
    @com.riffle.core.data.di.AudiobookDownloadsDir private val downloadsDir: File,
    private val dispatchers: DispatcherProvider,
    private val localAvailabilityEvents: LocalAvailabilityEvents,
) : AudiobookDownloadRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun itemDir(sourceId: String, itemId: String) = File(downloadsDir, "$sourceId/$itemId")
    private fun cacheItemDir(sourceId: String, itemId: String) = File(cacheDir, "$sourceId/$itemId")
    private fun manifestFile(sourceId: String, itemId: String) = File(itemDir(sourceId, itemId), "manifest.json")
    private fun cacheManifestFile(sourceId: String, itemId: String) = File(cacheItemDir(sourceId, itemId), "manifest.json")

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
        if (cacheManifestFile(sourceId, itemId).exists()) {
            return@withContext promoteCacheToDownload(sourceId, itemId, onProgress)
        }
        val session = audiobookRepository.openSession(sourceId, itemId)
            ?: return@withContext AudiobookDownloadResult.NetworkError(IOException("Could not open play session"))
        val wholeAudiobookBytes = audiobookRepository.downloadSizeBytes(sourceId, itemId)
            ?.takeIf { it > 0L }
        val progress = CumulativeDownloadProgress(wholeAudiobookBytes ?: 0L, onProgress)

        val dir = itemDir(sourceId, itemId).apply { mkdirs() }
        try {
            val manifestTracks = trackDownloader.download(session, dir, progress)
            val manifest = AudiobookDownloadManifest(
                durationSec = session.timeline.durationSec,
                tracks = manifestTracks.sortedBy { it.index },
                chapters = session.timeline.chapters.map {
                    AudiobookDownloadManifest.ManifestChapter(it.index, it.startSec, it.endSec, it.title)
                },
            )
            // Written last → atomic completion marker.
            manifestFile(sourceId, itemId).writeText(json.encodeToString(manifest))
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
            AudiobookDownloadResult.Success
        } catch (e: IOException) {
            dir.deleteRecursively() // leave no partial download behind
            AudiobookDownloadResult.NetworkError(e)
        }
    }

    private fun promoteCacheToDownload(
        sourceId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult {
        val from = cacheItemDir(sourceId, itemId)
        val to = itemDir(sourceId, itemId)
        val total = directorySize(from)
        return try {
            to.deleteRecursively()
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) {
                copyDirectory(from, to, total, onProgress)
                from.deleteRecursively()
            } else {
                onProgress(total, total)
            }
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
            AudiobookDownloadResult.Success
        } catch (e: IOException) {
            to.deleteRecursively()
            AudiobookDownloadResult.NetworkError(e)
        }
    }

    override suspend fun remove(sourceId: String, itemId: String): Long = withContext(dispatchers.io) {
        val dir = itemDir(sourceId, itemId)
        val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        dir.deleteRecursively()
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
        freed
    }

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun copyDirectory(
        from: File,
        to: File,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        var copied = 0L
        from.walkTopDown()
            .filter { it.isFile }
            .forEach { source ->
                val target = to.resolve(source.relativeTo(from).path)
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                    }
                }
            }
        onProgress(total, total)
    }
}
