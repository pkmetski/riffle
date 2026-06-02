package com.riffle.app.feature.reader.readaloud

import com.riffle.core.domain.AutoPageTurnRule
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Bridges the headless [ReadaloudController] to the reader UI. It owns the active
 * [ReadaloudTrack] and derives, from the controller's polled playback position:
 *
 *  - [activeFragmentRef]: the text fragment currently being narrated, which the screen
 *    decorates as the synced highlight.
 *  - [advanceEvents]: an "advance the page" signal emitted (via [AutoPageTurnRule]) when the
 *    narrated sentence scrolls off the bottom of the viewport during playback.
 *
 * The screen feeds back the set of currently-visible fragment refs through
 * [reportVisibleFragments]; the coordinator maps everything to document-order indices via
 * [ReadaloudTrack.indexOfFragment] before consulting the pure rule.
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

    private val _advanceEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once each time playback carries the active sentence off-screen. */
    val advanceEvents: SharedFlow<Unit> = _advanceEvents.asSharedFlow()

    // Latest visible-fragment indices reported by the screen, recomputed from the active clip on
    // every playback tick. Empty until the screen reports something.
    @Volatile private var visibleIndices: Set<Int> = emptySet()
    // The last index we fired an advance for, so a single off-screen transition emits one signal
    // (not one per 250 ms poll tick while the sentence stays ahead of the viewport).
    @Volatile private var lastAdvancedIndex: Int = -1

    init {
        scope.launch {
            controller.state.collect { s ->
                val t = track
                val active = if (t != null && s.currentAudioSrc != null) {
                    t.activeClipAt(s.currentAudioSrc, s.positionSec)?.textFragmentRef
                } else {
                    null
                }
                _activeFragmentRef.value = active
                maybeAdvance(active, s.isPlaying)
            }
        }
    }

    /** Connects the controller to [bundleFile] and queues [track]'s audio. */
    suspend fun open(itemId: String, bundleFile: File, track: ReadaloudTrack) {
        this.track = track
        lastAdvancedIndex = -1
        controller.prepare(bundleFile, track)
    }

    fun playFromHere(fragmentRef: String) {
        lastAdvancedIndex = -1
        controller.playFromFragment(fragmentRef)
    }

    /**
     * Starts playback at the reader's current position: the sentence under the cursor ([fragmentId])
     * if it maps to a clip, else the first clip of [href]'s chapter. Falls back to plain play (book
     * start) only when the position can't be resolved at all.
     */
    fun playFromReaderPosition(href: String, fragmentId: String?) {
        lastAdvancedIndex = -1
        val clip = track?.resolveStartClip(href, fragmentId)
        if (clip != null) {
            controller.playFromFragment(clip.textFragmentRef)
        } else {
            controller.play()
        }
    }

    fun play() = controller.play()

    fun pause() = controller.pause()

    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    /** Stops playback and tears the session down — the active fragment clears, so does the highlight. */
    fun close() {
        track = null
        visibleIndices = emptySet()
        lastAdvancedIndex = -1
        controller.stop()
        _activeFragmentRef.value = null
    }

    /** Cancels the state-collection scope. Call when the owning ViewModel is cleared (not on a
     *  mere bar-close, which must leave the coordinator reusable for the next open). */
    fun dispose() {
        scope.cancel()
    }

    /** The screen reports which fragment refs are currently rendered in the viewport. */
    fun reportVisibleFragments(fragmentRefs: Set<String>) {
        val t = track ?: run { visibleIndices = emptySet(); return }
        visibleIndices = fragmentRefs
            .map { t.indexOfFragment(it) }
            .filter { it >= 0 }
            .toSet()
        maybeAdvance(_activeFragmentRef.value, state.value.isPlaying)
    }

    private fun maybeAdvance(activeRef: String?, isPlaying: Boolean) {
        val t = track ?: return
        val activeIndex = activeRef?.let { t.indexOfFragment(it).takeIf { i -> i >= 0 } }
        if (AutoPageTurnRule.shouldAdvance(activeIndex, visibleIndices, isPlaying)) {
            // Only the first tick at a given active index fires — guards against re-emitting every
            // poll while the sentence remains ahead of the (now stale) visible set.
            if (activeIndex != null && activeIndex != lastAdvancedIndex) {
                lastAdvancedIndex = activeIndex
                _advanceEvents.tryEmit(Unit)
            }
        }
    }
}
