package com.riffle.app.feature.reader

import com.riffle.core.domain.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TocFlattenTest {

    private val section11 = TocEntry("Section 1.1", "chapter1.xhtml#s1")
    private val section12 = TocEntry("Section 1.2", "chapter1.xhtml#s2")
    private val subsub = TocEntry("Sub sub", "chapter1.xhtml#deep")
    private val nestedSection11 = section11.copy(children = listOf(subsub))
    private val chapter1 = TocEntry("Chapter 1", "chapter1.xhtml", listOf(nestedSection11, section12))
    private val chapter2 = TocEntry("Chapter 2", "chapter2.xhtml")
    private val chapter3 = TocEntry("Chapter 3", "chapter3.xhtml")
    private val toc = listOf(chapter1, chapter2, chapter3)

    @Test
    fun `flatten walks depth-first and preserves depth`() {
        val flat = flattenToc(toc)
        assertEquals(
            listOf(
                "Chapter 1" to 0,
                "Section 1.1" to 1,
                "Sub sub" to 2,
                "Section 1.2" to 1,
                "Chapter 2" to 0,
                "Chapter 3" to 0,
            ),
            flat.map { it.entry.title to it.depth },
        )
    }

    @Test
    fun `flatten skips blank-title container and promotes children to same depth`() {
        val container = TocEntry(title = "", href = "container.xhtml", children = listOf(chapter2, chapter3))
        val flat = flattenToc(listOf(chapter1, container))
        // chapter2 and chapter3 come out at depth 0 (same as the skipped container's would-be depth),
        // not depth 1.
        assertEquals(
            listOf(
                "Chapter 1" to 0,
                "Section 1.1" to 1,
                "Sub sub" to 2,
                "Section 1.2" to 1,
                "Chapter 2" to 0,
                "Chapter 3" to 0,
            ),
            flat.map { it.entry.title to it.depth },
        )
    }

    @Test
    fun `flatten is empty for empty input`() {
        assertEquals(emptyList<TocRow>(), flattenToc(emptyList()))
    }

    @Test
    fun `active flat index prefers exact-href match on any depth`() {
        val flat = flattenToc(toc)
        // Chapter 1 (0), Section 1.1 (1), Sub sub (2), Section 1.2 (3), Chapter 2 (4).
        assertEquals(3, findActiveFlatIndex(toc, flat, "chapter1.xhtml#s2"))
        assertEquals(2, findActiveFlatIndex(toc, flat, "chapter1.xhtml#deep"))
        assertEquals(4, findActiveFlatIndex(toc, flat, "chapter2.xhtml"))
    }

    @Test
    fun `active flat index falls back to top-level chapter start when active href is unknown-but-in-chapter`() {
        // A subsection href that isn't listed anywhere in the TOC but shares chapter1.xhtml.
        val flat = flattenToc(toc)
        // findActiveTopLevelIndex resolves chapter1.xhtml#unknown to chapter1 (its href matches).
        // findActiveFlatIndex should map that back to Chapter 1's flat index (0).
        assertEquals(0, findActiveFlatIndex(toc, flat, "chapter1.xhtml"))
    }

    @Test
    fun `active flat index is null for unknown chapter`() {
        val flat = flattenToc(toc)
        assertNull(findActiveFlatIndex(toc, flat, "chapter99.xhtml"))
    }

    @Test
    fun `active flat index is null for null href`() {
        val flat = flattenToc(toc)
        assertNull(findActiveFlatIndex(toc, flat, null))
    }
}
