package com.riffle.core.data

import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.SegmentTrackMapper
import com.riffle.core.network.NetworkAbsAudioTrack
import java.io.File

/**
 * Assembles a streaming session (ADR 0028) from the sidecar and the ABS audiobook tracks: parses the
 * Media Overlay track, reconciles its segments with the ABS tracks, and produces one clipped media
 * item per segment. Returns null when the two timelines can't be reconciled — the caller then falls
 * back to the bundle path (never streams a mismatch).
 */
class StreamingSetupBuilder {

    data class Setup(val track: ReadaloudTrack, val items: List<StreamingMediaItem>)

    fun build(
        sidecar: File,
        absTracks: List<NetworkAbsAudioTrack>,
        absBaseUrl: String,
        absItemId: String,
        absToken: String,
    ): Setup? {
        val track = runCatching { MediaOverlayReader.readTrack(sidecar) }.getOrNull()
            ?.takeIf { it.clips.isNotEmpty() }
            ?: return null
        val map = SegmentTrackMapper.align(track.clips, absTracks.map { it.durationSec }) ?: return null
        val items = StreamingMediaPlan.build(map.segmentPlacements(), absTracks, absBaseUrl, absItemId, absToken)
        return Setup(track, items)
    }
}
