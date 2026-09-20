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
 * **The bridge is index-addressed, never book-absolute.** It reports and accepts
 * `(trackIndex, offsetSec)` pairs only; projecting those onto the book's continuous timeline is
 * [IosAudioPlayerController]'s job, via the shared `AudiobookTracks` math Android also uses
 * (Android's equivalent seam, `MediaController.seekTo(index, ms)`, is index-addressed for the same
 * reason). Handing the Swift side the track offsets and asking it for an absolute position is what
 * produced the multi-track corruption: `AVQueuePlayer` *removes* each item as it finishes, so the
 * index derived from queue membership collapsed back to 0 after the first track and every position
 * written to the durable store from then on was short by the whole elapsed book.
 *
 * Callbacks use interface types instead of function literals to avoid Kotlin/Native
 * boxing of primitive types (Double → KotlinDouble, Boolean → KotlinBoolean) in
 * ObjC block parameters, which would make the Swift implementation cumbersome.
 */
interface IosAudioPlayerBridge {
    /**
     * Load [trackUrls] into the player queue and start at [startOffsetSec] within
     * [startTrackIndex]. The player does not know the book timeline; the caller resolves the
     * resume point first.
     */
    fun preparePlayer(
        trackUrls: List<String>,
        startTrackIndex: Int,
        startOffsetSec: Double,
    )

    fun play()
    fun pause()

    /**
     * Seek to [offsetSec] within track [trackIndex]. Must work in **both** directions: seeking to a
     * track the queue has already consumed re-queues from there. Implementations must not seek the
     * current item and then advance — the next item would start at 0, not at [offsetSec].
     */
    fun seekToTrack(trackIndex: Int, offsetSec: Double)

    fun setSpeed(speed: Float)

    /** Index of the track being played right now; 0 before [preparePlayer]. */
    fun currentTrackIndex(): Int

    /** Offset within the current track in seconds; 0 before [preparePlayer]. */
    fun currentTrackOffsetSec(): Double

    /** Seconds of the current track buffered ahead of the playhead; 0 when unknown. */
    fun currentTrackBufferedSec(): Double

    fun isPlaying(): Boolean

    /**
     * Replace the queued tracks from [fromIndex] to the end with [trackUrls], leaving the currently
     * playing track untouched. Used by the auto-cache swap to repoint the unplayed tail at local
     * `file://` URLs once the whole book is cached. A [fromIndex] at or before the current track is
     * ignored — swapping the item under the playhead would restart it.
     */
    fun replaceTracksFrom(fromIndex: Int, trackUrls: List<String>)

    /**
     * Periodic position callback; invoked on the main thread approximately every 0.5 s while
     * playing, and immediately after any seek this bridge performs on its own (remote commands).
     */
    fun setPositionCallback(callback: IosPositionCallback?)

    /** Called whenever the playing/paused state changes. */
    fun setPlayingCallback(callback: IosPlayingCallback?)

    /**
     * Called for lock-screen / Control Centre transport commands. These arrive in **book-absolute**
     * terms (the Now Playing scrubber is the book's timeline), so the bridge forwards them here
     * instead of resolving them itself.
     */
    fun setRemoteCommandCallback(callback: IosRemoteCommandCallback?)

    /**
     * Called when the player naturally exhausts all tracks (natural end-of-book).
     * Distinct from [setPlayingCallback]: that fires for any pause (user tap, interruption,
     * phone call); this fires only when AVQueuePlayer emits AVPlayerItemDidPlayToEndTime
     * for the last item.
     */
    fun setEndOfBookCallback(callback: IosEndOfBookCallback?)

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
 * Callback for periodic position updates, reported as a `(track index, offset in track)` pair —
 * see [IosAudioPlayerBridge] for why the bridge never reports a book-absolute position.
 *
 * Interface (not lambda) so Kotlin/Native emits a proper ObjC protocol with primitive
 * `int32_t`/`double` parameters instead of boxed `KotlinInt`/`KotlinDouble` block parameters.
 */
interface IosPositionCallback {
    fun onPosition(trackIndex: Int, offsetSec: Double)
}

/**
 * Callback for play/pause state changes.
 * Same rationale as [IosPositionCallback] — avoids KotlinBoolean boxing in ObjC blocks.
 */
interface IosPlayingCallback {
    fun onPlaying(isPlaying: Boolean)
}

/**
 * Callback fired only on natural end-of-book playback (AVPlayerItemDidPlayToEndTime for the
 * last track), not on user pause or audio interruption. Avoids false end-of-book detection
 * when the user pauses within END_OF_BOOK_EPS_SEC of the end.
 */
interface IosEndOfBookCallback {
    fun onEndOfBook()
}

/**
 * Lock-screen / Control Centre transport commands, resolved against the book timeline by
 * [IosAudioPlayerController]. Same interface-not-lambda rationale as [IosPositionCallback].
 */
interface IosRemoteCommandCallback {
    /** Now Playing scrubber dragged to a **book-absolute** [positionSec]. */
    fun onSeekAbsolute(positionSec: Double)

    /** Skip-forward / skip-back buttons; [deltaSec] is signed, in book-absolute seconds. */
    fun onSkip(deltaSec: Double)

    /** Next-/previous-track buttons; [delta] is +1 or -1. */
    fun onTrackDelta(delta: Int)
}

/** Factory so Koin can produce one bridge instance per player open. */
interface IosAudioPlayerBridgeFactory {
    fun create(): IosAudioPlayerBridge
}
