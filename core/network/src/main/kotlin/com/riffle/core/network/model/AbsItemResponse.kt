package com.riffle.core.network.model

import com.riffle.core.models.AudiobookFingerprint
import com.riffle.core.network.NetworkAbsAudioTrack
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
        val ino: String = "",
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

    /** The streamable audiobook tracks (ino + duration), index-ordered, for streaming playback (ADR 0028). */
    fun audiobookTracks(): List<NetworkAbsAudioTrack> =
        media.audioFiles.sortedBy { it.index }.map {
            NetworkAbsAudioTrack(ino = it.ino, index = it.index, durationSec = it.duration)
        }
}
