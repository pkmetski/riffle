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

    // ── skip + chapter navigation ──

    /** A resolved seek target: which queued audio file, and the offset within it. */
    data class Position(val audioSrc: String, val positionSec: Double)

    private fun chapterHrefOf(clip: MediaOverlayClip): String =
        clip.textFragmentRef.substringBefore('#').trimStart('/')

    /** Distinct chapter hrefs in reading order (clips are already in reading order). */
    private val chapterHrefs: List<String> = clips.map(::chapterHrefOf).distinct()

    /** Distinct audio files in playlist order — the same order [clips] is queued in. */
    private val fileOrder: List<String> = clips.map { it.audioSrc }.distinct()

    /** Each file's duration, approximated by the end of its last clip. */
    private val fileDuration: Map<String, Double> =
        clips.groupBy { it.audioSrc }.mapValues { (_, cs) -> cs.maxOf { it.clipEndSec } }

    /** Global start offset of each file on the concatenated timeline. */
    private val fileStart: Map<String, Double> = buildMap {
        var acc = 0.0
        for (src in fileOrder) {
            put(src, acc)
            acc += fileDuration[src] ?: 0.0
        }
    }

    private val totalDuration: Double = fileOrder.sumOf { fileDuration[it] ?: 0.0 }

    /** Number of chapters in the readaloud. */
    val chapterCount: Int get() = chapterHrefs.size

    /** Maps a live playback position to a global timeline offset, or null if [audioSrc] is unknown. */
    private fun globalOf(audioSrc: String?, positionSec: Double): Double? {
        val start = audioSrc?.let { fileStart[it] } ?: return null
        return start + positionSec
    }

    /** Inverse of [globalOf]: maps a clamped global offset back to a [Position]. */
    private fun positionAt(global: Double): Position {
        val g = global.coerceIn(0.0, totalDuration)
        // Last file whose start is <= g; offset is the remainder, clamped to that file's duration.
        val src = fileOrder.lastOrNull { (fileStart[it] ?: 0.0) <= g } ?: fileOrder.first()
        val within = (g - (fileStart[src] ?: 0.0)).coerceIn(0.0, fileDuration[src] ?: 0.0)
        return Position(src, within)
    }

    /**
     * The chapter index containing the live position, or -1 when nothing is playing. Uses the active
     * clip when the position sits inside one; otherwise the chapter of the latest clip at or before
     * the global position (covers inter-clip gaps).
     */
    fun chapterIndexAt(audioSrc: String?, positionSec: Double): Int {
        if (audioSrc == null) return -1
        activeClipAt(audioSrc, positionSec)?.let { return chapterHrefs.indexOf(chapterHrefOf(it)) }
        val global = globalOf(audioSrc, positionSec) ?: return -1
        val clip = clips.lastOrNull { (globalOf(it.audioSrc, it.clipBeginSec) ?: Double.MAX_VALUE) <= global }
            ?: return -1
        return chapterHrefs.indexOf(chapterHrefOf(clip))
    }

    /** The first clip of chapter [index], or null when [index] is out of range. */
    fun firstClipOfChapter(index: Int): MediaOverlayClip? {
        val href = chapterHrefs.getOrNull(index) ?: return null
        return clips.firstOrNull { chapterHrefOf(it) == href }
    }

    /** The chapter index [clip] belongs to (-1 if it isn't in this track). */
    fun chapterIndexOfClip(clip: MediaOverlayClip): Int = chapterHrefs.indexOf(chapterHrefOf(clip))

    /** Resolves a relative skip of [deltaSec] from the live position, clamped to the whole readaloud. */
    fun resolveRelativeSkip(audioSrc: String?, positionSec: Double, deltaSec: Double): Position? {
        val global = globalOf(audioSrc, positionSec) ?: return null
        return positionAt(global + deltaSec)
    }

    /**
     * Resolves a chapter jump. [forward] true -> the next chapter's first clip (null at the last
     * chapter). [forward] false -> restart the current chapter, unless the live position is within
     * [nearStartSec] of the current chapter's start, in which case go to the previous chapter (or
     * restart the first chapter when there is none).
     */
    fun resolveChapterSkip(audioSrc: String?, positionSec: Double, forward: Boolean, nearStartSec: Double): MediaOverlayClip? {
        val index = chapterIndexAt(audioSrc, positionSec)
        if (index < 0) return null
        if (forward) return firstClipOfChapter(index + 1)
        val currentFirst = firstClipOfChapter(index) ?: return null
        val global = globalOf(audioSrc, positionSec) ?: return currentFirst
        val chapterStart = globalOf(currentFirst.audioSrc, currentFirst.clipBeginSec) ?: 0.0
        val nearStart = (global - chapterStart) <= nearStartSec
        return if (nearStart && index > 0) firstClipOfChapter(index - 1) else currentFirst
    }

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
