package com.riffle.core.domain

/** A position on the ABS audiobook timeline: a track plus an offset (seconds) within it. */
data class AbsAudioPosition(val trackIndex: Int, val offsetSec: Double)

/**
 * Where one Storyteller segment sits on the ABS timeline: which track, the start offset, and how
 * long. Lets the player queue one media item per segment (preserving the existing clip/highlight
 * machinery) pointed at the ABS track, clipped to `[startSec, startSec + durationSec]` (ADR 0028).
 */
data class SegmentPlacement(
    val audioSrc: String,
    val absTrackIndex: Int,
    val startSec: Double,
    val durationSec: Double,
)

/**
 * Reconciles Storyteller's audio segments (one per distinct `audioSrc`, in clip order) with the
 * ABS audiobook's tracks, so a Media Overlay clip can be played from ABS. Two regimes (ADR 0028):
 *
 *  - **per-track** — Storyteller's segment durations match a contiguous run of ABS tracks (1:1,
 *    tolerant of dropped leading/trailing tracks like a publisher intro/outro). A clip plays from
 *    its matched track at its own `clipBegin` (offsets are per-segment).
 *  - **concatenated** — ABS serves the recording as fewer/larger files (e.g. one file). Segments
 *    tile a single global timeline; a clip plays at `Σ(earlier segment durations) + clipBegin`.
 */
class SegmentTrackMap internal constructor(
    private val segmentOrder: List<String>,
    private val perTrackStart: Int?,
    private val segmentDurations: List<Double>,
    private val absCumulative: List<Double>,
) {
    fun positionFor(clip: MediaOverlayClip): AbsAudioPosition? {
        val seg = segmentOrder.indexOf(clip.audioSrc)
        if (seg < 0) return null
        if (perTrackStart != null) {
            return AbsAudioPosition(perTrackStart + seg, clip.clipBeginSec)
        }
        val global = segmentDurations.take(seg).sum() + clip.clipBeginSec
        val track = absCumulative.indexOfLast { it <= global + TOLERANCE_SEC }
        if (track < 0) return null
        return AbsAudioPosition(track, global - absCumulative[track])
    }

    /** One placement per Storyteller segment, in order — the streaming media plan (ADR 0028). */
    fun segmentPlacements(): List<SegmentPlacement> {
        if (perTrackStart != null) {
            return segmentOrder.mapIndexed { k, src ->
                SegmentPlacement(src, perTrackStart + k, 0.0, segmentDurations[k])
            }
        }
        var global = 0.0
        return segmentOrder.mapIndexed { k, src ->
            val track = absCumulative.indexOfLast { it <= global + TOLERANCE_SEC }.coerceAtLeast(0)
            SegmentPlacement(src, track, global - absCumulative[track], segmentDurations[k])
                .also { global += segmentDurations[k] }
        }
    }

    internal companion object {
        const val TOLERANCE_SEC = 2.0
    }
}

object SegmentTrackMapper {

    private const val TOLERANCE_SEC = SegmentTrackMap.TOLERANCE_SEC

    fun align(clips: List<MediaOverlayClip>, absTrackDurationsSec: List<Double>): SegmentTrackMap? {
        val segmentOrder = clips.map { it.audioSrc }.distinct()
        val segmentDurations = segmentOrder.map { src ->
            clips.filter { it.audioSrc == src }.maxOf { it.clipEndSec }
        }

        val start = findContiguousRun(segmentDurations, absTrackDurationsSec)
        if (start != null) {
            return SegmentTrackMap(segmentOrder, start, segmentDurations, cumulative(absTrackDurationsSec))
        }

        // Concatenated fallback: only valid when the two timelines cover the same total length.
        val segTotal = segmentDurations.sum()
        val absTotal = absTrackDurationsSec.sum()
        if (kotlin.math.abs(segTotal - absTotal) > TOLERANCE_SEC) return null
        return SegmentTrackMap(segmentOrder, null, segmentDurations, cumulative(absTrackDurationsSec))
    }

    /** The start index in [tracks] of a run whose durations match [segments] pairwise, or null. */
    private fun findContiguousRun(segments: List<Double>, tracks: List<Double>): Int? {
        if (segments.isEmpty() || segments.size > tracks.size) return null
        for (start in 0..(tracks.size - segments.size)) {
            if (segments.indices.all { kotlin.math.abs(segments[it] - tracks[start + it]) <= TOLERANCE_SEC }) {
                return start
            }
        }
        return null
    }

    private fun cumulative(durations: List<Double>): List<Double> {
        var acc = 0.0
        return durations.map { val at = acc; acc += it; at }
    }
}
