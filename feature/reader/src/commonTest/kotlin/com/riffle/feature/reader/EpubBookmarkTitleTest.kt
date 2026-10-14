package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class EpubBookmarkTitleTest {

    private val segments = listOf(
        RailSegment("Introduction", "intro.xhtml", weight = 5f),
        RailSegment("Chapter One", "chapter1.xhtml", weight = 20f),
        RailSegment("A Very Long Chapter Title That Exceeds Thirty Characters", "chapter2.xhtml", weight = 15f),
    )

    private val spineHrefs = listOf("intro.xhtml", "chapter1.xhtml", "chapter2.xhtml")

    @Test
    fun usesChapterTitleWhenSegmentMatches() {
        val title = buildEpubBookmarkTitle(segments, "chapter1.xhtml", spineHrefs, 0.42, null)
        assertEquals("Chapter One · 42%", title)
    }

    @Test
    fun truncatesChapterTitleAt30Characters() {
        val title = buildEpubBookmarkTitle(segments, "chapter2.xhtml", spineHrefs, 0.1, null)
        assertEquals("A Very Long Chapter Title That… · 10%", title)
    }

    @Test
    fun fallsBackToTotalProgressionWhenNoSegmentMatch() {
        val title = buildEpubBookmarkTitle(emptyList(), "chapter1.xhtml", emptyList(), 0.3, 0.75)
        assertEquals("75%", title)
    }

    @Test
    fun fallsBackToChapterProgressionWhenTotalProgressionIsNull() {
        val title = buildEpubBookmarkTitle(emptyList(), "chapter1.xhtml", emptyList(), 0.55, null)
        assertEquals("55%", title)
    }

    @Test
    fun clampsTitlePercentageToRange() {
        val title = buildEpubBookmarkTitle(segments, "chapter1.xhtml", spineHrefs, 1.05, null)
        assertEquals("Chapter One · 100%", title)
    }

    @Test
    fun usesChapterProgressionNotTotalWhenSegmentMatches() {
        // Within-chapter progression should be used when a title is found,
        // not total progression — they have different values here.
        val title = buildEpubBookmarkTitle(segments, "chapter1.xhtml", spineHrefs, 0.5, 0.9)
        assertEquals("Chapter One · 50%", title)
    }
}
