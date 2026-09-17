package com.riffle.shared.audiobook

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.getOrNull

/**
 * Opens Audiobookshelf audiobook sessions on iOS directly through [AbsPlaybackApi].
 *
 * The shared [com.riffle.core.data.AudiobookRepositoryImpl] resolves sessions through the
 * `CatalogRegistry`, but iOS registers no ABS catalog (`AbsCatalog` is JVM-only), so for every ABS
 * source it returns null and the player lands on "Could not open audiobook". That regressed in
 * #1054, which replaced the iOS-specific player ViewModel (it called the playback API itself) with
 * the shared one. This wrapper restores the direct path for ABS sources — mirroring
 * `AbsCatalog.openAudiobook` — and delegates every other source and every other member.
 */
class IosAbsAudiobookRepository(
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val playbackApi: AbsPlaybackApi,
    private val sessionApi: AbsSessionApi,
    private val deviceIdStore: DeviceIdStore,
    private val delegate: AudiobookRepository,
) : AudiobookRepository {

    override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession? {
        val source = sourceRepository.getById(sourceId)
        if (source == null || source.type != SourceType.ABS) return delegate.openSession(sourceId, itemId)

        val token = tokenStorage.getToken(source.id) ?: return null
        val baseUrl = source.url.value.trimEnd('/')
        val insecure = source.insecureConnectionAllowed
        val session = playbackApi
            .openPlaybackSession(baseUrl, itemId, deviceIdStore.getOrCreate(), token, insecure)
            .getOrNull() ?: return null
        if (session.tracks.isEmpty()) return null

        val trackUrls = session.tracks.map { track ->
            val path = if (track.contentUrl.startsWith("/")) track.contentUrl else "/${track.contentUrl}"
            val separator = if (track.contentUrl.contains("?")) "&" else "?"
            "$baseUrl$path${separator}token=$token"
        }
        val spans = session.tracks.map { track ->
            AudiobookTrackSpan(index = track.index, startOffsetSec = track.startOffsetSec, durationSec = track.durationSec)
        }
        val chapters = session.chapters.mapIndexed { index, chapter ->
            AudiobookChapter(index = index, startSec = chapter.startSec, endSec = chapter.endSec, title = chapter.title)
        }
        val serverLastUpdate = sessionApi.getProgress(baseUrl, itemId, token, insecure).getOrNull()?.lastUpdate ?: 0L

        return AudiobookSession(
            trackUrls = trackUrls,
            tracks = spans,
            timeline = AudiobookTimeline(durationSec = session.durationSec, chapters = chapters),
            serverCurrentTimeSec = session.currentTimeSec,
            serverLastUpdate = serverLastUpdate,
        )
    }

    override suspend fun downloadSizeBytes(sourceId: String, itemId: String): Long? =
        delegate.downloadSizeBytes(sourceId, itemId)

    override suspend fun saveProgress(sourceId: String, itemId: String, positionSec: Double, durationSec: Double) =
        delegate.saveProgress(sourceId, itemId, positionSec, durationSec)
}
