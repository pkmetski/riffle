package com.riffle.shared.readaloud

/**
 * Obj-C-compatible seam between iosMain and the Swift-side AVQueuePlayer wrapper for Readaloud.
 *
 * Swift implementation: IosReadaloudBridgeImpl (in iosApp/iosApp/).
 * Registered at startup via [IosReadaloudBridgeFactory] passed to startKoin().
 *
 * The bridge is index-addressed: [prepareAudioSrcs] queues distinct audio files by their
 * (extracted) `file://` URL, and all subsequent calls reference them by the index in that list.
 * [IosReadaloudController] owns the mapping from [com.riffle.core.domain.ReadaloudTrack] clip
 * audioSrc paths to queue indices — the Swift side never sees raw zip-entry paths.
 *
 * Callbacks use interface types instead of function literals to avoid Kotlin/Native boxing of
 * primitive types (Double → KotlinDouble, Boolean → KotlinBoolean) in ObjC block parameters.
 */
interface IosReadaloudBridge {
    /**
     * Load [audioFileUrls] (playable `file://` or `http://` URLs) into the AVQueuePlayer and
     * optionally seek to [startSrcIndex] / [startOffsetSec] before the first play.
     * Does not auto-play — caller must call [play] explicitly.
     */
    fun prepareAudioSrcs(
        audioFileUrls: List<String>,
        startSrcIndex: Int,
        startOffsetSec: Double,
    )

    fun play()
    fun pause()

    /**
     * Seek to [offsetSec] within audio-file slot [srcIndex]. Rebuilds the queue when the target
     * is a different or already-consumed file — same semantics as the audiobook bridge.
     */
    fun seekToSrc(srcIndex: Int, offsetSec: Double)

    fun setSpeed(speed: Float)

    /** Index of the audio file currently playing; 0 before [prepareAudioSrcs]. */
    fun currentSrcIndex(): Int

    /** Playback offset within the current audio file in seconds; 0 before [prepareAudioSrcs]. */
    fun currentOffsetSec(): Double

    fun isPlaying(): Boolean

    /**
     * Periodic position callback; invoked on the main thread approximately every 250 ms while
     * playing, and after every seek this bridge performs internally.
     */
    fun setPositionCallback(callback: IosReadaloudPositionCallback?)

    /** Called whenever the playing/paused state changes. */
    fun setPlayingCallback(callback: IosReadaloudPlayingCallback?)

    /** Release AVPlayer resources. Safe to call multiple times. */
    fun dispose()
}

interface IosReadaloudPositionCallback {
    fun onPosition(srcIndex: Int, offsetSec: Double)
}

interface IosReadaloudPlayingCallback {
    fun onPlaying(isPlaying: Boolean)
}

/** Factory so Koin can produce one bridge instance per reader open. */
interface IosReadaloudBridgeFactory {
    fun create(): IosReadaloudBridge
}
