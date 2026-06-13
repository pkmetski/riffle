package com.riffle.app.feature.reader

import com.riffle.core.domain.SentenceQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [scopeSentencesToChapter], the fix for "Play from here jumps to a completely different
 * chapter" (The Martian ch16 → ch8). The selection resolver locates a tapped sentence by searching the
 * rendered page for each candidate sentence's short text prefix; handed the WHOLE book it matches a
 * FOREIGN chapter's sentence whose text recurs inside a current-chapter sentence and seeks that chapter.
 * Scoping the candidate list to the chapter being read removes the cross-chapter matches.
 */
class ScopeSentencesToChapterTest {

    private fun q(text: String) = SentenceQuote(before = "", highlight = text, after = "")

    // The real Martian collision: ch8's standalone "He thought for a moment." (id26-s205) is embedded
    // verbatim inside ch16's compound sentence (id34-s833). With the whole book in scope, a tap inside
    // ch16's "He thought for a moment." run resolves to the ch8 sentence; scoping to ch16 prevents it.
    private val quotes = mapOf(
        "id26-s205" to q("He thought for a moment."),
        "id26-s206" to q("“Sorry, Mitch, I’m with Venkat on this one,” he said."),
        "id34-s832" to q("Mitch shrugged."),
        "id34-s833" to q("“But if I wasn’t willing to take risks to save lives, I’d…” He thought for a moment."),
        "id34-s834" to q("“Well, I guess I’d be you.”"),
    )
    private val chapters = mapOf(
        "id26-s205" to "text/part0013.html",
        "id26-s206" to "text/part0013.html",
        "id34-s832" to "text/part0021.html",
        "id34-s833" to "text/part0021.html",
        "id34-s834" to "text/part0021.html",
    )

    @Test
    fun scopesToTheChapterBeingRead_excludingForeignChapterSentences() {
        val scoped = scopeSentencesToChapter(quotes, chapters, "text/part0021.html")
        val ids = scoped.map { it.first }.toSet()
        assertEquals(setOf("id34-s832", "id34-s833", "id34-s834"), ids)
        assertTrue("ch8 sentences must be excluded so the resolver can't seat on them", "id26-s205" !in ids)
        assertTrue("id26-s206" !in ids)
    }

    @Test
    fun reconcilesHrefByBasename_soAnOpfFolderPrefixDoesNotBreakScoping() {
        // The rendered (ABS) href may carry a folder prefix the bundle href doesn't (or vice versa);
        // matching on the file basename keeps scoping working when only the directory differs.
        val scoped = scopeSentencesToChapter(quotes, chapters, "OEBPS/text/part0021.html#frag")
        assertEquals(setOf("id34-s832", "id34-s833", "id34-s834"), scoped.map { it.first }.toSet())
    }

    @Test
    fun fallsBackToWholeBookWhenNoSentenceMapsToTheCurrentChapter() {
        // An unmatched href (chapter can't be reconciled with the bundle) must not yield an empty list —
        // that would break "Play from here" entirely; fall back to the whole book (prior behaviour).
        val scoped = scopeSentencesToChapter(quotes, chapters, "text/unknown-chapter.html")
        assertEquals(quotes.keys, scoped.map { it.first }.toSet())
    }

    @Test
    fun fallsBackToWholeBookWhenChapterMapIsEmpty() {
        // Quotes built but the chapter map not yet populated → don't return empty.
        val scoped = scopeSentencesToChapter(quotes, emptyMap(), "text/part0021.html")
        assertEquals(quotes.keys, scoped.map { it.first }.toSet())
    }
}
