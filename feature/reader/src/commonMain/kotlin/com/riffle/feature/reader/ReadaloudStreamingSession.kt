package com.riffle.feature.reader

import com.riffle.core.domain.ReadaloudTrack

/** Platform-neutral view of a streaming readaloud session (ADR 0040). iOS always returns null. */
interface ReadaloudStreamingSession {
    val track: ReadaloudTrack
    val bundlePath: String
}
