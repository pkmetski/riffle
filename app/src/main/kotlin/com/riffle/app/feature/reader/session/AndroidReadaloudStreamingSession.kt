package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory
import com.riffle.app.feature.reader.readaloud.SharedBundle
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.feature.reader.ReadaloudStreamingSession

/**
 * Wraps the Android [ReadaloudStreamingSessionFactory.Session] as a platform-neutral
 * [ReadaloudStreamingSession]. Carries [streaming] so [AndroidReadaloudPlayerController] can
 * open the stream via [SharedBundle.Streaming] without downcasting at the common level.
 */
class AndroidReadaloudStreamingSession(
    val factory: ReadaloudStreamingSessionFactory.Session,
) : ReadaloudStreamingSession {
    override val track: ReadaloudTrack get() = factory.track
    override val bundlePath: String get() = factory.sidecarFile.absolutePath
    val streaming: SharedBundle.Streaming get() = factory.streaming
}
