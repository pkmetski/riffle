package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossEpubIndexBuilderTest {

    @Test
    fun `emits one char map per chapter with each side's readable-character count`() {
        // Same logical text, but the two publications mark it up differently:
        // the ABS EPUB wraps the second sentence in extra markup (no readable-text
        // change), so readable-character counts per chapter match across the two.
        val absChapters = listOf(
            "<html><body><p>Hello there.</p></body></html>",
            "<html><body><p>Chapter <b>two</b> begins.</p></body></html>",
        )
        val storytellerChapters = listOf(
            "<html><body><span id=\"s1\">Hello there.</span></body></html>",
            "<html><body><span id=\"s1\">Chapter two begins.</span></body></html>",
        )

        val index = CrossEpubIndexBuilder.build(absChapters, storytellerChapters)

        assertEquals(
            listOf(
                ChapterCharMap(absChars = 12, storytellerChars = 12),
                ChapterCharMap(absChars = 19, storytellerChars = 19),
            ),
            index.perChapter,
        )
    }
}
