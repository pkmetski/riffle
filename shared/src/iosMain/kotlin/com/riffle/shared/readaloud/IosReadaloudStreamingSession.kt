package com.riffle.shared.readaloud

import com.riffle.core.data.StreamingMediaItem
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.feature.reader.ReadaloudStreamingSession

/**
 * iOS streaming session: carries the ABS audio URLs keyed by Storyteller audioSrc, along with the
 * pre-parsed [ReadaloudTrack] extracted from the sidecar, so [IosReadaloudPlayerController] can
 * open the stream using HTTP URLs instead of extracting audio from a local zip.
 */
internal class IosReadaloudStreamingSession(
    override val track: ReadaloudTrack,
    override val bundlePath: String,
    val items: List<StreamingMediaItem>,
) : ReadaloudStreamingSession
