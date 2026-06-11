package com.riffle.core.domain

/**
 * A ready-to-play [Audiobook] session resolved from ABS (ADR 0029): the ordered, directly-streamable
 * track URLs paired with their timeline [tracks], the [timeline] (duration + chapter markers), and the
 * server-recorded resume position. Audio streams from ABS; nothing here comes from a Storyteller bundle.
 */
data class AudiobookSession(
    val trackUrls: List<String>,
    val tracks: List<AudiobookTrackSpan>,
    val timeline: AudiobookTimeline,
    val serverCurrentTimeSec: Double,
)

interface AudiobookRepository {
    /** Opens an ABS direct-play session for the audiobook item, or null if it can't be opened. */
    suspend fun openSession(serverId: String, itemId: String): AudiobookSession?

    /**
     * Pushes the audiobook's book-absolute listen position to its single ABS progress record. This is
     * the audiobook-only single-peer sync (ADR 0029); a matched Readaloud routes through the canonical
     * reconciliation cycle instead. No-op-safe to call repeatedly (on pause / close / periodically).
     */
    suspend fun saveProgress(serverId: String, itemId: String, positionSec: Double, durationSec: Double)
}
