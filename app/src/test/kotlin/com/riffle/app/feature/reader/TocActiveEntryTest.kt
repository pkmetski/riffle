package com.riffle.app.feature.reader

import com.riffle.core.domain.TocEntry
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
}
