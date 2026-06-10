package com.riffle.app.feature.reader.readaloud

import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Bridges the headless [ReadaloudController] to the reader UI. It owns the active [ReadaloudTrack]
 * and derives [activeFragmentRef] — the text fragment currently being narrated — from the
 * controller's polled playback position. The screen decorates that fragment as the synced highlight
 * and follows it (see EpubReaderScreen's auto-follow).
 *
 * Lives as a per-reader instance (constructed by the ViewModel, not a @Singleton) so its scope
 * dies with the reader. The shared [ReadaloudController] it drives is the singleton.
 */
class PlayerCoordinator @Inject constructor(
    private val controller: ReadaloudController,
    private val audioRepository: ReadaloudAudioRepository,
) {
    /** Mirrors the controller's playback state so the screen has a single thing to observe. */
    val state: StateFlow<ReadaloudController.PlaybackState> = controller.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var track: ReadaloudTrack? = null

    private val _activeFragmentRef = MutableStateFlow<String?>(null)
    /** The text fragment currently narrated, or null when nothing is playing/prepared. */
    val activeFragmentRef: StateFlow<String?> = _activeFragmentRef.asStateFlow()

    init {
        scope.launch {
            controller.state.collect { s ->
                val t = track
                _activeFragmentRef.value = if (t != null && s.currentAudioSrc != null) {
                    t.activeClipAt(s.currentAudioSrc, s.positionSec)?.textFragmentRef
                } else {
                    null
                }
            }
        }
    }

    /** Connects the controller to [bundleFile] and queues [track]'s audio. */
    suspend fun open(itemId: String, bundleFile: File, track: ReadaloudTrack) {
        this.track = track
        controller.prepare(bundleFile, track)
    }

    /**
     * "Play from here" from the text-selection menu. [fragmentRef] is the selection's
     * "href#fragmentId" (or a bare "href" when the selection carries no fragment). Resolves exactly
     * like [playFromReaderPosition] — the selected sentence if it maps to a narrated clip, else the
     * chapter's first clip, else the nearest narrated clip after it — so a free-text selection (which
     * rarely lands on a SMIL boundary) starts on the page the user is reading rather than silently
     * restarting the whole book.
     */
    fun playFromHere(fragmentRef: String) {
        val href = fragmentRef.substringBefore('#')
        val fragmentId = fragmentRef.substringAfter('#', "").ifEmpty { null }
        playFromReaderPosition(href, fragmentId)
    }

    /**
     * Starts playback at the reader's current position: the sentence under the cursor ([fragmentId])
     * if it maps to a clip, else the first clip of [href]'s chapter, else the nearest narrated clip
     * after it. Does NOT fall back to plain `play()` when nothing resolves: on a freshly-prepared
     * session the controller sits at position 0, so `play()` would start the book over — and the
     * reader's auto-follow would then drag the reading position back to the start, erasing progress.
     * Not starting is strictly safer than restarting the book.
     */
    fun playFromReaderPosition(href: String, fragmentId: String?) {
        val clip = track?.resolveStartClip(href, fragmentId) ?: return
        controller.playFromFragment(clip.textFragmentRef)
    }

    fun play() = controller.play()

    fun pause() = controller.pause()

    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    fun rewind() = controller.rewind()

    fun forward() = controller.forward()

    fun previousChapter() = controller.previousChapter()

    fun nextChapter() = controller.nextChapter()

    /** Stops playback and tears the session down — the active fragment clears, so does the highlight. */
    fun close() {
        track = null
        controller.stop()
        _activeFragmentRef.value = null
    }

    /** Cancels the state-collection scope. Call when the owning ViewModel is cleared (not on a
     *  mere bar-close, which must leave the coordinator reusable for the next open). */
    fun dispose() {
        scope.cancel()
    }
}
