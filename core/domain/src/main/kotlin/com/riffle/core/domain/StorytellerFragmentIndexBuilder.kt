package com.riffle.core.domain

/** One spine chapter of an EPUB: its manifest href and its (already-extracted) HTML. */
data class EpubChapterHtml(
    val href: String,
    val html: String,
)

/**
 * Resolves the Storyteller EPUB's SMIL fragment references (`href#id`) to canonical
 * [ChapterProgression]s, so [CanonicalPositionTranslator] can bridge audio time to a
 * reader position. Pure given the spine HTML and the parsed SMIL clips; fragments whose
 * chapter or element cannot be located are dropped (a missing anchor degrades a single
 * conversion, never the whole map).
 */
object StorytellerFragmentIndexBuilder {

    fun build(
        chapters: List<EpubChapterHtml>,
        clips: List<MediaOverlayClip>,
    ): Map<String, ChapterProgression> {
        // Storyteller's SMIL files live in their own directory, so their fragment refs are written
        // relative to it (e.g. "../text/part6.html#s0") while spine chapter hrefs are root-relative
        // ("text/part6.html"). Key by a path with "."/".." segments resolved so the forms match.
        val chapterIndexByHref = chapters.withIndex().associate { (i, c) -> resolvePath(c.href) to i }
        val result = LinkedHashMap<String, ChapterProgression>()
        for (clip in clips) {
            val ref = clip.textFragmentRef
            val hash = ref.indexOf('#')
            if (hash < 0) continue
            val href = ref.substring(0, hash)
            val elementId = ref.substring(hash + 1)
            val chapterIndex = chapterIndexByHref[resolvePath(href)] ?: continue
            val progression = EpubTextChars.progressionOfElementId(chapters[chapterIndex].html, elementId)
                ?: continue
            result[ref] = ChapterProgression(chapterIndex, progression)
        }
        return result
    }

    /** Collapses "." and ".." segments in a relative EPUB href so a SMIL-relative ref and the
     *  spine-relative chapter href for the same file compare equal. Leading ".." that would escape
     *  the root are dropped (Storyteller SMIL sits one directory below the text it references). */
    private fun resolvePath(href: String): String {
        val out = ArrayDeque<String>()
        for (segment in href.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }
}
