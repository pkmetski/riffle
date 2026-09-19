package com.riffle.core.data

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.models.AudiobookTrackSpan
import kotlinx.serialization.Serializable

/**
 * On-disk manifest written after a successful download so the book plays offline (ADR 0035).
 *
 * Shared by the Android (`AudiobookDownloadRepositoryImpl`/`AudiobookCacheRepositoryImpl`) and iOS
 * (`IosAudiobookDownloadRepositoryImpl`/`IosAudiobookCacheRepositoryImpl`) repositories so the
 * two platforms can never disagree on the format — a book downloaded by one would otherwise be
 * unreadable by the other's parser, and the drift would only show up at playback time.
 *
 * The manifest is always written **last**, so its presence is the atomic "fully downloaded"
 * marker: a partial download (some tracks, no manifest) reads as not-downloaded and is re-fetched.
 */
@Serializable
internal data class AudiobookDownloadManifest(
    val durationSec: Double,
    val tracks: List<ManifestTrack>,
    val chapters: List<ManifestChapter>,
) {
    @Serializable
    data class ManifestTrack(val index: Int, val file: String, val startOffsetSec: Double, val durationSec: Double)

    @Serializable
    data class ManifestChapter(val index: Int, val startSec: Double, val endSec: Double, val title: String)

    /**
     * Rebuilds a playable session from this manifest. [trackUrlFor] maps a manifest track's file
     * name to a platform-local URL (a `file://` URI on both platforms today).
     */
    fun toSession(trackUrlFor: (fileName: String) -> String): AudiobookSession = AudiobookSession(
        trackUrls = tracks.map { trackUrlFor(it.file) },
        tracks = tracks.map { AudiobookTrackSpan(it.index, it.startOffsetSec, it.durationSec) },
        timeline = AudiobookTimeline(
            durationSec = durationSec,
            chapters = chapters.map { AudiobookChapter(it.index, it.startSec, it.endSec, it.title) },
        ),
        // Resume position comes from progress sync, not the manifest.
        serverCurrentTimeSec = 0.0,
    )

    companion object {
        /** Builds a manifest from a live session plus the track files that were actually written. */
        fun from(session: AudiobookSession, tracks: List<ManifestTrack>) = AudiobookDownloadManifest(
            durationSec = session.timeline.durationSec,
            tracks = tracks.sortedBy { it.index },
            chapters = session.timeline.chapters.map {
                ManifestChapter(it.index, it.startSec, it.endSec, it.title)
            },
        )
    }
}
