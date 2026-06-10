package com.riffle.core.network.model

import com.riffle.core.domain.AudiobookFingerprint
import kotlinx.serialization.Serializable

@Serializable
internal data class AbsItemResponse(
    val id: String,
    val libraryId: String = "",
    val media: AbsItemMediaDto,
) {
    @Serializable
    data class AbsItemMediaDto(
        val ebookFile: AbsItemEbookFileDto? = null,
        val audioFiles: List<AbsAudioFileDto> = emptyList(),
        val duration: Double? = null,
    )

    @Serializable
    data class AbsItemEbookFileDto(
        val ino: String = "",
    )

    @Serializable
    data class AbsAudioFileDto(
        val index: Int = 0,
        val duration: Double = 0.0,
        val metadata: AbsAudioFileMetadataDto = AbsAudioFileMetadataDto(),
    )

    @Serializable
    data class AbsAudioFileMetadataDto(
        val size: Long = 0,
    )

    /** The ABS audiobook's fingerprint for the identity check (ADR 0028), or null if it has no audio. */
    fun audiobookFingerprint(): AudiobookFingerprint? {
        if (media.audioFiles.isEmpty()) return null
        val tracks = media.audioFiles.sortedBy { it.index }
        return AudiobookFingerprint(
            fileSizeBytes = tracks.sumOf { it.metadata.size },
            durationSec = media.duration ?: tracks.sumOf { it.duration },
            trackDurationsSec = tracks.map { it.duration },
        )
    }
}
