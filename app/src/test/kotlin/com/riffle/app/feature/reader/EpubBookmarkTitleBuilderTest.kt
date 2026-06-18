package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubBookmarkTitleBuilderTest {

    private fun toc(vararg entries: TocEntry) = entries.toList()

    @Test
    fun `named chapter uses title and within-chapter pct`() {
        val entries = toc(
            TocEntry("Prologue", "ch1.xhtml"),
            TocEntry("The Egg", "ch2.xhtml"),
        )
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch2.xhtml",
            chapterProgression = 0.34,
            totalProgression = 0.55,
            tocEntries = entries,
        )
        assertEquals("The Egg · 34%", title)
    }

    @Test
    fun `blank chapter title falls back to totalProgression pct`() {
        val entries = toc(TocEntry("   ", "ch1.xhtml"))
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch1.xhtml",
            chapterProgression = 0.67,
            totalProgression = 0.45,
            tocEntries = entries,
        )
        assertEquals("45%", title)
    }

    @Test
    fun `no matching TOC entry falls back to totalProgression pct`() {
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "unknown.xhtml",
            chapterProgression = 0.5,
            totalProgression = 0.3,
            tocEntries = emptyList(),
        )
        assertEquals("30%", title)
    }

    @Test
    fun `null totalProgression falls back to chapterProgression pct`() {
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch1.xhtml",
            chapterProgression = 0.72,
            totalProgression = null,
            tocEntries = emptyList(),
        )
        assertEquals("72%", title)
    }

    @Test
    fun `chapter title found in nested TOC children`() {
        val entries = toc(
            TocEntry("Part I", "part1.xhtml", children = listOf(
                TocEntry("The Beginning", "ch1.xhtml"),
            )),
        )
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch1.xhtml",
            chapterProgression = 0.1,
            totalProgression = null,
            tocEntries = entries,
        )
        assertEquals("The Beginning · 10%", title)
    }

    @Test
    fun `progression rounds correctly at boundary`() {
        val entries = toc(TocEntry("One", "ch.xhtml"))
        val title = EpubBookmarkTitleBuilder.build(
            chapterHref = "ch.xhtml",
            chapterProgression = 0.999,
            totalProgression = null,
            tocEntries = entries,
        )
        assertEquals("One · 100%", title)
    }
}
