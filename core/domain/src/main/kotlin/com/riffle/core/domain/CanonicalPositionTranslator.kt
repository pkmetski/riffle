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
    private val smilClips: List<MediaOverlayClip>,
    private val index: CrossEpubIndex = CrossEpubIndex(emptyList()),
    private val fragmentProgressions: Map<String, ChapterProgression> = emptyMap(),
) {

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
        smilClips.firstOrNull { seconds >= it.clipBeginSec && seconds < it.clipEndSec }
            ?.textFragmentRef

    /**
     * Storyteller text fragment → the audio time where its narration begins. Returns
     * `null` when no clip narrates [textFragmentRef].
     */
    fun textFragmentToAudioSeconds(textFragmentRef: String): Double? =
        smilClips.firstOrNull { it.textFragmentRef == textFragmentRef }?.clipBeginSec
}
