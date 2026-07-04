package com.riffle.app.feature.reader.readaloud

import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.sentence.FragmentRef
import com.riffle.core.domain.sentence.SentenceTicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [SentenceTicker] for Readaloud: resolves the text fragment currently narrated from the
 * controller's polled audio-clock position via [ReadaloudTrack.activeClipAt]. Extracted from
 * [PlayerCoordinator] (ADR 0039) — [PlayerCoordinator] retains track lifecycle and cross-href
 * resolution, and holds this ticker to derive [currentFragment] + [progress].
 */
class AudioClockTicker(
    private val controller: ReadaloudController,
    private val trackProvider: () -> ReadaloudTrack?,
    scope: CoroutineScope,
) : SentenceTicker {
    private val _currentFragment = MutableStateFlow<FragmentRef?>(null)
    override val currentFragment: StateFlow<FragmentRef?> = _currentFragment.asStateFlow()

    private val _progress = MutableStateFlow<Double?>(null)
    override val progress: StateFlow<Double?> = _progress.asStateFlow()

    init {
        scope.launch {
            controller.state.collect { state ->
                val track = trackProvider()
                val clip = if (track != null && state.currentAudioSrc != null) {
                    track.activeClipAt(state.currentAudioSrc, state.positionSec)
                } else {
                    null
                }
                _currentFragment.value = clip?.textFragmentRef
                _progress.value = clip?.progressAt(state.positionSec)
            }
        }
    }

    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun stop() = controller.stop()
    override fun goTo(fragment: FragmentRef) = controller.playFromFragment(fragment)

    /**
     * Synchronously clears [currentFragment]/[progress] without waiting for the next
     * [ReadaloudController.state] emission. [controller]'s stop/handoff paths reset its state to a
     * fresh [ReadaloudController.PlaybackState] which would eventually clear these too, but callers
     * that need the clear to be visible immediately (e.g. [PlayerCoordinator.close]) call this directly.
     */
    fun reset() {
        _currentFragment.value = null
        _progress.value = null
    }
}
