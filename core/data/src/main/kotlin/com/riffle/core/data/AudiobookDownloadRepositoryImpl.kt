package com.riffle.core.data

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookTrackSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val okHttpClient: OkHttpClient,
    @com.riffle.core.data.di.AudiobookDownloadsDir private val downloadsDir: File,
) : AudiobookDownloadRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun itemDir(serverId: String, itemId: String) = File(downloadsDir, "$serverId/$itemId")
    private fun manifestFile(serverId: String, itemId: String) = File(itemDir(serverId, itemId), "manifest.json")

    override fun isDownloaded(serverId: String, itemId: String): Boolean =
        manifestFile(serverId, itemId).exists()

    override fun localSession(serverId: String, itemId: String): AudiobookSession? {
        val mf = manifestFile(serverId, itemId)
        if (!mf.exists()) return null
        val manifest = runCatching { json.decodeFromString<AudiobookDownloadManifest>(mf.readText()) }.getOrNull()
            ?: return null
        val dir = itemDir(serverId, itemId)
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
        serverId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult = withContext(Dispatchers.IO) {
        if (isDownloaded(serverId, itemId)) return@withContext AudiobookDownloadResult.Success
        val session = audiobookRepository.openSession(serverId, itemId)
            ?: return@withContext AudiobookDownloadResult.NetworkError(IOException("Could not open play session"))

        val dir = itemDir(serverId, itemId).apply { mkdirs() }
        var downloaded = 0L
        var total = 0L
        val manifestTracks = ArrayList<AudiobookDownloadManifest.ManifestTrack>()
        try {
            session.trackUrls.forEachIndexed { i, url ->
                val fileName = "track-$i"
                val out = File(dir, fileName)
                val request = Request.Builder().url(url).get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for track $i")
                    val body = response.body
                    val len = body.contentLength()
                    if (len > 0) total += len
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                onProgress(downloaded, total)
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
            manifestFile(serverId, itemId).writeText(json.encodeToString(manifest))
            AudiobookDownloadResult.Success
        } catch (e: IOException) {
            dir.deleteRecursively() // leave no partial download behind
            AudiobookDownloadResult.NetworkError(e)
        }
    }

    override suspend fun remove(serverId: String, itemId: String): Long = withContext(Dispatchers.IO) {
        val dir = itemDir(serverId, itemId)
        val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        dir.deleteRecursively()
        freed
    }
}
