package com.riffle.shared.audiobook

/**
 * Obj-C-compatible seam between iosMain and the Swift-side AVQueuePlayer wrapper.
 *
 * Swift implementation: IosAudioPlayerBridgeImpl (in iosApp/iosApp/).
 * Registered at startup via [IosAudioPlayerBridgeFactory] passed to startKoin().
 *
 * All callbacks are invoked on the main thread by the Swift implementation.
 * Track URLs must be fully-qualified (scheme + host + path + ?token=…).
 */
interface IosAudioPlayerBridge {
    /**
     * Load [trackUrls] into the player queue and seek to [startAtSec].
     * [trackStartOffsetsSec] gives the book-absolute start of each track in [trackUrls],
     * used by the bridge to compute book-absolute position reports.
     * [totalDurationSec] is the book's total duration, used for Now Playing.
     */
    fun preparePlayer(
        trackUrls: List<String>,
        trackStartOffsetsSec: List<Double>,
        startAtSec: Double,
        totalDurationSec: Double,
    )

    fun play()
    fun pause()

    /** Seek to a book-absolute [positionSec]. */
    fun seekTo(positionSec: Double)

    fun setSpeed(speed: Float)

    /** Book-absolute position in seconds; 0 before [preparePlayer]. */
    fun currentPositionSec(): Double

    fun isPlaying(): Boolean

    /**
     * Periodic book-absolute position callback; invoked on the main thread
     * approximately every 0.5 s while playing.
     */
    fun setPositionCallback(callback: ((positionSec: Double) -> Unit)?)

    /** Called whenever the playing/paused state changes. */
    fun setPlayingCallback(callback: ((isPlaying: Boolean) -> Unit)?)

    /**
     * Push Now Playing / lock-screen metadata.  Call after [preparePlayer] and whenever
     * the displayed chapter or cover changes.
     */
    fun setNowPlayingInfo(
        title: String,
        author: String,
        durationSec: Double,
        positionSec: Double,
        coverUrl: String?,
    )

    /** Release AVPlayer resources. Safe to call multiple times. */
    fun dispose()
}

/** Factory so Koin can produce one bridge instance per player open. */
interface IosAudioPlayerBridgeFactory {
    fun create(): IosAudioPlayerBridge
}
