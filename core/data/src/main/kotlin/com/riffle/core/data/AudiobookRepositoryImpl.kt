package com.riffle.core.data

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkPlaybackSessionResult
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
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : AudiobookRepository {

    override suspend fun openSession(serverId: String, itemId: String): AudiobookSession? {
        val server = serverRepository.getById(serverId) ?: return null
        val token = tokenStorage.getToken(serverId) ?: return null
        val result = playbackApi.openPlaybackSession(
            baseUrl = server.url.value,
            libraryItemId = itemId,
            deviceId = serverId, // stable per-server device id; ABS only needs it non-empty
            token = token,
            insecureAllowed = server.insecureConnectionAllowed,
        )
        val session = (result as? NetworkPlaybackSessionResult.Success)?.session ?: return null
        if (session.tracks.isEmpty()) return null

        val base = server.url.value.trimEnd('/')
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
        return AudiobookSession(
            trackUrls = trackUrls,
            tracks = spans,
            timeline = timeline,
            serverCurrentTimeSec = session.currentTimeSec,
        )
    }

    override suspend fun saveProgress(serverId: String, itemId: String, positionSec: Double, durationSec: Double) {
        val server = serverRepository.getById(serverId) ?: return
        val token = tokenStorage.getToken(serverId) ?: return
        sessionApi.syncAudiobookProgress(
            baseUrl = server.url.value,
            libraryItemId = itemId,
            payload = NetworkAudiobookProgressPayload(currentTime = positionSec, duration = durationSec),
            token = token,
            insecureAllowed = server.insecureConnectionAllowed,
        )
    }
}
