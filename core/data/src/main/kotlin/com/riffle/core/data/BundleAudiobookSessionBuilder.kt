package com.riffle.core.data

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.ReadaloudTrack
import java.io.File

/**
 * Maps a bundle's Media Overlay [ReadaloudTrack] to a playable [AudiobookSession]: one span per
 * distinct audio file (the same playlist order Readaloud queues), with cumulative book-absolute
 * offsets, and chapters derived from the bundle's distinct chapter hrefs. The bundle audio is the
 * same ABS source re-split into segments, so these spans are simply a different — but still
 * contiguous — covering of the book timeline; the player's seek math works on any such covering.
 *
 * Track URLs are the audio files' zip-entry paths (a clip's `audioSrc`); the playback service routes
 * any non-http/file mediaId through `ZipAudioDataSource`, reading from [bundle] via `SharedBundle`.
 * Returns null when the bundle has no Media Overlay clips.
 *
 * Chapter titles are numbered ("Chapter N") — the SMIL overlay carries chapter hrefs and timings but
 * not display titles. This is the accepted v1 offline degradation.
 */
internal fun buildBundleAudiobookSession(track: ReadaloudTrack, bundle: File): AudiobookSession? {
    val files = track.clips.map { it.audioSrc }.distinct()
    if (files.isEmpty()) return null

    val durByFile = track.clips.groupBy { it.audioSrc }.mapValues { (_, cs) -> cs.maxOf { it.clipEndSec } }
    var acc = 0.0
    val spans = files.mapIndexed { i, src ->
        val dur = durByFile[src] ?: 0.0
        AudiobookTrackSpan(index = i, startOffsetSec = acc, durationSec = dur).also { acc += dur }
    }
    val totalDuration = acc
    val fileStart = files.zip(spans).associate { (src, span) -> src to span.startOffsetSec }

    val starts = (0 until track.chapterCount).mapNotNull { idx ->
        track.firstClipOfChapter(idx)?.let { clip -> (fileStart[clip.audioSrc] ?: 0.0) + clip.clipBeginSec }
    }.sorted()
    val chapters = starts.mapIndexed { i, start ->
        AudiobookChapter(
            index = i,
            startSec = start,
            endSec = starts.getOrNull(i + 1) ?: totalDuration,
            title = "Chapter ${i + 1}",
        )
    }

    return AudiobookSession(
        trackUrls = files,
        tracks = spans,
        timeline = AudiobookTimeline(durationSec = totalDuration, chapters = chapters),
        serverCurrentTimeSec = 0.0,
        serverLastUpdate = 0L,
        localZipFile = bundle,
    )
}
