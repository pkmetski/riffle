package com.riffle.core.domain

/** One spine chapter of an EPUB: its manifest href and its (already-extracted) HTML. */
data class EpubChapterHtml(
    val href: String,
    val html: String,
)

/**
 * Resolves the Storyteller EPUB's SMIL fragment references (`href#id`) to canonical
 * [ChapterProgression]s, so [DefaultPositionTranslator] can bridge audio time to a
 * reader position. Pure given the spine HTML and the parsed SMIL clips; fragments whose
 * chapter or element cannot be located are dropped (a missing anchor degrades a single
 * conversion, never the whole map).
 */
object StorytellerFragmentIndexBuilder {

    /**
     * @param resolveProgressions resolves a chapter's HTML + the element ids it needs to each id's
     *   within-chapter progression. Invoked **once per chapter** (not once per clip) — overridable
     *   so tests can assert that O(chapters), not O(clips), parses happen. Defaults to the
     *   single-parse [EpubTextChars.progressionsOfElementIds].
     */
    fun build(
        chapters: List<EpubChapterHtml>,
        clips: List<MediaOverlayClip>,
        resolveProgressions: (html: String, elementIds: Set<String>) -> Map<String, Double> =
            EpubTextChars::progressionsOfElementIds,
    ): Map<String, ChapterProgression> {
        // Storyteller's SMIL files live in their own directory, so their fragment refs are written
        // relative to it (e.g. "../text/part6.html#s0") while spine chapter hrefs are root-relative
        // ("text/part6.html"). Key by a path with "."/".." segments resolved so the forms match.
        val chapterIndexByHref = chapters.withIndex().associate { (i, c) -> resolveEpubHref(c.href) to i }

        // Group fragments by chapter so each chapter's HTML is parsed once, not once per clip — a
        // readaloud has thousands of clips, and re-parsing per clip is pathologically slow.
        val fragsByChapter = LinkedHashMap<Int, MutableList<Pair<String, String>>>() // chapter → (ref, elementId)
        for (clip in clips) {
            val ref = clip.textFragmentRef
            val hash = ref.indexOf('#')
            if (hash < 0) continue
            val chapterIndex = chapterIndexByHref[resolveEpubHref(ref.substring(0, hash))] ?: continue
            fragsByChapter.getOrPut(chapterIndex) { mutableListOf() }.add(ref to ref.substring(hash + 1))
        }

        val result = LinkedHashMap<String, ChapterProgression>()
        for ((chapterIndex, frags) in fragsByChapter) {
            val progressions = resolveProgressions(
                chapters[chapterIndex].html, frags.mapTo(HashSet()) { it.second },
            )
            for ((ref, elementId) in frags) {
                val progression = progressions[elementId] ?: continue
                result[ref] = ChapterProgression(chapterIndex, progression)
            }
        }
        return result
    }

}
