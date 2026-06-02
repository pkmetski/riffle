package com.riffle.core.domain

/**
 * The ordered Media Overlay timeline for a Readaloud, built from every `.smil` entry in the
 * Storyteller EPUB bundle (concatenated in spine order). Relates the things the player and reader
 * need: a playback position `(audioSrc, sec)`, a text fragment reference, and the clip to start
 * narration from for a given reader position.
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

    /**
     * The clip to start narration from for a reader position given by its chapter [href] and
     * optional [fragmentId]. Prefers an exact "href#fragmentId" match (the sentence under the
     * reader's cursor); else the first clip of that chapter; else — when the chapter has no Media
     * Overlay at all (e.g. a Storyteller un-narrated chapter-heading page, `…_split_000.html`, the
     * page the TOC lands on) — the first narrated clip at or after the reader's position in reading
     * order. Returns null only when the reader is past all narrated content. This keeps playback on
     * (or just ahead of) the page the user is reading rather than restarting the whole book. Leading
     * slashes are ignored so Readium's "/text/x" and SMIL's "text/x" hrefs reconcile.
     *
     * Relies on [clips] being in reading order (MediaOverlayReader concatenates `.smil` entries in
     * spine order) with reading-order-monotonic hrefs, so the first clip whose chapter href is not
     * less than [href] is the start of the nearest following narrated chapter.
     */
    fun resolveStartClip(href: String, fragmentId: String?): MediaOverlayClip? {
        val target = href.trimStart('/')
        if (fragmentId != null) {
            clips.firstOrNull { it.textFragmentRef.trimStart('/') == "$target#$fragmentId" }?.let { return it }
        }
        fun chapterHref(clip: MediaOverlayClip) = clip.textFragmentRef.substringBefore('#').trimStart('/')
        clips.firstOrNull { chapterHref(it) == target }?.let { return it }
        return clips.firstOrNull { chapterHref(it) >= target }
    }
}
