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

        // Group fragments by chapter so each chapter's HTML is parsed once, not once per clip — a
        // readaloud has thousands of clips, and re-parsing per clip is pathologically slow.
        val fragsByChapter = LinkedHashMap<Int, MutableList<Pair<String, String>>>() // chapter → (ref, elementId)
        for (clip in clips) {
            val ref = clip.textFragmentRef
            val hash = ref.indexOf('#')
            if (hash < 0) continue
            val chapterIndex = chapterIndexByHref[resolvePath(ref.substring(0, hash))] ?: continue
            fragsByChapter.getOrPut(chapterIndex) { mutableListOf() }.add(ref to ref.substring(hash + 1))
        }

        val result = LinkedHashMap<String, ChapterProgression>()
        for ((chapterIndex, frags) in fragsByChapter) {
            val progressions = EpubTextChars.progressionsOfElementIds(
                chapters[chapterIndex].html, frags.mapTo(HashSet()) { it.second },
            )
            for ((ref, elementId) in frags) {
                val progression = progressions[elementId] ?: continue
                result[ref] = ChapterProgression(chapterIndex, progression)
            }
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
