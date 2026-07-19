package com.riffle.app.feature.reader

import com.riffle.core.models.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TocActiveEntryTest {

    private val section11 = TocEntry("Section 1.1", "chapter1.xhtml#s1")
    private val section12 = TocEntry("Section 1.2", "chapter1.xhtml#s2")
    private val chapter1 = TocEntry("Chapter 1", "chapter1.xhtml", listOf(section11, section12))
    private val chapter2 = TocEntry("Chapter 2", "chapter2.xhtml")
    private val chapter3 = TocEntry("Chapter 3", "chapter3.xhtml")
    private val toc = listOf(chapter1, chapter2, chapter3)

    @Test
    fun `finds top-level entry by exact href`() {
        assertEquals(chapter2, findActiveEntry(toc, "chapter2.xhtml"))
    }

    @Test
    fun `finds top-level entry for chapter 3`() {
        assertEquals(chapter3, findActiveEntry(toc, "chapter3.xhtml"))
    }

    @Test
    fun `finds child entry by exact href with fragment`() {
        assertEquals(section11, findActiveEntry(toc, "chapter1.xhtml#s1"))
    }

    @Test
    fun `finds second child entry by exact href`() {
        assertEquals(section12, findActiveEntry(toc, "chapter1.xhtml#s2"))
    }

    @Test
    fun `returns null when no entry matches`() {
        assertNull(findActiveEntry(toc, "chapter4.xhtml"))
    }

    @Test
    fun `returns null for empty list`() {
        assertNull(findActiveEntry(emptyList(), "chapter1.xhtml"))
    }

    @Test
    fun `active top-level index matches the entry itself`() {
        assertEquals(1, findActiveTopLevelIndex(toc, "chapter2.xhtml"))
    }

    @Test
    fun `active top-level index resolves a nested child to its top-level ancestor`() {
        assertEquals(0, findActiveTopLevelIndex(toc, "chapter1.xhtml#s2"))
    }

    @Test
    fun `active top-level index is null when nothing matches`() {
        assertNull(findActiveTopLevelIndex(toc, "chapter4.xhtml"))
    }

    @Test
    fun `active top-level index is null for null href`() {
        assertNull(findActiveTopLevelIndex(toc, null))
    }

    // "Extreme Ownership" TOC pattern: every chapter is anchored with #chN in the nav
    // document. Readium's Locator.href never carries fragments (they live in
    // locations.fragments), so exact-string matching cannot line the two sides up.
    // These tests pin the path-only fallback added in findActiveEntry.
    private val eoChapter1 = TocEntry("Chapter 1", "xhtml/chapter1.xhtml#ch1")
    private val eoChapter2 = TocEntry("Chapter 2", "xhtml/chapter2.xhtml#ch2")
    private val eoForeword = TocEntry("Foreword", "xhtml/foreword.xhtml")
    private val eoToc = listOf(eoForeword, eoChapter1, eoChapter2)

    @Test
    fun `path fallback resolves fragment-anchored entry when current href has no fragment`() {
        assertEquals(eoChapter1, findActiveEntry(eoToc, "xhtml/chapter1.xhtml"))
        assertEquals(eoChapter2, findActiveEntry(eoToc, "xhtml/chapter2.xhtml"))
    }

    @Test
    fun `path fallback still returns null when no entry shares the resource`() {
        assertNull(findActiveEntry(eoToc, "xhtml/chapter99.xhtml"))
    }

    @Test
    fun `exact fragment match wins over path-only fallback`() {
        // With multiple TOC entries sharing a resource path but different fragments, a
        // current href that carries the exact fragment must still pick that specific entry
        // — not the first one on the resource.
        assertEquals(section12, findActiveEntry(toc, "chapter1.xhtml#s2"))
    }

    @Test
    fun `top-level index resolves fragment-anchored entry from fragment-less href`() {
        assertEquals(1, findActiveTopLevelIndex(eoToc, "xhtml/chapter1.xhtml"))
    }
}
