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
            // Fall back to the bare span id. The rendered publication (the ABS EPUB, ADR 0026) can carry
            // different chapter hrefs than the Storyteller bundle the SMIL clips come from, so the href
            // portions won't match even for the same sentence. But sentence-span ids are only unique
            // WITHIN a document — they recur across chapters — so a plain bare-id match would return the
            // earliest book-wide occurrence and jump narration to an identically-id'd sentence in an
            // earlier chapter (the "Play-from-here reset my progress" bug). So among the clips sharing the
            // id, prefer the one in the reader's chapter; only when the chapter can't be matched (the two
            // EPUBs share nothing) do we fall back to the first occurrence, so a book whose hrefs don't
            // line up still plays the right sentence when the id happens to be unique.
            val sharingId = clips.filter { it.textFragmentRef.substringAfter('#', "") == fragmentId }
            sharingId.firstOrNull { sameChapter(it, target) }?.let { return it }
            sharingId.firstOrNull()?.let { return it }
        }
        fun chapterHref(clip: MediaOverlayClip) = clip.textFragmentRef.substringBefore('#').trimStart('/')
        clips.firstOrNull { chapterHref(it) == target }?.let { return it }
        return clips.firstOrNull { chapterHref(it) >= target }
    }

    /**
     * Whether [clip]'s chapter is [targetHref], tolerant of the differing href schemes the same chapter
     * carries across the pipeline: the player track's full zip path ("OEBPS/text/x.xhtml"), a bundle
     * spine href ("text/x.xhtml"), and a raw SMIL ref ("../text/x.xhtml"). [resolveEpubHref] collapses
     * `.`/`..`; the suffix test absorbs a leading directory (the OPF folder) one side carries and the
     * other doesn't. Chapter hrefs are unique per book, so the suffix can't cross-match two chapters.
     */
    private fun sameChapter(clip: MediaOverlayClip, targetHref: String): Boolean {
        val c = resolveEpubHref(clip.textFragmentRef.substringBefore('#')).trimStart('/')
        val t = resolveEpubHref(targetHref).trimStart('/')
        return c == t || c.endsWith("/$t") || t.endsWith("/$c")
    }
}
