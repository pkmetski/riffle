package com.riffle.core.domain

/**
 * The single point of conversion between the three coordinate systems a matched
 * readaloud book holds a position in (ADR 0019):
 *
 *  - **audio-seconds** — ABS audiobook progress, an offset into the audio file.
 *  - **Storyteller-EPUB text position** — a text fragment reference (`href#id`) in
 *    Storyteller's publication, the anchor of a Readium Locator.
 *  - **ABS-EPUB CFI** — a CFI in the ABS-served EPUB (a different file for the same
 *    logical book).
 *
 * Every conversion is best-effort: an input that cannot be placed in the target
 * system returns `null` rather than guessing, so a missing mapping degrades a sync
 * cycle to a deferred PATCH instead of a wrong one.
 */
class CanonicalPositionTranslator(
    smilClips: List<MediaOverlayClip>,
    private val index: CrossEpubIndex = CrossEpubIndex(emptyList()),
    private val fragmentProgressions: Map<String, ChapterProgression> = emptyMap(),
) {

    /**
     * SMIL clip times are **per audio file** — each file's clips restart near 0. The ABS audiobook is
     * those files concatenated into one timeline, so to translate a clip to an absolute ABS
     * `currentTime` we add the cumulative duration of every file before it, in playback (document)
     * order. A file's duration is taken as its largest `clipEnd`. A single-file bundle is unchanged
     * (every offset is 0). Without this, a clip time from file N is sent as an absolute time and lands
     * a fraction of the way in (e.g. 1/8 instead of 1/2).
     */
    private val absoluteClips: List<MediaOverlayClip> = run {
        val fileOrder = LinkedHashSet<String>().apply { smilClips.forEach { add(it.audioSrc) } }
        val fileDuration = smilClips.groupBy { it.audioSrc }.mapValues { e -> e.value.maxOf { it.clipEndSec } }
        val offsetOf = HashMap<String, Double>()
        var acc = 0.0
        for (file in fileOrder) { offsetOf[file] = acc; acc += fileDuration[file] ?: 0.0 }
        smilClips.map { c ->
            val offset = offsetOf[c.audioSrc] ?: 0.0
            if (offset == 0.0) c else c.copy(clipBeginSec = c.clipBeginSec + offset, clipEndSec = c.clipEndSec + offset)
        }
    }

    /**
     * Audio time → canonical (Storyteller-EPUB) progression: the narrated SMIL fragment
     * resolved to its within-chapter progression. Returns `null` when no clip covers the
     * time or the fragment has no resolved progression.
     */
    fun audioSecondsToStorytellerProgression(seconds: Double): ChapterProgression? =
        audioSecondsToTextFragment(seconds)?.let { fragmentProgressions[it] }

    /**
     * Canonical (Storyteller-EPUB) progression → audio time: the start of the clip for
     * the latest narrated fragment at or before [pos] within the same chapter. Returns
     * `null` when no narrated fragment precedes [pos].
     */
    fun storytellerProgressionToAudioSeconds(pos: ChapterProgression): Double? {
        val fragment = fragmentProgressions.entries
            .filter { it.value.chapterIndex == pos.chapterIndex && it.value.progression <= pos.progression }
            .maxByOrNull { it.value.progression }
            ?.key
            ?: return null
        return textFragmentToAudioSeconds(fragment)
    }

    /**
     * A within-chapter progression in the Storyteller EPUB → the same logical point as
     * a within-chapter progression in the ABS EPUB, preserving the absolute readable-
     * character offset (the chapters hold the same prose, marked up differently).
     * Returns `null` when the chapter has no entry in the cross-EPUB index.
     */
    fun storytellerToAbsProgression(pos: ChapterProgression): ChapterProgression? =
        remap(pos, fromChars = { it.storytellerChars }, toChars = { it.absChars })

    /** Inverse of [storytellerToAbsProgression]. */
    fun absToStorytellerProgression(pos: ChapterProgression): ChapterProgression? =
        remap(pos, fromChars = { it.absChars }, toChars = { it.storytellerChars })

    /**
     * Book-wide progress (0..1) of an ABS-domain [pos], weighting each chapter by its readable-
     * character count: the chars before the chapter plus the within-chapter offset, over the book
     * total. Lets the ebook progress bar be filled correctly even for a position reconstructed from a
     * remote (audiobook / Storyteller), whose canonical carries no book-wide progression — without
     * this the propagated ebook write would clear the server's progress to 0. Returns `null` when the
     * index has no character data to weight by.
     */
    fun absBookProgression(pos: ChapterProgression): Double? {
        val chapters = index.perChapter
        val chapter = chapters.getOrNull(pos.chapterIndex) ?: return null
        val total = chapters.sumOf { it.absChars }
        if (total <= 0L) return null
        val before = chapters.take(pos.chapterIndex).sumOf { it.absChars }
        val within = pos.progression.coerceIn(0.0, 1.0) * chapter.absChars
        return ((before + within) / total).coerceIn(0.0, 1.0)
    }

    private inline fun remap(
        pos: ChapterProgression,
        fromChars: (ChapterCharMap) -> Long,
        toChars: (ChapterCharMap) -> Long,
    ): ChapterProgression? {
        val map = index.perChapter.getOrNull(pos.chapterIndex) ?: return null
        val to = toChars(map)
        if (to == 0L) return null
        val charOffset = pos.progression * fromChars(map)
        return ChapterProgression(pos.chapterIndex, (charOffset / to).coerceIn(0.0, 1.0))
    }

    /**
     * Audio time → the Storyteller text fragment the SMIL narrates at that instant.
     * Returns `null` when no clip covers [seconds] (e.g. before the first or past the
     * last clip).
     */
    fun audioSecondsToTextFragment(seconds: Double): String? =
        absoluteClips.firstOrNull { seconds >= it.clipBeginSec && seconds < it.clipEndSec }
            ?.textFragmentRef

    /**
     * Storyteller text fragment → the audio time where its narration begins. Returns
     * `null` when no clip narrates [textFragmentRef].
     */
    fun textFragmentToAudioSeconds(textFragmentRef: String): Double? =
        absoluteClips.firstOrNull { it.textFragmentRef == textFragmentRef }?.clipBeginSec
}
