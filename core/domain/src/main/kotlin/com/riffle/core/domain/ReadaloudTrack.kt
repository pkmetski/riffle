package com.riffle.core.domain

/**
 * The ordered Media Overlay timeline for a Readaloud, built from every `.smil` entry in the
 * Storyteller EPUB bundle (concatenated in spine order). Maps freely between the three things
 * the player and reader need to relate: a playback position `(audioSrc, sec)`, a text fragment
 * reference, and the document-order index used for [AutoPageTurnRule] decisions.
 */
class ReadaloudTrack(val clips: List<MediaOverlayClip>) {

    private val indexByFragment: Map<String, Int> =
        clips.withIndex().associate { (i, clip) -> clip.textFragmentRef to i }

    /**
     * The clip narrating [positionSec] within [audioSrc], or null if the position sits in a gap
     * or names an audio file with no overlay. Ranges are half-open `[begin, end)` so abutting
     * clips don't both claim the boundary instant.
     */
    fun activeClipAt(audioSrc: String, positionSec: Double): MediaOverlayClip? =
        clips.firstOrNull {
            it.audioSrc == audioSrc && positionSec >= it.clipBeginSec && positionSec < it.clipEndSec
        }

    /** The clip for a text fragment — the seek target when the user picks "Play from here". */
    fun clipForFragment(textFragmentRef: String): MediaOverlayClip? =
        indexByFragment[textFragmentRef]?.let { clips[it] }

    /** Document-order index of a fragment, or -1 if absent. */
    fun indexOfFragment(textFragmentRef: String): Int =
        indexByFragment[textFragmentRef] ?: -1
}
