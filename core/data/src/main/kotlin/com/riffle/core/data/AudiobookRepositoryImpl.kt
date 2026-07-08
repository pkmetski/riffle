package com.riffle.core.data

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.Clock
import javax.inject.Inject

/**
 * Opens a Source-native direct-play audiobook session (ADR 0029) and maps it to a playable
 * [AudiobookSession]. Track URLs come from the Source's [AudiobookMediaCapability] with any auth
 * headers/tokens baked in. Chapter markers and durations pass straight through to [AudiobookTimeline].
 */
class AudiobookRepositoryImpl @Inject constructor(
    private val catalogRegistry: CatalogRegistry,
    private val clock: Clock,
) : AudiobookRepository {

    override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession? {
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return null
        val audioCap = catalog as? AudiobookMediaCapability ?: return null
        // deviceLabel is opaque to Riffle — ABS treats it as the session's device id.
        val stream = audioCap.openAudiobook(itemId, deviceLabel = sourceId) ?: return null
        val spans = stream.tracks.map { t ->
            AudiobookTrackSpan(index = t.index, startOffsetSec = t.startOffsetSec, durationSec = t.durationSec)
        }
        val timeline = AudiobookTimeline(
            durationSec = stream.totalDurationSec,
            chapters = stream.chapters.map { c ->
                AudiobookChapter(index = c.index, startSec = c.startSec, endSec = c.endSec, title = c.title)
            },
        )
        return AudiobookSession(
            trackUrls = stream.trackUrls,
            tracks = spans,
            timeline = timeline,
            serverCurrentTimeSec = stream.serverCurrentTimeSec,
            serverLastUpdate = stream.serverLastUpdate,
        )
    }

    override suspend fun saveProgress(sourceId: String, itemId: String, positionSec: Double, durationSec: Double) {
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return
        val peer = catalog as? ProgressPeerCapability ?: return
        runCatching {
            peer.pushAudiobookProgress(
                itemId = itemId,
                currentTimeSec = positionSec,
                durationSec = durationSec,
                // ABS derives audiobook finished-state from progress==1.0 server-side; sending
                // isFinished here would only touch the ebook dimension of the shared record.
                isFinished = null,
                lastUpdateEpochMs = clock.nowMs(),
            )
        }
    }
}
