package com.riffle.core.network.model

import com.riffle.core.domain.AudiobookFingerprint
import kotlinx.serialization.Serializable

/**
 * The slice of `GET /api/v2/books/{id}` Riffle needs for the identity check (ADR 0028): the
 * **ingested-source** audiobook's byte size, total duration, and per-track durations — i.e. the
 * original file Storyteller aligned against, not the re-split bundle.
 */
@Serializable
data class StorytellerV2BookResponse(
    val audiobook: AudiobookDto? = null,
) {
    @Serializable
    data class AudiobookDto(
        val fileSize: Long = 0,
        val duration: Double = 0.0,
        val manifest: ManifestDto = ManifestDto(),
    )

    @Serializable
    data class ManifestDto(
        val readingOrder: List<TrackDto> = emptyList(),
    )

    @Serializable
    data class TrackDto(
        val duration: Double = 0.0,
    )

    /** The ingested-source fingerprint, or null when the book has no audiobook attached. */
    fun toFingerprint(): AudiobookFingerprint? {
        val ab = audiobook ?: return null
        return AudiobookFingerprint(
            fileSizeBytes = ab.fileSize,
            durationSec = ab.duration,
            trackDurationsSec = ab.manifest.readingOrder.map { it.duration },
        )
    }
}
