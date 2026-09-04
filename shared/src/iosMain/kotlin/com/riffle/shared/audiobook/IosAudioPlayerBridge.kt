package com.riffle.shared.audiobook

/**
 * Obj-C-compatible seam between iosMain and the Swift-side AVQueuePlayer wrapper.
 *
 * Swift implementation: IosAudioPlayerBridgeImpl (in iosApp/iosApp/).
 * Registered at startup via [IosAudioPlayerBridgeFactory] passed to startKoin().
 *
 * All callbacks are invoked on the main thread by the Swift implementation.
 * Track URLs must be fully-qualified (scheme + host + path + ?token=…).
 *
 * Callbacks use interface types instead of function literals to avoid Kotlin/Native
 * boxing of primitive types (Double → KotlinDouble, Boolean → KotlinBoolean) in
 * ObjC block parameters, which would make the Swift implementation cumbersome.
 */
interface IosAudioPlayerBridge {
    /**
     * Load [trackUrls] into the player queue and seek to [startAtSec].
     * [trackStartOffsetsSec] gives the book-absolute start of each track in [trackUrls],
     * used by the bridge to compute book-absolute position reports.
     * [totalDurationSec] is the book's total duration, used for Now Playing.
     *
     * DoubleArray (not List<Double>) so the ObjC bridge exposes KotlinDoubleArray —
     * a concrete type with primitive-indexed accessors — instead of a generic NSArray
     * of boxed KotlinDouble objects.
     */
    fun preparePlayer(
        trackUrls: List<String>,
        trackStartOffsetsSec: DoubleArray,
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
    fun setPositionCallback(callback: IosPositionCallback?)

    /** Called whenever the playing/paused state changes. */
    fun setPlayingCallback(callback: IosPlayingCallback?)

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

/**
 * Callback for periodic position updates.
 * Interface (not lambda) so Kotlin/Native emits a proper ObjC protocol with
 * a primitive `double` parameter instead of a boxed `KotlinDouble` block parameter.
 */
interface IosPositionCallback {
    fun onPosition(positionSec: Double)
}

/**
 * Callback for play/pause state changes.
 * Same rationale as [IosPositionCallback] — avoids KotlinBoolean boxing in ObjC blocks.
 */
interface IosPlayingCallback {
    fun onPlaying(isPlaying: Boolean)
}

/** Factory so Koin can produce one bridge instance per player open. */
interface IosAudioPlayerBridgeFactory {
    fun create(): IosAudioPlayerBridge
}
