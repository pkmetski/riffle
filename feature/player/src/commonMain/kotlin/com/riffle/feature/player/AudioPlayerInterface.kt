package com.riffle.feature.player

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.models.AudiobookTrackSpan
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic seam for the audiobook playback backend. Hides all Android/Media3 types from
 * [AudiobookPlayerViewModel] so the ViewModel can live in a JVM KMP source set.
 *
 * Android implementation: [com.riffle.app.feature.audiobook.AudiobookController].
 *
 * [localZipFilePath] in [prepare] is a `String?` path rather than `java.io.File?` so this interface
 * can live in commonMain. The Android implementation converts it back internally.
 */
interface AudioPlayerInterface {
    val state: StateFlow<PlaybackState>
    val sleepTimer: StateFlow<SleepTimerMode>

    // replay=1 so a STATE_ENDED that fires while no collector is attached is still delivered.
    val playbackEnded: SharedFlow<Unit>

    /**
     * Connects (if needed) and queues the audiobook's [trackUrls], one per [spans] entry,
     * then seeks to [startAtSec] on the book-absolute timeline.
     *
     * @param localZipFilePath absolute path to a bundle zip file for bundle-backed audio,
     *   or null for HTTP/file sessions.
     */
    suspend fun prepare(
        trackUrls: List<String>,
        spans: List<AudiobookTrackSpan>,
        durationSec: Double,
        startAtSec: Double,
        localZipFilePath: String? = null,
        coverUri: String? = null,
        bookTitle: String? = null,
        chapters: List<AudiobookChapter> = emptyList(),
    )

    /**
     * Establishes the binder connection without touching media items. Call during pre-warm
     * so the first swipe-up pays ~0 ms instead of the full round-trip (ADR 0039).
     */
    suspend fun warmBinder()

    fun play()
    fun pause()
    fun setSpeed(speed: Float)
    fun setSleepTimer(mode: SleepTimerMode)
    fun cancelSleepTimer()

    /** Called by ViewModel when a chapter boundary is crossed in EndOfChapter mode. */
    fun triggerSleepNow()

    /** Seeks to a book-absolute position. */
    fun seekTo(absoluteSec: Double)
    fun skipBy(deltaSec: Double)

    /** The book-absolute position as of the last poll/push. */
    fun currentAbsoluteSec(): Double

    /**
     * Replaces media items from [fromIndex] to the end of the queue with [newUrls].
     * Used by the auto-cache swap.
     */
    fun swapTracksFromIndex(fromIndex: Int, newUrls: List<String>)

    /**
     * Discards any cached end-of-book event WITHOUT tearing the session down. Used on
     * playlist auto-advance so the next VM doesn't re-consume the previous item's STATE_ENDED.
     */
    fun clearEndOfBookCache()

    /** Tears down the MediaSession queue and releases the player. Idempotent. */
    fun stop()

    /**
     * Releases the audiobook handle WITHOUT stopping the shared player — used when readaloud
     * is taking over the same session (the audiobook→readaloud swipe).
     */
    fun releaseForHandoff()

    /**
     * The playback state snapshot surfaced by the controller's poll loop.
     *
     * [positionSec] and [bufferedSec] are book-absolute (across all tracks).
     */
    data class PlaybackState(
        val connected: Boolean = false,
        val isPlaying: Boolean = false,
        val speed: Float = 1f,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val bufferedSec: Double = 0.0,
    )
}
