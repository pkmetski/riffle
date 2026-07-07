package com.riffle.core.data

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkResult
import javax.inject.Inject

/**
 * Opens an ABS direct-play session (ADR 0029) and maps it to a playable [AudiobookSession]: each
 * track's server-relative `contentUrl` is turned into an absolute, token-bearing URL Media3 can
 * stream (ABS accepts the auth token as a `?token=` query param, so no custom auth data source is
 * needed). Chapter markers and durations pass straight through to the [AudiobookTimeline].
 */
class AudiobookRepositoryImpl @Inject constructor(
    private val playbackApi: AbsPlaybackApi,
    private val sessionApi: AbsSessionApi,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) : AudiobookRepository {

    override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession? {
        val source = sourceRepository.getById(sourceId) ?: return null
        val token = tokenStorage.getToken(sourceId) ?: return null
        val result = playbackApi.openPlaybackSession(
            baseUrl = source.url.value,
            libraryItemId = itemId,
            deviceId = sourceId, // stable per-server device id; ABS only needs it non-empty
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
        val session = (result as? NetworkResult.Success)?.value ?: return null
        if (session.tracks.isEmpty()) return null

        val base = source.url.value.trimEnd('/')
        val trackUrls = session.tracks.map { t ->
            val path = if (t.contentUrl.startsWith("/")) t.contentUrl else "/${t.contentUrl}"
            val sep = if (t.contentUrl.contains("?")) "&" else "?"
            "$base$path${sep}token=$token"
        }
        val spans = session.tracks.map { t ->
            AudiobookTrackSpan(index = t.index, startOffsetSec = t.startOffsetSec, durationSec = t.durationSec)
        }
        val timeline = AudiobookTimeline(
            durationSec = session.durationSec,
            chapters = session.chapters.mapIndexed { i, c ->
                AudiobookChapter(index = i, startSec = c.startSec, endSec = c.endSec, title = c.title)
            },
        )
        // Read ABS's server-side lastUpdate so the player can last-update-wins resume against the
        // durable local store. A failed read leaves it 0 (the play-session currentTime still resumes).
        val serverLastUpdate = (
            sessionApi.getProgress(
                baseUrl = source.url.value,
                libraryItemId = itemId,
                token = token,
                insecureAllowed = source.insecureConnectionAllowed,
            ) as? NetworkResult.Success
        )?.value?.lastUpdate ?: 0L

        return AudiobookSession(
            trackUrls = trackUrls,
            tracks = spans,
            timeline = timeline,
            serverCurrentTimeSec = session.currentTimeSec,
            serverLastUpdate = serverLastUpdate,
        )
    }

    override suspend fun saveProgress(sourceId: String, itemId: String, positionSec: Double, durationSec: Double) {
        val source = sourceRepository.getById(sourceId) ?: return
        val token = tokenStorage.getToken(sourceId) ?: return
        sessionApi.syncAudiobookProgress(
            baseUrl = source.url.value,
            libraryItemId = itemId,
            payload = NetworkAudiobookProgressPayload(currentTime = positionSec, duration = durationSec),
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
    }
}
